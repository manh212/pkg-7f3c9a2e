/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Opt-in, zero-polling battery and resource profiler for Meridian (Developer Settings → Quản lý
 * mức sử dụng pin).
 *
 * Disabled by default (`pref_battery_profiler_enabled = false`) so normal users incur virtually
 * zero overhead (a single volatile boolean check per hook). When enabled, tracks:
 * 1. Real battery drain via [BatteryManager] (`CHARGE_COUNTER` µAh, unplugged `%` drop per segment,
 *    current mA, temp), split into unplugged Screen-On vs Screen-Off.
 * 2. Exact per-thread CPU (`/proc/self/task/<tid>/stat`) cross-checked against total process CPU
 *    (`/proc/self/stat`) so short-lived and newly spawned threads are never lost.
 * 3. Speech / TTS output pipeline: speak requests, transmitted character counts, cache hits vs live
 *    TTS calls, interrupted utterances, PCM IPC bytes, DSP/resample CPU time, and NavigationPcmCache
 *    waste rate (cancelled/expired/timeout/superseded).
 * 4. Hardware & system consumers: LowLatencyAudio audible vs keep-warm idle, Earcons & Haptics,
 *    Sensors, WakeLocks, Network bytes (`TrafficStats`), and Top apps firing AccessibilityEvents
 *    both while Screen-On and Screen-Off.
 */
object BatteryUsageTracker {

  const val PREF_ENABLED_KEY = "pref_battery_profiler_enabled"

  private val stateLock = Any()
  private val initialized = AtomicBoolean(false)
  @Volatile private var enabled = false

  // Session baseline & current state
  @Volatile private var sessionStartWallMs = 0L
  @Volatile private var sessionStartElapsedMs = 0L
  @Volatile private var lastSegmentElapsedMs = 0L
  @Volatile private var startBatteryLevel = -1
  @Volatile private var lastBatteryLevel = -1
  @Volatile private var lastChargeCounterUah = Long.MIN_VALUE
  @Volatile private var isScreenOn = true
  @Volatile private var isCharging = false

  // Accumulated unplugged durations, % drop, & charge drain (µAh)
  private var unpluggedScreenOnMs = 0L
  private var unpluggedScreenOffMs = 0L
  private var pluggedMs = 0L
  private var screenOnDrainUah = 0L
  private var screenOffDrainUah = 0L
  private var unpluggedLevelDropPct = 0

  // Per-thread & whole-process CPU tracking (jiffies snapshot -> accumulated ms split by screen)
  private val lastThreadJiffies = HashMap<Int, ThreadSample>()
  private var lastProcessJiffies = -1L
  private val screenOnThreadCpuMs = HashMap<String, Long>()
  private val screenOffThreadCpuMs = HashMap<String, Long>()
  private var totalCpuScreenOnMs = 0L
  private var totalCpuScreenOffMs = 0L
  private var shortLivedCpuScreenOnMs = 0L
  private var shortLivedCpuScreenOffMs = 0L

  // Network baseline (UID bytes via TrafficStats)
  private var startUidTxBytes = -1L
  private var startUidRxBytes = -1L

  // Audio & DSP counters
  private val audioAudibleUs = AtomicLong()
  private val audioKeepWarmIdleUs = AtomicLong()
  private val audioAudibleBursts = AtomicLong()
  private val audioIdleBursts = AtomicLong()
  private val audioDspWriteUs = AtomicLong()
  private val audioDspWrites = AtomicLong()
  @Volatile private var activeAudioBackend = "unknown"

  // TTS / Speech Output counters
  private val ttsSpeakRequests = AtomicLong()
  private val ttsSpeakChars = AtomicLong()
  private val ttsLiveRequests = AtomicLong()
  private val ttsCacheRequests = AtomicLong()
  private val ttsInterrupted = AtomicLong()
  private val ttsErrors = AtomicLong()
  private val ttsPcmBytes = AtomicLong()
  private val ttsPcmChunks = AtomicLong()
  @Volatile private var activeTtsEngine = "unknown"

  // NavigationPcmCache prefetch & waste counters
  private val pcmPrefetches = AtomicLong()
  private val pcmPrefetchHits = AtomicLong()
  private val pcmPrefetchCancelled = AtomicLong()
  private val pcmPrefetchExpired = AtomicLong()
  private val pcmPrefetchTimeouts = AtomicLong()
  private val pcmPrefetchSuperseded = AtomicLong()

  // Earcons & Haptics counters
  private val earconPlays = AtomicLong()
  private val hapticPlays = AtomicLong()
  private val hapticTotalMs = AtomicLong()

  // Sensor tracking
  private class SensorStats {
    val samples = AtomicLong()
    val activations = AtomicLong()
    @Volatile var active = false
    @Volatile var activeSinceElapsedMs = 0L
    var totalActiveMs = 0L
  }
  private val sensors = ConcurrentHashMap<String, SensorStats>()

  // WakeLock tracking
  private class WakeLockStats {
    val acquires = AtomicLong()
    @Volatile var held = false
    @Volatile var heldSinceElapsedMs = 0L
    var totalHeldMs = 0L
    var maxHoldMs = 0L
  }
  private val wakeLocks = ConcurrentHashMap<String, WakeLockStats>()

  // Accessibility events & background work
  private val eventsScreenOn = AtomicLong()
  private val eventsScreenOff = AtomicLong()
  private val screenOnEventsByPackage = ConcurrentHashMap<String, AtomicLong>()
  private val screenOffEventsByPackage = ConcurrentHashMap<String, AtomicLong>()
  private val orderBuildsScreenOn = AtomicLong()
  private val orderBuildsScreenOff = AtomicLong()
  private val orderBuildTotalMs = AtomicLong()
  private val windowPrefetches = AtomicLong()

  private const val MAX_TRACKED_PACKAGES = 64

  internal data class ThreadSample(val name: String, val jiffies: Long)

  private data class BatterySnapshot(
    val levelPct: Int,
    val chargeCounterUah: Long,
    val currentNowUa: Long,
    val tempTenthsC: Int,
    val charging: Boolean,
  )

  /** Flags passed from TalkBack/preferences for automated red-flag diagnostics. */
  data class DiagnosticFlags(
    val debugLogOn: Boolean = false,
    val performanceStatsOn: Boolean = false,
    val backTapEnabled: Boolean = false,
    val audioKeepWarmEnabled: Boolean = false,
    val keepScreenOnWhileSpeaking: Boolean = false,
    val prepareAdjacentSpeech: Boolean = false,
  )

  private val clkTck: Long by lazy {
    try {
      val v = Os.sysconf(OsConstants._SC_CLK_TCK)
      if (v > 0L) v else 100L
    } catch (_: Throwable) {
      100L
    }
  }

