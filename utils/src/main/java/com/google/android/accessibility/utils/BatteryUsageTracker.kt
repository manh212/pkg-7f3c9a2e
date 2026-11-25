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
 * Lightweight, zero-polling battery and resource profiler for Meridian.
 *
 * Combines:
 * 1. Real battery drain via [BatteryManager] (`CHARGE_COUNTER` in µAh, level %, current µA, temp),
 *    strictly separated into unplugged Screen-On vs Screen-Off segments.
 * 2. Exact per-thread CPU time (user + kernel ms) from `/proc/self/task/<tid>/stat`, attributed to
 *    Screen-On vs Screen-Off only at state transitions and on-demand report generation.
 * 3. Lightweight `AtomicLong` hardware counters for LowLatencyAudio (audible vs keep-warm idle),
 *    sensors (BackTap, StopSensors, Proximity), WakeLocks, screen-off AccessibilityEvents per
 *    package, traversal order builds, and background prefetches.
 */
object BatteryUsageTracker {

  private val stateLock = Any()
  private val initialized = AtomicBoolean(false)

  // Session baseline & current state
  @Volatile private var sessionStartWallMs = 0L
  @Volatile private var sessionStartElapsedMs = 0L
  @Volatile private var lastSegmentElapsedMs = 0L
  @Volatile private var startBatteryLevel = -1
  @Volatile private var lastChargeCounterUah = Long.MIN_VALUE
  @Volatile private var isScreenOn = true
  @Volatile private var isCharging = false

  // Accumulated unplugged durations & charge drain (µAh)
  private var unpluggedScreenOnMs = 0L
  private var unpluggedScreenOffMs = 0L
  private var pluggedMs = 0L
  private var screenOnDrainUah = 0L
  private var screenOffDrainUah = 0L

  // Per-thread CPU tracking (jiffies snapshot -> accumulated ms split by screen state)
  private val lastThreadJiffies = HashMap<Int, ThreadSample>()
  private val screenOnThreadCpuMs = HashMap<String, Long>()
  private val screenOffThreadCpuMs = HashMap<String, Long>()
  private var totalCpuScreenOnMs = 0L
  private var totalCpuScreenOffMs = 0L

  // Audio counters (microseconds)
  private val audioAudibleUs = AtomicLong()
  private val audioKeepWarmIdleUs = AtomicLong()
  private val audioAudibleBursts = AtomicLong()
  private val audioIdleBursts = AtomicLong()

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
  private val screenOffEventsByPackage = ConcurrentHashMap<String, AtomicLong>()
  private val orderBuildsScreenOn = AtomicLong()
  private val orderBuildsScreenOff = AtomicLong()
  private val orderBuildTotalMs = AtomicLong()
  private val windowPrefetches = AtomicLong()
  private val pcmPrefetches = AtomicLong()

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

  /** Initializes a profiling session if not already started. */
  @JvmStatic
  fun startIfNeeded(context: Context) {
    if (initialized.compareAndSet(false, true)) {
      resetSession(context)
    }
  }

  /** Resets all battery, CPU, sensor, audio, wakelock, and event counters to right now. */
  @JvmStatic
  fun resetSession(context: Context) {
    val app = context.applicationContext
    val bat = readBattery(app)
    val power = app.getSystemService(PowerManager::class.java)
    val screenInteractive = power?.isInteractive ?: true
    val nowWall = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()

    synchronized(stateLock) {
      initialized.set(true)
      sessionStartWallMs = nowWall
      sessionStartElapsedMs = nowElapsed
      lastSegmentElapsedMs = nowElapsed
      startBatteryLevel = bat.levelPct
      lastChargeCounterUah = bat.chargeCounterUah
      isScreenOn = screenInteractive
      isCharging = bat.charging

      unpluggedScreenOnMs = 0L
      unpluggedScreenOffMs = 0L
      pluggedMs = 0L
      screenOnDrainUah = 0L
      screenOffDrainUah = 0L

      lastThreadJiffies.clear()
      lastThreadJiffies.putAll(readThreadJiffies())
      screenOnThreadCpuMs.clear()
      screenOffThreadCpuMs.clear()
      totalCpuScreenOnMs = 0L
      totalCpuScreenOffMs = 0L

      audioAudibleUs.set(0L)
      audioKeepWarmIdleUs.set(0L)
      audioAudibleBursts.set(0L)
      audioIdleBursts.set(0L)

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
      screenOffEventsByPackage.clear()
      orderBuildsScreenOn.set(0L)
      orderBuildsScreenOff.set(0L)
      orderBuildTotalMs.set(0L)
      windowPrefetches.set(0L)
      pcmPrefetches.set(0L)
    }
  }

