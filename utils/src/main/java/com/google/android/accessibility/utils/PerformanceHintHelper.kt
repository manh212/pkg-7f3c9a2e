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
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Uses Android's Dynamic Performance Framework (ADPF, API 31+) to keep the service's main thread
 * responsive during touch exploration, swipes, and traversal-order builds — especially helpful on
 * stock Android / Google Pixel Tensor devices whose scheduler otherwise ramps clocks slowly for
 * background AccessibilityService processes.
 */
object PerformanceHintHelper {
  private const val TAG = "PerformanceHintHelper"

  /** Target work duration (~12 ms) so PowerHAL treats swipes as latency-critical UI work. */
  internal const val TARGET_WORK_DURATION_NANOS = 12_000_000L

  /** Proactive boost duration (16 ms > 12 ms target) sent on touch interaction start. */
  private const val INTERACTION_BOOST_NANOS = 16_000_000L

  /** Minimum interval between proactive touch boosts to avoid spamming PowerHAL. */
  private const val MIN_BOOST_INTERVAL_MS = 40L

  @Volatile private var lastBoostUptimeMs = 0L

  /** Opens an ADPF hint session for the calling (main) thread. Safe no-op below API 31. */
  @JvmStatic
  fun start(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    Api31Impl.start(context)
  }

  /** Proactively hints PowerHAL at touch start so CPU clocks ramp before tree traversal begins. */
  @JvmStatic
  fun onTouchInteractionStart() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val now = SystemClock.uptimeMillis()
    if (now - lastBoostUptimeMs < MIN_BOOST_INTERVAL_MS) return
    lastBoostUptimeMs = now
    Api31Impl.report(INTERACTION_BOOST_NANOS)
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

  @RequiresApi(Build.VERSION_CODES.S)
  private object Api31Impl {
    private val lock = Any()
    @Volatile private var session: PerformanceHintManager.Session? = null

    fun start(context: Context) {
      synchronized(lock) {
        if (session != null) return
        try {
          val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
          val tid = Process.myTid()
          session = manager.createHintSession(intArrayOf(tid), TARGET_WORK_DURATION_NANOS)
          if (session != null) {
            LogUtils.d(TAG, "ADPF hint session started for tid=%d", tid)
          }
        } catch (t: Throwable) {
          LogUtils.w(TAG, "Unable to start ADPF hint session: %s", t)
          session = null
        }
      }
    }

    fun report(actualNanos: Long) {
      val active = session ?: return
      try {
        active.reportActualWorkDuration(actualNanos)
      } catch (_: Throwable) {
        // Some devices reject or close hint sessions during thermal throttling or backgrounding.
      }
    }

    fun close() {
      synchronized(lock) {
        val active = session ?: return
        session = null
        try {
          active.close()
        } catch (t: Throwable) {
          LogUtils.w(TAG, "Error closing ADPF hint session: %s", t)
        }
      }
    }
  }
}