  /** Returns whether detailed battery profiling is currently enabled. */
  @JvmStatic fun isEnabled(): Boolean = enabled

  /**
   * Synchronizes the in-memory enabled flag from SharedPreferences without overwriting any existing
   * user choice. Defaults to `false` for new installs.
   */
  @JvmStatic
  fun syncEnabledFromPreferences(context: Context) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context.applicationContext)
    val shouldEnable = prefs.getBoolean(PREF_ENABLED_KEY, false)
    setEnabled(context, shouldEnable)
  }

  /** Enables or disables battery profiling. Turning on starts a fresh session if needed. */
  @JvmStatic
  fun setEnabled(context: Context, value: Boolean) {
    enabled = value
    if (value) {
      startIfNeeded(context)
    } else {
      initialized.set(false)
    }
  }

  /** Initializes a profiling session if enabled and not already started. */
  @JvmStatic
  fun startIfNeeded(context: Context) {
    if (!enabled) return
    if (initialized.compareAndSet(false, true)) {
      resetSession(context)
    }
  }

  /** Resets all battery, CPU, TTS, audio, haptic, network, sensor, wakelock, and event counters. */
  @JvmStatic
  fun resetSession(context: Context) {
    val app = context.applicationContext
    val bat = readBattery(app)
    val power = app.getSystemService(PowerManager::class.java)
    val screenInteractive = power?.isInteractive ?: true
    val nowWall = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val uid = Process.myUid()
    val txBytes = safeTrafficBytes { TrafficStats.getUidTxBytes(uid) }
    val rxBytes = safeTrafficBytes { TrafficStats.getUidRxBytes(uid) }

    synchronized(stateLock) {
      initialized.set(true)
      sessionStartWallMs = nowWall
      sessionStartElapsedMs = nowElapsed
      lastSegmentElapsedMs = nowElapsed
      startBatteryLevel = bat.levelPct
      lastBatteryLevel = bat.levelPct
      lastChargeCounterUah = bat.chargeCounterUah
      isScreenOn = screenInteractive
      isCharging = bat.charging

      unpluggedScreenOnMs = 0L
      unpluggedScreenOffMs = 0L
      pluggedMs = 0L
      screenOnDrainUah = 0L
      screenOffDrainUah = 0L
      unpluggedLevelDropPct = 0

      lastThreadJiffies.clear()
      lastThreadJiffies.putAll(readThreadJiffies())
      lastProcessJiffies = readProcessJiffies()
      screenOnThreadCpuMs.clear()
      screenOffThreadCpuMs.clear()
      totalCpuScreenOnMs = 0L
      totalCpuScreenOffMs = 0L
      shortLivedCpuScreenOnMs = 0L
      shortLivedCpuScreenOffMs = 0L

      startUidTxBytes = txBytes
      startUidRxBytes = rxBytes

      audioAudibleUs.set(0L)
      audioKeepWarmIdleUs.set(0L)
      audioAudibleBursts.set(0L)
      audioIdleBursts.set(0L)
      audioDspWriteUs.set(0L)
      audioDspWrites.set(0L)

      ttsSpeakRequests.set(0L)
      ttsSpeakChars.set(0L)
      ttsLiveRequests.set(0L)
      ttsCacheRequests.set(0L)
      ttsInterrupted.set(0L)
      ttsErrors.set(0L)
      ttsPcmBytes.set(0L)
      ttsPcmChunks.set(0L)

      pcmPrefetches.set(0L)
      pcmPrefetchHits.set(0L)
      pcmPrefetchCancelled.set(0L)
      pcmPrefetchExpired.set(0L)
      pcmPrefetchTimeouts.set(0L)
      pcmPrefetchSuperseded.set(0L)

      earconPlays.set(0L)
      hapticPlays.set(0L)
      hapticTotalMs.set(0L)

      for (stat in sensors.values) {
        stat.samples.set(0L)
        stat.activations.set(if (stat.active) 1L else 0L)
        stat.totalActiveMs = 0L
        if (stat.active) stat.activeSinceElapsedMs = nowElapsed
      }
      for (wl in wakeLocks.values) {
        wl.acquires.set(if (wl.held) 1L else 0L)
        wl.totalHeldMs = 0L
        wl.maxHoldMs = 0L
        if (wl.held) wl.heldSinceElapsedMs = nowElapsed
      }

      eventsScreenOn.set(0L)
      eventsScreenOff.set(0L)
      screenOnEventsByPackage.clear()
      screenOffEventsByPackage.clear()
      orderBuildsScreenOn.set(0L)
      orderBuildsScreenOff.set(0L)
      orderBuildTotalMs.set(0L)
      windowPrefetches.set(0L)
    }
  }

  /** Called on ACTION_SCREEN_ON / ACTION_SCREEN_OFF. Zero background polling needed. */
  @JvmStatic
  fun onScreenStateChanged(context: Context, screenOn: Boolean) {
    if (!enabled) return
    if (!initialized.get()) {
      startIfNeeded(context)
    }
    synchronized(stateLock) {
      flushSegmentLocked(context.applicationContext)
      isScreenOn = screenOn
    }
  }

  /** Called on ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED. */
  @JvmStatic
  fun onPowerStateChanged(context: Context, charging: Boolean) {
    if (!enabled) return
    if (!initialized.get()) {
      startIfNeeded(context)
    }
    synchronized(stateLock) {
      flushSegmentLocked(context.applicationContext)
      isCharging = charging
    }
  }

  /** Records one LowLatencyAudio burst (audible speech/sound vs silent keep-warm). */
  @JvmStatic
  fun onAudioBurstUs(audible: Boolean, durationUs: Long) {
    if (!enabled || durationUs <= 0L) return
    if (audible) {
      audioAudibleUs.addAndGet(durationUs)
      audioAudibleBursts.incrementAndGet()
    } else {
      audioKeepWarmIdleUs.addAndGet(durationUs)
      audioIdleBursts.incrementAndGet()
    }
  }

  /** Records one DSP/resample write duration in microseconds. */
  @JvmStatic
  fun onAudioDspWriteUs(durationUs: Long) {
    if (!enabled || durationUs <= 0L) return
    audioDspWriteUs.addAndGet(durationUs)
    audioDspWrites.incrementAndGet()
  }

  /** Records the currently active low-latency audio backend label. */
  @JvmStatic
  fun onAudioBackendSelected(backendLabel: String) {
    if (!enabled || backendLabel.isEmpty()) return
    activeAudioBackend = backendLabel
  }

  /** Records an utterance dispatched to TTS (live engine synthesis vs local cache). */
  @JvmStatic
  fun onTtsSpeakRequest(enginePackage: String?, charCount: Int, fromCache: Boolean) {
    if (!enabled) return
    if (!enginePackage.isNullOrEmpty()) {
      activeTtsEngine = enginePackage
    }
    ttsSpeakRequests.incrementAndGet()
    if (charCount > 0) ttsSpeakChars.addAndGet(charCount.toLong())
    if (fromCache) ttsCacheRequests.incrementAndGet() else ttsLiveRequests.incrementAndGet()
  }

  /** Records PCM audio bytes received over IPC from the TTS engine. */
  @JvmStatic
  fun onTtsAudioChunk(byteCount: Int) {
    if (!enabled || byteCount <= 0) return
    ttsPcmBytes.addAndGet(byteCount.toLong())
    ttsPcmChunks.incrementAndGet()
  }

  /** Records an utterance interrupted mid-speech (e.g., user swiped quickly). */
  @JvmStatic
  fun onTtsInterrupted() {
    if (!enabled) return
    ttsInterrupted.incrementAndGet()
  }

  /** Records a TTS synthesis/playback failure. */
  @JvmStatic
  fun onTtsError() {
    if (!enabled) return
    ttsErrors.incrementAndGet()
  }

  /** Records an earcon / auditory feedback sound play. */
  @JvmStatic
  fun onEarconPlayed() {
    if (!enabled) return
    earconPlays.incrementAndGet()
  }

  /** Records a haptic vibration play and its estimated active motor duration in milliseconds. */
  @JvmStatic
  fun onHapticPlayed(durationMs: Long) {
    if (!enabled) return
    hapticPlays.incrementAndGet()
    if (durationMs > 0L) hapticTotalMs.addAndGet(durationMs)
  }

  /** Records sensor listener registration / unregistration. */
  @JvmStatic
  fun onSensorStateChanged(sensorName: String, active: Boolean) {
    if (!enabled) return
    val stat = sensors.getOrPut(sensorName) { SensorStats() }
    val now = SystemClock.elapsedRealtime()
    synchronized(stat) {
      if (active) {
        if (!stat.active) {
          stat.active = true
          stat.activeSinceElapsedMs = now
          stat.activations.incrementAndGet()
        }
      } else {
        if (stat.active) {
          stat.totalActiveMs += max(0L, now - stat.activeSinceElapsedMs)
          stat.active = false
        }
      }
    }
  }

  /** Records a single sensor callback sample (used to compute real Hz). */
  @JvmStatic
  fun onSensorSample(sensorName: String) {
    if (!enabled) return
    sensors.getOrPut(sensorName) { SensorStats() }.samples.incrementAndGet()
  }

  /** Records WakeLock acquisition. */
  @JvmStatic
  fun onWakeLockAcquire(tag: String) {
    if (!enabled) return
    val wl = wakeLocks.getOrPut(tag) { WakeLockStats() }
    val now = SystemClock.elapsedRealtime()
    synchronized(wl) {
      if (!wl.held) {
        wl.held = true
        wl.heldSinceElapsedMs = now
        wl.acquires.incrementAndGet()
      }
    }
  }

  /** Records WakeLock release. */
  @JvmStatic
  fun onWakeLockRelease(tag: String) {
    if (!enabled) return
    val wl = wakeLocks[tag] ?: return
    val now = SystemClock.elapsedRealtime()
    synchronized(wl) {
      if (wl.held) {
        val heldMs = max(0L, now - wl.heldSinceElapsedMs)
        wl.totalHeldMs += heldMs
        if (heldMs > wl.maxHoldMs) wl.maxHoldMs = heldMs
        wl.held = false
      }
    }
  }

  /** Records an incoming AccessibilityEvent and tracks per-package activity (Screen-On & Off). */
  @JvmStatic
  fun onAccessibilityEvent(packageName: CharSequence?, eventType: Int) {
    if (!enabled) return
    val pkg = packageName?.toString()?.takeIf { it.isNotEmpty() } ?: "system"
    val targetMap =
      if (isScreenOn) {
        eventsScreenOn.incrementAndGet()
        screenOnEventsByPackage
      } else {
        eventsScreenOff.incrementAndGet()
        screenOffEventsByPackage
      }
    val counter = targetMap[pkg]
    if (counter != null) {
      counter.incrementAndGet()
    } else if (targetMap.size < MAX_TRACKED_PACKAGES) {
      targetMap.putIfAbsent(pkg, AtomicLong(1L))?.incrementAndGet()
    }
  }

  /** Records a TraversalTreeCache order build. */
  @JvmStatic
  fun onOrderBuild(durationMs: Long) {
    if (!enabled) return
    if (isScreenOn) orderBuildsScreenOn.incrementAndGet() else orderBuildsScreenOff.incrementAndGet()
    if (durationMs > 0L) orderBuildTotalMs.addAndGet(durationMs)
  }

  /** Records a background WindowNodePrefetcher run. */
  @JvmStatic
  fun onWindowPrefetch() {
    if (!enabled) return
    windowPrefetches.incrementAndGet()
  }

  /** Records a background NavigationPcmCache synthesis request. */
  @JvmStatic
  fun onPcmPrefetch() {
    if (!enabled) return
    pcmPrefetches.incrementAndGet()
  }

  /** Records a NavigationPcmCache hit (prefetched speech actually used). */
  @JvmStatic
  fun onPcmPrefetchHit() {
    if (!enabled) return
    pcmPrefetchHits.incrementAndGet()
  }

  /** Records a wasted NavigationPcmCache effort (cancelled, expired, timeout, or superseded). */
  @JvmStatic
  fun onPcmPrefetchWasted(reason: String) {
    if (!enabled) return
    when (reason) {
      "cancelled" -> pcmPrefetchCancelled.incrementAndGet()
      "expired" -> pcmPrefetchExpired.incrementAndGet()
      "timeout" -> pcmPrefetchTimeouts.incrementAndGet()
      "superseded" -> pcmPrefetchSuperseded.incrementAndGet()
    }
  }

  /** One-line summary for the Developer Settings preference subtitle. */
  @JvmStatic
  fun buildShortSubtitle(context: Context): String {
    if (!enabled) {
      return "Đang tắt (bật công tắc ở trên để bắt đầu đo)"
    }
    if (!initialized.get()) {
      startIfNeeded(context)
    }
    val app = context.applicationContext
    val bat = readBattery(app)
    val unpluggedMs: Long
    val drainUah: Long
    val levelDrop: Int
    synchronized(stateLock) {
      flushSegmentLocked(app)
      unpluggedMs = unpluggedScreenOnMs + unpluggedScreenOffMs
      drainUah = screenOnDrainUah + screenOffDrainUah
      levelDrop = unpluggedLevelDropPct
    }
    val drainMah = drainUah / 1000.0
    return String.format(
      Locale.ROOT,
      "Đã đo %s (rút sạc) · Pin: %d%% (-%d%%, -%.1f mAh)",
      formatDuration(unpluggedMs),
      bat.levelPct,
      levelDrop,
      drainMah,
    )
  }

  /** Compact section embedded into `meridian-diagnostics-*.txt` when profiling is enabled. */
  @JvmStatic
  fun buildCompactSummary(context: Context, flags: DiagnosticFlags = DiagnosticFlags()): String {
    if (!enabled) {
      return "== Đo chi tiết mức sử dụng pin Meridian ==\n" +
        "Trạng thái: Đang tắt (bật trong Cài đặt nhà phát triển → Quản lý mức sử dụng pin)\n"
    }
    return buildDetailedReport(context, flags, maxThreads = 8, maxPackages = 5)
  }

  /** Full, human-readable detailed battery breakdown report for in-app viewing and file export. */
  @JvmStatic
  fun buildDetailedReport(
    context: Context,
    flags: DiagnosticFlags = DiagnosticFlags(),
    maxThreads: Int = 12,
    maxPackages: Int = 8,
  ): String {
    if (!enabled) {
      return "== Đo chi tiết mức sử dụng pin Meridian ==\n" +
        "Bộ đo đang TẮT. Hãy bật công tắc 'Bật đo chi tiết mức sử dụng pin' trong Cài đặt nhà phát triển → Quản lý mức sử dụng pin.\n"
    }
    if (!initialized.get()) {
      startIfNeeded(context)
    }
    val app = context.applicationContext
    val bat = readBattery(app)
    val nowElapsed = SystemClock.elapsedRealtime()
    val nowWall = System.currentTimeMillis()
    val uid = Process.myUid()
    val curTxBytes = safeTrafficBytes { TrafficStats.getUidTxBytes(uid) }
    val curRxBytes = safeTrafficBytes { TrafficStats.getUidRxBytes(uid) }

    val totalElapsedMs: Long
    val onMs: Long
    val offMs: Long
    val chgMs: Long
    val onDrainUah: Long
    val offDrainUah: Long
    val levelDrop: Int
    val cpuOnMs: Long
    val cpuOffMs: Long
    val shortCpuOnMs: Long
    val shortCpuOffMs: Long
    val threadRows: List<ThreadCpuRow>
    val startLevel: Int
    val startWall: Long
    val netTxBytes: Long
    val netRxBytes: Long

    synchronized(stateLock) {
      flushSegmentLocked(app)
      totalElapsedMs = max(1L, nowElapsed - sessionStartElapsedMs)
      onMs = unpluggedScreenOnMs
      offMs = unpluggedScreenOffMs
      chgMs = pluggedMs
      onDrainUah = screenOnDrainUah
      offDrainUah = screenOffDrainUah
      levelDrop = unpluggedLevelDropPct
      cpuOnMs = totalCpuScreenOnMs
      cpuOffMs = totalCpuScreenOffMs
      shortCpuOnMs = shortLivedCpuScreenOnMs
      shortCpuOffMs = shortLivedCpuScreenOffMs
      startLevel = startBatteryLevel
      startWall = sessionStartWallMs
      netTxBytes = if (startUidTxBytes >= 0L && curTxBytes >= startUidTxBytes) curTxBytes - startUidTxBytes else 0L
      netRxBytes = if (startUidRxBytes >= 0L && curRxBytes >= startUidRxBytes) curRxBytes - startUidRxBytes else 0L

      val allThreadNames = (screenOnThreadCpuMs.keys + screenOffThreadCpuMs.keys).toSet()
      val mutableRows =
        allThreadNames
          .map { name ->
            val onCpu = screenOnThreadCpuMs[name] ?: 0L
            val offCpu = screenOffThreadCpuMs[name] ?: 0L
            ThreadCpuRow(name, onCpu, offCpu, onCpu + offCpu)
          }
          .filter { it.totalMs > 0L }
          .toMutableList()
      val shortTotal = shortCpuOnMs + shortCpuOffMs
      if (shortTotal > 0L) {
        mutableRows.add(
          ThreadCpuRow("(luồng ngắn hạn đã thoát)", shortCpuOnMs, shortCpuOffMs, shortTotal)
        )
      }
      threadRows = mutableRows.sortedByDescending { it.totalMs }
    }

    val unpluggedMs = onMs + offMs
    val totalDrainUah = onDrainUah + offDrainUah
    val totalDrainMah = totalDrainUah / 1000.0
    val onDrainMah = onDrainUah / 1000.0
    val offDrainMah = offDrainUah / 1000.0
    val unpluggedHours = unpluggedMs / 3_600_000.0
    val mahPerHour = if (unpluggedHours > 0.005) totalDrainMah / unpluggedHours else 0.0
    val pctPerHour = if (unpluggedHours > 0.005 && levelDrop > 0) levelDrop / unpluggedHours else 0.0

    val totalCpuMs = cpuOnMs + cpuOffMs
    val mainCpuMs = threadRows.firstOrNull { isMainThreadName(it.name) }?.totalMs ?: 0L
    val bgCpuMs = max(0L, totalCpuMs - mainCpuMs)
    val cpuOnPct = if (onMs > 0L) cpuOnMs * 100.0 / onMs else 0.0
    val cpuOffPct = if (offMs > 0L) cpuOffMs * 100.0 / offMs else 0.0

    val audibleMs = audioAudibleUs.get() / 1000L
    val keepWarmMs = audioKeepWarmIdleUs.get() / 1000L
    val totalAudioMs = audibleMs + keepWarmMs
    val dspWriteMs = audioDspWriteUs.get() / 1000L
    val dspWrites = audioDspWrites.get()

    val speakReqs = ttsSpeakRequests.get()
    val speakChars = ttsSpeakChars.get()
    val liveReqs = ttsLiveRequests.get()
    val cacheReqs = ttsCacheRequests.get()
    val interruptedReqs = ttsInterrupted.get()
    val ttsErrCount = ttsErrors.get()
    val pcmBytes = ttsPcmBytes.get()
    val pcmChunks = ttsPcmChunks.get()
    val pcmQueued = pcmPrefetches.get()
    val pcmHits = pcmPrefetchHits.get()
    val pcmCancelled = pcmPrefetchCancelled.get()
    val pcmExpired = pcmPrefetchExpired.get()
    val pcmTimeout = pcmPrefetchTimeouts.get()
    val pcmSuperseded = pcmPrefetchSuperseded.get()
    val pcmWastedTotal = pcmCancelled + pcmExpired + pcmTimeout + pcmSuperseded

    val earcons = earconPlays.get()
    val haptics = hapticPlays.get()
    val hapticMs = hapticTotalMs.get()

    // Snapshot sensors
    data class SensorRow(
      val name: String,
      val activeMs: Long,
      val activations: Long,
      val samples: Long,
      val activeNow: Boolean,
    )
    val sensorRows =
      sensors.entries
        .map { (name, st) ->
          synchronized(st) {
            val activeMs =
              st.totalActiveMs + if (st.active) max(0L, nowElapsed - st.activeSinceElapsedMs) else 0L
            SensorRow(name, activeMs, st.activations.get(), st.samples.get(), st.active)
          }
        }
        .sortedByDescending { it.activeMs }
    val totalSensorMs = sensorRows.sumOf { it.activeMs }

    // Snapshot wakelocks
    data class WakeLockRow(
      val tag: String,
      val acquires: Long,
      val totalHeldMs: Long,
      val maxHoldMs: Long,
      val heldNow: Boolean,
    )
    val wakeLockRows =
      wakeLocks.entries
        .map { (tag, wl) ->
          synchronized(wl) {
            val currentHold = if (wl.held) max(0L, nowElapsed - wl.heldSinceElapsedMs) else 0L
            WakeLockRow(
              tag = tag,
              acquires = wl.acquires.get(),
              totalHeldMs = wl.totalHeldMs + currentHold,
              maxHoldMs = max(wl.maxHoldMs, currentHold),
              heldNow = wl.held,
            )
          }
        }
        .sortedByDescending { it.totalHeldMs }
    val totalWakeLockMs = wakeLockRows.sumOf { it.totalHeldMs }

    // Estimated Meridian-only mAh model (mW * sec / (3600 * 3.85V) -> mAh)
    val estCpuMainMah = mwMsToMah(260.0, mainCpuMs)
    val estCpuBgMah = mwMsToMah(200.0, bgCpuMs)
    val estAudioAudibleMah = mwMsToMah(38.0, audibleMs)
    val estAudioKeepWarmMah = mwMsToMah(22.0, keepWarmMs)
    // External TTS engine load proxy: ~18 ms CPU per live synthesis + ~1.8 ms per 100 chars
    val estTtsActiveMs = liveReqs * 18L + (speakChars * 18L) / 1000L + pcmQueued * 22L
    val estTtsExternalMah = mwMsToMah(220.0, estTtsActiveMs)
    val estSensorsMah = mwMsToMah(16.0, totalSensorMs)
    val estWakeLocksMah = mwMsToMah(25.0, totalWakeLockMs)
    val estHapticsMah = mwMsToMah(140.0, hapticMs)
    val estDebugLogMah = if (flags.debugLogOn) mwMsToMah(75.0, totalCpuMs) else 0.0
    val estMeridianTotalMah =
      estCpuMainMah +
        estCpuBgMah +
        estAudioAudibleMah +
        estAudioKeepWarmMah +
        estTtsExternalMah +
        estSensorsMah +
        estWakeLocksMah +
        estHapticsMah +
        estDebugLogMah
    val totalScore = max(0.0001, estMeridianTotalMah)

    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    val sb = StringBuilder(3072)

    sb.append("== Đo chi tiết mức sử dụng pin Meridian ==\n")
    sb.append("Bắt đầu đo: ").append(fmt.format(Date(startWall)))
      .append(" → Hiện tại: ").append(fmt.format(Date(nowWall))).append('\n')
    sb.append("Tổng thời gian đo: ").append(formatDuration(totalElapsedMs))
      .append(" (Rút sạc: ").append(formatDuration(unpluggedMs))
      .append(" · Đang sạc: ").append(formatDuration(chgMs)).append(")\n")
    sb.append("  • Rút sạc khi BẬT màn hình: ").append(formatDuration(onMs))
      .append(" | khi TẮT màn hình: ").append(formatDuration(offMs)).append('\n')
    sb.append(
      String.format(
        Locale.ROOT,
        "Pin máy: %d%% → %d%% (tụt lúc rút sạc: %d%%) · Trạng thái: %s · Nhiệt độ: %.1f°C\n",
        startLevel,
        bat.levelPct,
        levelDrop,
        if (bat.charging) "Đang sạc" else "Đang dùng pin",
        bat.tempTenthsC / 10.0,
      )
    )
    if (bat.chargeCounterUah > 0L) {
      sb.append(
        String.format(
          Locale.ROOT,
          "Tổng máy tụt thực (CHARGE_COUNTER): -%.1f mAh (Bật màn: -%.1f mAh | Tắt màn: -%.1f mAh)\n",
          totalDrainMah,
          onDrainMah,
          offDrainMah,
        )
      )
    }
    val shortWindowNote = if (unpluggedMs in 1L until 600_000L) " [đo <10p, xem mAh/giờ chuẩn hơn]" else ""
    sb.append(
      String.format(
        Locale.ROOT,
        "Tốc độ xả toàn máy: %.1f %%/giờ (%.0f mAh/giờ)%s · Dòng tức thời: %.0f mA\n",
        pctPerHour,
        mahPerHour,
        shortWindowNote,
        abs(bat.currentNowUa) / 1000.0,
      )
    )
    val shareOfDeviceStr =
      if (totalDrainMah > 0.5) {
        val pctOfDev = (estMeridianTotalMah * 100.0 / totalDrainMah).coerceIn(0.0, 100.0)
        String.format(Locale.ROOT, " (~%.1f%% tổng tụt máy)", pctOfDev)
      } else {
        ""
      }
    sb.append(
      String.format(
        Locale.ROOT,
        "Ước tính riêng Meridian + tải TTS đẩy đi: ~%.2f mAh%s\n",
        estMeridianTotalMah,
        shareOfDeviceStr,
      )
    )
    sb.append(
      String.format(
        Locale.ROOT,
        "Tổng CPU tiến trình Meridian (/proc/self/stat): %s (Bật màn: %s [%.2f%% tải] | Tắt màn: %s [%.2f%% tải])\n",
        formatDuration(totalCpuMs),
        formatDuration(cpuOnMs),
        cpuOnPct,
        formatDuration(cpuOffMs),
        cpuOffPct,
      )
    )

    sb.append("\n-- Phân bổ tỷ trọng ước tính nội bộ Meridian (100%) --\n")
    appendShare(sb, "CPU luồng chính (main)", estCpuMainMah, totalScore, formatDuration(mainCpuMs))
    appendShare(sb, "CPU luồng nền (prefetch/order/audio/AI)", estCpuBgMah, totalScore, formatDuration(bgCpuMs))
    appendShare(sb, "Đầu ra TTS (tải đẩy sang engine ngoài)", estTtsExternalMah, totalScore, "$speakReqs câu · $speakChars ký tự")
    appendShare(sb, "Âm thanh loa (phát tiếng thật)", estAudioAudibleMah, totalScore, formatDuration(audibleMs))
    appendShare(sb, "Âm thanh loa (giữ ấm im lặng Keep-Warm)", estAudioKeepWarmMah, totalScore, formatDuration(keepWarmMs))
    appendShare(sb, "Rung phản hồi (Haptics)", estHapticsMah, totalScore, "$haptics lần · ${formatDuration(hapticMs)}")
    appendShare(sb, "Cảm biến (BackTap / Lắc / Tiệm cận)", estSensorsMah, totalScore, formatDuration(totalSensorMs))
    appendShare(sb, "WakeLock / Giữ sáng màn hình", estWakeLocksMah, totalScore, formatDuration(totalWakeLockMs))
    if (flags.debugLogOn) {
      appendShare(sb, "Ghi nhật ký gỡ lỗi (Debug log Verbose)", estDebugLogMah, totalScore, "ĐANG BẬT")
    }

    sb.append("\n-- Chi tiết Đầu ra giọng nói (TTS & Audio) --\n")
    sb.append("  • TTS Engine: ").append(activeTtsEngine)
      .append(" | Đường phát: ").append(activeAudioBackend).append('\n')
    val avgChars = if (speakReqs > 0L) speakChars.toDouble() / speakReqs else 0.0
    sb.append(
      String.format(
        Locale.ROOT,
        "  • Yêu cầu đọc: %d câu (trực tiếp: %d | từ cache: %d) · Tổng: %d ký tự (TB %.1f ký tự/câu)\n",
        speakReqs,
        liveReqs,
        cacheReqs,
        speakChars,
        avgChars,
      )
    )
    val interruptPct = if (speakReqs > 0L) interruptedReqs * 100.0 / speakReqs else 0.0
    sb.append(
      String.format(
        Locale.ROOT,
        "  • Bị ngắt ngang sớm: %d lần (%.1f%% số câu) | Lỗi TTS: %d\n",
        interruptedReqs,
        interruptPct,
        ttsErrCount,
      )
    )
    val pcmMb = pcmBytes / (1024.0 * 1024.0)
    val avgDspMs = if (dspWrites > 0L) dspWriteMs.toDouble() / dspWrites else 0.0
    sb.append(
      String.format(
        Locale.ROOT,
        "  • PCM truyền qua IPC: %.2f MB (%d chunks) | Xử lý DSP/Resample: %s (%d lượt, TB %.2f ms)\n",
        pcmMb,
        pcmChunks,
        formatDuration(dspWriteMs),
        dspWrites,
        avgDspMs,
      )
    )
    val pcmWasteDenom = max(1L, pcmHits + pcmWastedTotal)
    val pcmWastePct = if (pcmQueued > 0L) pcmWastedTotal * 100.0 / pcmWasteDenom else 0.0
    sb.append(
      String.format(
        Locale.ROOT,
        "  • Đọc trước nền (NavigationPcmCache): gọi=%d | dùng được=%d | lãng phí=%d (%.1f%% — hủy:%d, hết hạn:%d, timeout:%d, đè:%d)\n",
        pcmQueued,
        pcmHits,
        pcmWastedTotal,
        pcmWastePct,
        pcmCancelled,
        pcmExpired,
        pcmTimeout,
        pcmSuperseded,
      )
    )
    val keepWarmPctOfAudio = if (totalAudioMs > 0L) keepWarmMs * 100.0 / totalAudioMs else 0.0
    sb.append(
      String.format(
        Locale.ROOT,
        "  • LowLatencyAudio: tiếng thật %s (%d bursts) | giữ ấm im lặng %s (%d bursts, %.1f%% tổng audio)\n",
        formatDuration(audibleMs),
        audioAudibleBursts.get(),
        formatDuration(keepWarmMs),
        audioIdleBursts.get(),
        keepWarmPctOfAudio,
      )
    )

    sb.append("\n-- Chi tiết Rung, Âm thanh phản hồi & Mạng --\n")
    sb.append(
      String.format(
        Locale.ROOT,
        "  • Rung (Haptics): %d lần (tổng %s) | Âm thanh phản hồi (Earcon): %d lần\n",
        haptics,
        formatDuration(hapticMs),
        earcons,
      )
    )
    sb.append(
      String.format(
        Locale.ROOT,
        "  • Lưu lượng mạng riêng Meridian: Gửi %s | Nhận %s\n",
        formatBytes(netTxBytes),
        formatBytes(netRxBytes),
      )
    )

    sb.append("\n-- Top ").append(maxThreads).append(" luồng tiêu tốn CPU nhiều nhất --\n")
    if (threadRows.isEmpty()) {
      sb.append("  (Chưa có mẫu CPU đáng kể — hãy dùng máy vài phút rồi xem lại)\n")
    } else {
      val denom = max(1L, totalCpuMs)
      for (row in threadRows.take(maxThreads)) {
        val pct = row.totalMs * 100.0 / denom
        sb.append(
          String.format(
            Locale.ROOT,
            "  • %-26s %6d ms (%4.1f%%) | Bật màn: %5d ms | Tắt màn: %5d ms\n",
            row.name.take(26),
            row.totalMs,
            pct,
            row.screenOnMs,
            row.screenOffMs,
          )
        )
      }
    }

    sb.append("\n-- Chi tiết Cảm biến --\n")
    if (sensorRows.isEmpty()) {
      sb.append("  • Không có cảm biến nào được kích hoạt trong phiên đo.\n")
    } else {
      for (sr in sensorRows) {
        val activeSec = sr.activeMs / 1000.0
        val hz = if (activeSec > 0.5) sr.samples / activeSec else 0.0
        val pctUnplugged = if (unpluggedMs > 0L) sr.activeMs * 100.0 / unpluggedMs else 0.0
        sb.append(
          String.format(
            Locale.ROOT,
            "  • %s: bật %s (%.1f%% thời gian rút sạc), %d lần bật, %d mẫu (%.1f Hz)%s\n",
            sr.name,
            formatDuration(sr.activeMs),
            pctUnplugged,
            sr.activations,
            sr.samples,
            hz,
            if (sr.activeNow) " [ĐANG BẬT]" else "",
          )
        )
      }
    }

    sb.append("\n-- Chi tiết WakeLock --\n")
    if (wakeLockRows.isEmpty()) {
      sb.append("  • Không ghi nhận WakeLock nào trong phiên đo.\n")
    } else {
      for (wl in wakeLockRows) {
        sb.append(
          String.format(
            Locale.ROOT,
            "  • %s: %d lần giữ, tổng %s, lâu nhất %s%s\n",
            wl.tag,
            wl.acquires,
            formatDuration(wl.totalHeldMs),
            formatDuration(wl.maxHoldMs),
            if (wl.heldNow) " [ĐANG GIỮ]" else "",
          )
        )
      }
    }

    sb.append("\n-- Sự kiện trợ năng & Tác vụ nền --\n")
    sb.append(
      String.format(
        Locale.ROOT,
        "  • AccessibilityEvent: Bật màn = %d | Tắt màn = %d\n",
        eventsScreenOn.get(),
        eventsScreenOff.get(),
      )
    )
    sb.append(
      String.format(
        Locale.ROOT,
        "  • Dựng cây điều hướng (Order build): Bật màn = %d | Tắt màn = %d (tổng %d ms)\n",
        orderBuildsScreenOn.get(),
        orderBuildsScreenOff.get(),
        orderBuildTotalMs.get(),
      )
    )
    sb.append(
      String.format(
        Locale.ROOT,
        "  • WindowNodePrefetcher: %d lượt | NavigationPcmCache prefetch: %d lượt\n",
        windowPrefetches.get(),
        pcmQueued,
      )
    )
    val topOnPkgs =
      screenOnEventsByPackage.entries
        .map { it.key to it.value.get() }
        .filter { it.second > 0L }
        .sortedByDescending { it.second }
        .take(maxPackages)
    if (topOnPkgs.isNotEmpty()) {
      sb.append("  • Top ứng dụng xả sự kiện lúc BẬT màn hình:\n")
      for ((pkg, count) in topOnPkgs) {
        sb.append("      - ").append(pkg).append(": ").append(count).append(" sự kiện\n")
      }
    }
    val topOffPkgs =
      screenOffEventsByPackage.entries
        .map { it.key to it.value.get() }
        .filter { it.second > 0L }
        .sortedByDescending { it.second }
        .take(maxPackages)
    if (topOffPkgs.isNotEmpty()) {
      sb.append("  • Top ứng dụng xả sự kiện lúc MÀN HÌNH TẮT:\n")
      for ((pkg, count) in topOffPkgs) {
        sb.append("      - ").append(pkg).append(": ").append(count).append(" sự kiện\n")
      }
    }

    // Automatic red-flag alerts
    val alerts = ArrayList<String>()
    if (flags.debugLogOn) {
      alerts.add("[CẢNH BÁO] Ghi nhật ký gỡ lỗi (Debug log) đang BẬT — ghi logcat liên tục tốn CPU và pin.")
    }
    if (flags.performanceStatsOn) {
      alerts.add("[CẢNH BÁO] In thống kê hiệu suất (pref_performance_stats) đang BẬT — tăng tải luồng chính.")
    }
    if (flags.backTapEnabled) {
      val backTapRow = sensorRows.firstOrNull { it.name.startsWith("BackTap") }
      val backTapMs = backTapRow?.activeMs ?: 0L
      if (backTapMs > 0L || backTapRow?.activeNow == true) {
        alerts.add(
          "[CẢNH BÁO] Gõ mặt lưng (BackTap) đang hoạt động (${formatDuration(backTapMs)}) — gia tốc kế chạy liên tục là nguồn hao pin lớn."
        )
      } else {
        alerts.add(
          "[THÔNG TIN] Gõ mặt lưng bật trong cài đặt nhưng cảm biến chưa kích hoạt (0 ms — không hao pin)."
        )
      }
    }
    if (flags.audioKeepWarmEnabled && keepWarmMs > 30_000L && keepWarmPctOfAudio > 35.0) {
      alerts.add(
        String.format(
          Locale.ROOT,
          "[CẢNH BÁO] Audio Keep-Warm giữ luồng phát im lặng %s (%.0f%% tổng thời gian audio) — ngăn Audio DSP ngủ.",
          formatDuration(keepWarmMs),
          keepWarmPctOfAudio,
        )
      )
    }
    if (pcmQueued >= 20L && pcmWastePct >= 50.0) {
      alerts.add(
        String.format(
          Locale.ROOT,
          "[CẢNH BÁO] Đọc trước PCM (NavigationPcmCache) lãng phí %.0f%% (%d/%d lượt bị hủy/hết hạn/timeout) — tăng tải pin Google TTS.",
          pcmWastePct,
          pcmWastedTotal,
          pcmQueued,
        )
      )
    }
    if (flags.keepScreenOnWhileSpeaking) {
      alerts.add("[LƯU Ý] Giữ màn hình sáng khi đang đọc đang BẬT — có thể giữ màn hình và WakeLock lâu hơn khi đọc văn bản dài.")
    }
    if (offMs > 60_000L && cpuOffPct > 2.0) {
      alerts.add(
        String.format(
          Locale.ROOT,
          "[CẢNH BÁO] Tải CPU khi MÀN HÌNH TẮT cao bất thường (%.2f%%, %s) — kiểm tra Top luồng và Top ứng dụng xả sự kiện lúc tắt màn hình ở trên.",
          cpuOffPct,
          formatDuration(cpuOffMs),
        )
      )
    }
    if (eventsScreenOff.get() > 300L) {
      alerts.add(
        "[CẢNH BÁO] Nhận ${eventsScreenOff.get()} AccessibilityEvent khi màn hình tắt — một ứng dụng nền đang đánh thức Meridian liên tục."
      )
    }
    for (wl in wakeLockRows) {
      if (wl.heldNow && wl.maxHoldMs > 60_000L) {
        alerts.add("[CẢNH BÁO] WakeLock '${wl.tag}' đang bị giữ liên tục (tối đa ${formatDuration(wl.maxHoldMs)}) — nghi ngờ rò rỉ WakeLock.")
      }
    }

    sb.append("\n-- Chẩn đoán nhanh & Gợi ý --\n")
    if (alerts.isEmpty()) {
      sb.append("  • Chưa phát hiện bất thường rõ rệt trong phiên đo này.\n")
    } else {
      for (a in alerts) {
        sb.append("  • ").append(a).append('\n')
      }
    }

    return sb.toString()
  }

  private fun mwMsToMah(mw: Double, durationMs: Long): Double {
    if (durationMs <= 0L) return 0.0
    val seconds = durationMs / 1000.0
    return (mw * seconds) / (3600.0 * 3.85)
  }

  private fun appendShare(
    sb: StringBuilder,
    label: String,
    score: Double,
    totalScore: Double,
    detail: String,
  ) {
    val pct = (score * 100.0 / totalScore).roundToInt()
    sb.append(String.format(Locale.ROOT, "  • %-40s %3d%% (%s)\n", label, pct, detail))
  }

  private data class ThreadCpuRow(
    val name: String,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val totalMs: Long,
  )

  private fun isMainThreadName(name: String): Boolean {
    return name == "main" ||
      name.startsWith("io.github.aaron") ||
      name.startsWith("meridian") ||
      name.startsWith("b.aaron_gh")
  }

  private fun flushSegmentLocked(context: Context) {
    val nowElapsed = SystemClock.elapsedRealtime()
    val dt = max(0L, nowElapsed - lastSegmentElapsedMs)
    lastSegmentElapsedMs = nowElapsed

    val bat = readBattery(context)
    if (isCharging) {
      pluggedMs += dt
    } else if (isScreenOn) {
      unpluggedScreenOnMs += dt
    } else {
      unpluggedScreenOffMs += dt
    }

    if (!isCharging && lastBatteryLevel >= 0 && bat.levelPct >= 0 && bat.levelPct < lastBatteryLevel) {
      unpluggedLevelDropPct += (lastBatteryLevel - bat.levelPct)
    }
    if (bat.levelPct >= 0) {
      lastBatteryLevel = bat.levelPct
    }

    if (lastChargeCounterUah != Long.MIN_VALUE && bat.chargeCounterUah > 0L) {
      val deltaDrain = lastChargeCounterUah - bat.chargeCounterUah
      if (!isCharging && deltaDrain > 0L) {
        if (isScreenOn) screenOnDrainUah += deltaDrain else screenOffDrainUah += deltaDrain
      }
    }
    if (bat.chargeCounterUah > 0L) {
      lastChargeCounterUah = bat.chargeCounterUah
    }

    // Flush per-thread CPU deltas from /proc/self/task/*/stat and cross-check /proc/self/stat
    val currentThreads = readThreadJiffies()
    val currentProcJiffies = readProcessJiffies()
    val targetMap = if (isScreenOn) screenOnThreadCpuMs else screenOffThreadCpuMs
    var liveDeltaMsTotal = 0L
    for ((tid, sample) in currentThreads) {
      val prev = lastThreadJiffies[tid]
      val deltaJiffies =
        if (prev != null && prev.name == sample.name) {
          max(0L, sample.jiffies - prev.jiffies)
        } else if (prev == null) {
          // Newly spawned thread during this segment: credit its initial CPU time.
          max(0L, sample.jiffies)
        } else {
          0L
        }
      if (deltaJiffies > 0L) {
        val deltaMs = deltaJiffies * 1000L / clkTck
        val groupName = normalizeThreadName(sample.name)
        targetMap[groupName] = (targetMap[groupName] ?: 0L) + deltaMs
        liveDeltaMsTotal += deltaMs
      }
    }

    var procDeltaMs = liveDeltaMsTotal
    if (lastProcessJiffies >= 0L && currentProcJiffies >= lastProcessJiffies) {
      val measuredProcMs = (currentProcJiffies - lastProcessJiffies) * 1000L / clkTck
      if (measuredProcMs > liveDeltaMsTotal) {
        val shortLivedMs = measuredProcMs - liveDeltaMsTotal
        if (isScreenOn) shortLivedCpuScreenOnMs += shortLivedMs
        else shortLivedCpuScreenOffMs += shortLivedMs
        procDeltaMs = measuredProcMs
      }
    }
    if (currentProcJiffies >= 0L) {
      lastProcessJiffies = currentProcJiffies
    }

    if (isScreenOn) totalCpuScreenOnMs += procDeltaMs else totalCpuScreenOffMs += procDeltaMs
    lastThreadJiffies.clear()
    lastThreadJiffies.putAll(currentThreads)
  }

  internal fun normalizeThreadName(raw: String): String {
    val name = raw.trim()
    return when {
      name.startsWith("BacktalkPrefetch") -> "BacktalkPrefetch (pool)"
      name.startsWith("binder:", ignoreCase = true) -> "Binder (IPC pool)"
      name.startsWith("OkHttp", ignoreCase = true) -> "OkHttp (network)"
      name.startsWith("pool-") -> "Executor pool"
      else -> name
    }
  }

  private fun readBattery(context: Context): BatterySnapshot {
    val bm = context.getSystemService(BatteryManager::class.java)
    val sticky =
      try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      } catch (_: Throwable) {
        null
      }
    val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val pct =
      if (level >= 0 && scale > 0) (level * 100 / scale)
      else bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    val chargeCounter =
      try {
        bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
      } catch (_: Throwable) {
        Long.MIN_VALUE
      }
    val currentNow =
      try {
        bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
      } catch (_: Throwable) {
        0L
      }
    return BatterySnapshot(
      levelPct = pct,
      chargeCounterUah = if (chargeCounter > 0L) chargeCounter else Long.MIN_VALUE,
      currentNowUa = if (currentNow == Long.MIN_VALUE) 0L else currentNow,
      tempTenthsC = temp,
      charging = plugged != 0,
    )
  }

  private fun readProcessJiffies(): Long {
    return try {
      val line = File("/proc/self/stat").readText()
      parseProcStat(line)?.jiffies ?: -1L
    } catch (_: Throwable) {
      -1L
    }
  }

  private fun readThreadJiffies(): Map<Int, ThreadSample> {
    val taskDir = File("/proc/self/task")
    val entries = taskDir.listFiles() ?: return emptyMap()
    val result = HashMap<Int, ThreadSample>(entries.size)
    for (dir in entries) {
      val tid = dir.name.toIntOrNull() ?: continue
      val stat =
        try {
          File(dir, "stat").readText()
        } catch (_: Throwable) {
          continue
        }
      val parsed = parseProcStat(stat) ?: continue
      result[tid] = if (tid == Process.myPid()) parsed.copy(name = "main") else parsed
    }
    return result
  }

  private inline fun safeTrafficBytes(reader: () -> Long): Long {
    return try {
      val value = reader()
      if (value >= 0L) value else -1L
    } catch (_: Throwable) {
      -1L
    }
  }

  /**
   * Parses `/proc/self/stat` or `/proc/self/task/<tid>/stat` where comm is enclosed in parentheses
   * `(name)` and fields 14 (utime) and 15 (stime) are indices 11 and 12 after the closing `)`.
   */
  internal fun parseProcStat(statLine: String): ThreadSample? {
    val open = statLine.indexOf('(')
    val close = statLine.lastIndexOf(')')
    if (open < 0 || close <= open || close + 2 >= statLine.length) return null
    val comm = statLine.substring(open + 1, close)
    val rest = statLine.substring(close + 2).trim().split(Regex("\\s+"))
    if (rest.size < 13) return null
    val utime = rest[11].toLongOrNull() ?: return null
    val stime = rest[12].toLongOrNull() ?: return null
    return ThreadSample(comm, utime + stime)
  }

  internal fun formatBytes(bytes: Long): String {
    val safe = max(0L, bytes)
    return when {
      safe >= 1024L * 1024L -> String.format(Locale.ROOT, "%.2f MB", safe / (1024.0 * 1024.0))
      safe >= 1024L -> String.format(Locale.ROOT, "%.1f KB", safe / 1024.0)
      else -> "$safe B"
    }
  }

  internal fun formatDuration(ms: Long): String {
    val safe = max(0L, ms)
    if (safe < 1000L) return "${safe} ms"
    val totalSec = safe / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return when {
      hours > 0L -> String.format(Locale.ROOT, "%dh %02dm %02ds", hours, mins, secs)
      mins > 0L -> String.format(Locale.ROOT, "%dm %02ds", mins, secs)
      else -> String.format(Locale.ROOT, "%ds", secs)
    }
  }
}