  /** Called on ACTION_SCREEN_ON / ACTION_SCREEN_OFF. Zero background polling needed. */
  @JvmStatic
  fun onScreenStateChanged(context: Context, screenOn: Boolean) {
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
    if (durationUs <= 0L) return
    if (audible) {
      audioAudibleUs.addAndGet(durationUs)
      audioAudibleBursts.incrementAndGet()
    } else {
      audioKeepWarmIdleUs.addAndGet(durationUs)
      audioIdleBursts.incrementAndGet()
    }
  }

  /** Records sensor listener registration / unregistration. */
  @JvmStatic
  fun onSensorStateChanged(sensorName: String, active: Boolean) {
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
    sensors.getOrPut(sensorName) { SensorStats() }.samples.incrementAndGet()
  }

  /** Records WakeLock acquisition. */
  @JvmStatic
  fun onWakeLockAcquire(tag: String) {
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

  /** Records an incoming AccessibilityEvent and tracks per-package screen-off wakeups. */
  @JvmStatic
  fun onAccessibilityEvent(packageName: CharSequence?, eventType: Int) {
    if (isScreenOn) {
      eventsScreenOn.incrementAndGet()
    } else {
      eventsScreenOff.incrementAndGet()
      val pkg = packageName?.toString()?.takeIf { it.isNotEmpty() } ?: "system"
      val counter = screenOffEventsByPackage[pkg]
      if (counter != null) {
        counter.incrementAndGet()
      } else if (screenOffEventsByPackage.size < MAX_TRACKED_PACKAGES) {
        screenOffEventsByPackage.putIfAbsent(pkg, AtomicLong(1L))?.incrementAndGet()
      }
    }
  }

  /** Records a TraversalTreeCache order build. */
  @JvmStatic
  fun onOrderBuild(durationMs: Long) {
    if (isScreenOn) orderBuildsScreenOn.incrementAndGet() else orderBuildsScreenOff.incrementAndGet()
    if (durationMs > 0L) orderBuildTotalMs.addAndGet(durationMs)
  }

  /** Records a background WindowNodePrefetcher run. */
  @JvmStatic
  fun onWindowPrefetch() {
    windowPrefetches.incrementAndGet()
  }

  /** Records a background NavigationPcmCache synthesis request. */
  @JvmStatic
  fun onPcmPrefetch() {
    pcmPrefetches.incrementAndGet()
  }

  /** One-line summary for the Settings preference subtitle. */
  @JvmStatic
  fun buildShortSubtitle(context: Context): String {
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
      levelDrop = if (startBatteryLevel >= 0 && bat.levelPct >= 0) startBatteryLevel - bat.levelPct else 0
    }
    val drainMah = drainUah / 1000.0
    return String.format(
      Locale.ROOT,
      "Đã đo %s (rút sạc) · Pin: %d%% (%+d%%, -%.1f mAh)",
      formatDuration(unpluggedMs),
      bat.levelPct,
      -levelDrop,
      drainMah,
    )
  }

  /** Compact section embedded into `meridian-diagnostics-*.txt`. */
  @JvmStatic
  fun buildCompactSummary(context: Context, flags: DiagnosticFlags = DiagnosticFlags()): String =
    buildDetailedReport(context, flags, maxThreads = 6, maxPackages = 5)

