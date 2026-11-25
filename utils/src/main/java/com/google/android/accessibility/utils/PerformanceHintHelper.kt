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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.android.libraries.accessibility.utils.log.LogUtils
import kotlin.math.roundToInt

object PerformanceHintHelper {
  private const val TAG = "PerformanceHintHelper"

  internal const val TARGET_WORK_DURATION_NANOS = 12_000_000L

  internal const val TARGET_90HZ_NANOS = 9_000_000L

  internal const val TARGET_HIGH_REFRESH_NANOS = 7_000_000L

  /** Minimum interval between proactive touch boosts to avoid spamming PowerHAL. */
  private const val MIN_BOOST_INTERVAL_MS = 40L

  internal const val MAX_TRACKED_TIDS = 12

  @Volatile private var lastBoostUptimeMs = 0L

  internal fun targetNanosForRefreshRate(refreshRateHz: Float): Long =
    when {
      refreshRateHz >= 115f -> TARGET_HIGH_REFRESH_NANOS
      refreshRateHz >= 85f -> TARGET_90HZ_NANOS
      else -> TARGET_WORK_DURATION_NANOS
    }

  internal fun boostNanosForTarget(targetNanos: Long): Long =
    (targetNanos * 3L) / 2L

  /** Opens an ADPF hint session for the calling (main) thread. Safe no-op below API 31. */
  @JvmStatic
  fun start(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val refreshRate = readDisplayRefreshRate(context)
    Api31Impl.start(context, refreshRate)
  }

  @JvmStatic
  fun registerWorkerThread(tid: Int) {
    if (tid <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    Api31Impl.registerWorkerThread(tid)
  }

  @JvmStatic
  fun setPreferPowerEfficiency(preferPowerEfficiency: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    Api31Impl.setPreferPowerEfficiency(preferPowerEfficiency)
  }

  /** Proactively hints PowerHAL at touch start so CPU clocks ramp before tree traversal begins. */
  @JvmStatic
  fun onTouchInteractionStart() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val now = SystemClock.uptimeMillis()
    if (now - lastBoostUptimeMs < MIN_BOOST_INTERVAL_MS) return
    lastBoostUptimeMs = now
    Api31Impl.reportProactiveBoost()
  }

  /** Reports an observed work duration (e.g., TraversalTreeCache order build) in milliseconds. */
  @JvmStatic
  fun reportWorkDurationMs(durationMs: Long) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || durationMs <= 0L) return
    val nanos = (durationMs * 1_000_000L).coerceIn(1_000_000L, 250_000_000L)
    Api31Impl.report(nanos)
  }

  /** Closes the ADPF hint session when the accessibility service is destroyed. */
  @JvmStatic
  fun close() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    Api31Impl.close()
  }

  @JvmStatic
  fun statusSummary(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return "unsupported (API ${Build.VERSION.SDK_INT} < 31)"
    }
    return Api31Impl.statusSummary()
  }

  private fun readDisplayRefreshRate(context: Context): Float {
    return try {
      val dm = context.getSystemService(DisplayManager::class.java)
      val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
      val rate = display?.refreshRate ?: 60f
      if (rate.isFinite() && rate >= 30f) rate else 60f
    } catch (_: Throwable) {
      60f
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private object Api31Impl {
    private val lock = Any()
    @Volatile private var session: PerformanceHintManager.Session? = null
    private val trackedTids = LinkedHashSet<Int>()
    private var targetDurationNanos: Long = TARGET_WORK_DURATION_NANOS
    private var detectedRefreshRateHz: Float = 60f
    private var setThreadsApplied: Boolean = false
    private var powerEfficiencyApplied: Boolean = false
    private var preferPowerEfficiencyState: Boolean = false

    fun start(context: Context, refreshRateHz: Float) {
      synchronized(lock) {
        if (session != null) return
        detectedRefreshRateHz = refreshRateHz
        targetDurationNanos = targetNanosForRefreshRate(refreshRateHz)
        try {
          val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
          val mainTid = Process.myTid()
          trackedTids.add(mainTid)
          val initialTids = trackedTids.take(MAX_TRACKED_TIDS).toIntArray()
          val created = manager.createHintSession(initialTids, targetDurationNanos)
          session = created
          if (created != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
              preferPowerEfficiencyState = false
              powerEfficiencyApplied = Api35Impl.setPreferPowerEfficiency(created, false)
            }
            LogUtils.d(
              TAG,
              "ADPF hint session started: tids=%d target=%dms refresh=%.0fHz",
              initialTids.size,
              targetDurationNanos / 1_000_000L,
              refreshRateHz,
            )
          }
        } catch (t: Throwable) {
          LogUtils.w(TAG, "Unable to start ADPF hint session: %s", t)
          session = null
        }
      }
    }

    fun registerWorkerThread(tid: Int) {
      synchronized(lock) {
        if (trackedTids.size >= MAX_TRACKED_TIDS || !trackedTids.add(tid)) return
        val active = session ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          val tids = trackedTids.toIntArray()
          if (Api34Impl.setThreads(active, tids)) {
            setThreadsApplied = true
            LogUtils.d(TAG, "ADPF session updated with %d threads", tids.size)
          }
        }
      }
    }

    fun setPreferPowerEfficiency(prefer: Boolean) {
      synchronized(lock) {
        preferPowerEfficiencyState = prefer
        val active = session ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
          if (Api35Impl.setPreferPowerEfficiency(active, prefer)) {
            powerEfficiencyApplied = true
          }
        }
      }
    }

    fun reportProactiveBoost() {
      report(boostNanosForTarget(targetDurationNanos))
    }

    fun report(actualNanos: Long) {
      synchronized(lock) {
        val active = session ?: return
        try {
          active.reportActualWorkDuration(actualNanos)
        } catch (_: Throwable) {}
      }
    }

    fun close() {
      synchronized(lock) {
        val active = session ?: return
        session = null
        trackedTids.clear()
        setThreadsApplied = false
        powerEfficiencyApplied = false
        try {
          active.close()
        } catch (t: Throwable) {
          LogUtils.w(TAG, "Error closing ADPF hint session: %s", t)
        }
      }
    }

    fun statusSummary(): String =
      synchronized(lock) {
        if (session == null) {
          "inactive"
        } else {
          val targetMs = targetDurationNanos / 1_000_000L
          val hz = detectedRefreshRateHz.roundToInt()
          "active (target=${targetMs} ms @ ${hz} Hz, threads=${trackedTids.size}, " +
            "setThreads=${if (setThreadsApplied) "yes" else "no"}, " +
            "powerEffApi=${if (powerEfficiencyApplied) "yes(off=${preferPowerEfficiencyState})" else "no"})"
        }
      }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private object Api34Impl {
    fun setThreads(session: PerformanceHintManager.Session, tids: IntArray): Boolean {
      return try {
        session.setThreads(tids)
        true
      } catch (_: Throwable) {
        false
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  private object Api35Impl {
    fun setPreferPowerEfficiency(
      session: PerformanceHintManager.Session,
      preferPowerEfficiency: Boolean,
    ): Boolean {
      return try {
        session.setPreferPowerEfficiency(preferPowerEfficiency)
        true
      } catch (_: Throwable) {
        false
      }
    }
  }
}