  /** Full, human-readable detailed battery breakdown report for in-app viewing and file export. */
  @JvmStatic
  fun buildDetailedReport(
    context: Context,
    flags: DiagnosticFlags = DiagnosticFlags(),
    maxThreads: Int = 12,
    maxPackages: Int = 8,
  ): String {
    if (!initialized.get()) {
      startIfNeeded(context)
    }
    val app = context.applicationContext
    val bat = readBattery(app)
    val nowElapsed = SystemClock.elapsedRealtime()
    val nowWall = System.currentTimeMillis()

    val totalElapsedMs: Long
    val onMs: Long
    val offMs: Long
    val chgMs: Long
    val onDrainUah: Long
    val offDrainUah: Long
    val cpuOnMs: Long
    val cpuOffMs: Long
    val threadRows: List<ThreadCpuRow>
    val startLevel: Int
    val startWall: Long

    synchronized(stateLock) {
      flushSegmentLocked(app)
      totalElapsedMs = max(1L, nowElapsed - sessionStartElapsedMs)
      onMs = unpluggedScreenOnMs
      offMs = unpluggedScreenOffMs
      chgMs = pluggedMs
      onDrainUah = screenOnDrainUah
      offDrainUah = screenOffDrainUah
      cpuOnMs = totalCpuScreenOnMs
      cpuOffMs = totalCpuScreenOffMs
      startLevel = startBatteryLevel
      startWall = sessionStartWallMs

      val allThreadNames = (screenOnThreadCpuMs.keys + screenOffThreadCpuMs.keys).toSet()
      threadRows =
        allThreadNames
          .map { name ->
            val onCpu = screenOnThreadCpuMs[name] ?: 0L
            val offCpu = screenOffThreadCpuMs[name] ?: 0L
            ThreadCpuRow(name, onCpu, offCpu, onCpu + offCpu)
          }
          .filter { it.totalMs > 0L }
          .sortedByDescending { it.totalMs }
    }

    val unpluggedMs = onMs + offMs
    val totalDrainUah = onDrainUah + offDrainUah
    val totalDrainMah = totalDrainUah / 1000.0
    val onDrainMah = onDrainUah / 1000.0
    val offDrainMah = offDrainUah / 1000.0
    val unpluggedHours = unpluggedMs / 3_600_000.0
    val mahPerHour = if (unpluggedHours > 0.005) totalDrainMah / unpluggedHours else 0.0
    val levelDrop = if (startLevel >= 0 && bat.levelPct >= 0) startLevel - bat.levelPct else 0
    val pctPerHour = if (unpluggedHours > 0.005 && levelDrop > 0) levelDrop / unpluggedHours else 0.0

    val totalCpuMs = cpuOnMs + cpuOffMs
    val mainCpuMs = threadRows.firstOrNull { isMainThreadName(it.name) }?.totalMs ?: 0L
    val bgCpuMs = max(0L, totalCpuMs - mainCpuMs)
    val cpuOnPct = if (onMs > 0L) cpuOnMs * 100.0 / onMs else 0.0
    val cpuOffPct = if (offMs > 0L) cpuOffMs * 100.0 / offMs else 0.0

    val audibleMs = audioAudibleUs.get() / 1000L
    val keepWarmMs = audioKeepWarmIdleUs.get() / 1000L
    val totalAudioMs = audibleMs + keepWarmMs

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

    // Weighted internal power attribution (relative mW-second score normalized to 100%)
    val scoreCpuMain = mainCpuMs * 1.0
    val scoreCpuBg = bgCpuMs * 0.85
    val scoreAudioAudible = audibleMs * 0.28
    val scoreAudioKeepWarm = keepWarmMs * 0.16
    val scoreSensors = totalSensorMs * 0.14
    val scoreWakeLocks = totalWakeLockMs * 0.12
    val scoreDebugLog = if (flags.debugLogOn) totalCpuMs * 0.25 else 0.0
    val totalScore =
      max(
        1.0,
        scoreCpuMain +
          scoreCpuBg +
          scoreAudioAudible +
          scoreAudioKeepWarm +
          scoreSensors +
          scoreWakeLocks +
          scoreDebugLog,
      )

    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    val sb = StringBuilder(2048)

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
        "Pin máy: %d%% → %d%% (tụt %d%%) · Trạng thái: %s · Nhiệt độ: %.1f°C\n",
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
          "Dung lượng tụt thực (CHARGE_COUNTER): -%.1f mAh (Bật màn: -%.1f mAh | Tắt màn: -%.1f mAh)\n",
          totalDrainMah,
          onDrainMah,
          offDrainMah,
        )
      )
    }
    sb.append(
      String.format(
        Locale.ROOT,
        "Tốc độ xả: %.1f %%/giờ (%.0f mAh/giờ) · Dòng tức thời: %.0f mA\n",
        pctPerHour,
        mahPerHour,
        abs(bat.currentNowUa) / 1000.0,
      )
    )
    sb.append(
      String.format(
        Locale.ROOT,
        "Tổng CPU tiến trình Meridian: %s (Bật màn: %s [%.2f%% tải] | Tắt màn: %s [%.2f%% tải])\n",
        formatDuration(totalCpuMs),
        formatDuration(cpuOnMs),
        cpuOnPct,
        formatDuration(cpuOffMs),
        cpuOffPct,
      )
    )

    sb.append("\n-- Phân bổ tỷ trọng ước tính nội bộ Meridian (100%) --\n")
    appendShare(sb, "CPU luồng chính (main)", scoreCpuMain, totalScore, formatDuration(mainCpuMs))
    appendShare(sb, "CPU luồng nền (prefetch/order/audio/AI)", scoreCpuBg, totalScore, formatDuration(bgCpuMs))
    appendShare(sb, "Âm thanh (phát tiếng thật)", scoreAudioAudible, totalScore, formatDuration(audibleMs))
    appendShare(sb, "Âm thanh (giữ ấm im lặng Keep-Warm)", scoreAudioKeepWarm, totalScore, formatDuration(keepWarmMs))
    appendShare(sb, "Cảm biến (BackTap / Lắc / Tiệm cận)", scoreSensors, totalScore, formatDuration(totalSensorMs))
    appendShare(sb, "WakeLock / Giữ sáng màn hình", scoreWakeLocks, totalScore, formatDuration(totalWakeLockMs))
    if (flags.debugLogOn) {
      appendShare(sb, "Ghi nhật ký gỡ lỗi (Debug log Verbose)", scoreDebugLog, totalScore, "ĐANG BẬT")
    }

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
            "  • %-24s %6d ms (%4.1f%%) | Bật màn: %5d ms | Tắt màn: %5d ms\n",
            row.name.take(24),
            row.totalMs,
            pct,
            row.screenOnMs,
            row.screenOffMs,
          )
        )
      }
    }

    sb.append("\n-- Chi tiết Âm thanh (LowLatencyAudio) --\n")
    val keepWarmPctOfAudio = if (totalAudioMs > 0L) keepWarmMs * 100.0 / totalAudioMs else 0.0
    sb.append(
      String.format(
        Locale.ROOT,
        "  • Phát tiếng thật: %s (%d bursts) | Giữ ấm im lặng: %s (%d bursts, %.1f%% tổng audio)\n",
        formatDuration(audibleMs),
        audioAudibleBursts.get(),
        formatDuration(keepWarmMs),
        audioIdleBursts.get(),
        keepWarmPctOfAudio,
      )
    )

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
        pcmPrefetches.get(),
      )
    )
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
      alerts.add("[CẢNH BÁO] Ghi nhật ký gỡ lỗi (Debug log) đang BẬT — ghi logcat liên tục tốn rất nhiều CPU và pin.")
    }
    if (flags.performanceStatsOn) {
      alerts.add("[CẢNH BÁO] In thống kê hiệu suất (pref_performance_stats) đang BẬT — tăng tải luồng chính.")
    }
    if (flags.backTapEnabled) {
      val backTapMs = sensorRows.firstOrNull { it.name.startsWith("BackTap") }?.activeMs ?: 0L
      alerts.add(
        "[CẢNH BÁO] Gõ mặt lưng (BackTap) đang BẬT (cảm biến chạy ${formatDuration(backTapMs)}) — gia tốc kế chạy liên tục là nguồn hao pin lớn."
      )
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

    if (lastChargeCounterUah != Long.MIN_VALUE && bat.chargeCounterUah > 0L) {
      val deltaDrain = lastChargeCounterUah - bat.chargeCounterUah
      if (!isCharging && deltaDrain > 0L) {
        if (isScreenOn) screenOnDrainUah += deltaDrain else screenOffDrainUah += deltaDrain
      }
    }
    if (bat.chargeCounterUah > 0L) {
      lastChargeCounterUah = bat.chargeCounterUah
    }

    // Flush per-thread CPU deltas from /proc/self/task/*/stat
    val currentThreads = readThreadJiffies()
    val targetMap = if (isScreenOn) screenOnThreadCpuMs else screenOffThreadCpuMs
    var deltaCpuMsTotal = 0L
    for ((tid, sample) in currentThreads) {
      val prev = lastThreadJiffies[tid]
      val deltaJiffies =
        if (prev != null && prev.name == sample.name) {
          max(0L, sample.jiffies - prev.jiffies)
        } else {
          0L
        }
      if (deltaJiffies > 0L) {
        val deltaMs = deltaJiffies * 1000L / clkTck
        val groupName = normalizeThreadName(sample.name)
        targetMap[groupName] = (targetMap[groupName] ?: 0L) + deltaMs
        deltaCpuMsTotal += deltaMs
      }
    }
    if (isScreenOn) totalCpuScreenOnMs += deltaCpuMsTotal else totalCpuScreenOffMs += deltaCpuMsTotal
    lastThreadJiffies.clear()
    lastThreadJiffies.putAll(currentThreads)
  }

  private fun normalizeThreadName(raw: String): String {
    val name = raw.trim()
    return when {
      name.startsWith("BacktalkPrefetch") -> "BacktalkPrefetch (pool)"
      name.startsWith("Binder:") -> "Binder (IPC pool)"
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

  /**
   * Parses `/proc/self/task/<tid>/stat` where comm is enclosed in parentheses `(name)` and fields
   * 14 (utime) and 15 (stime) are 1-indexed — i.e., indices 11 and 12 after the closing `)`.
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
