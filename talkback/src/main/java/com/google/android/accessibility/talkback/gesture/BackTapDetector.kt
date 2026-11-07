/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.gesture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class BackTapDetector(private val listener: Listener) {

  interface Listener {
    fun onTaps(count: Int)

    fun onJolt(description: String) {}
  }

  var threshold: Float = Sensitivity.MEDIUM.threshold

  var waitForTriple: Boolean = true

  enum class Sensitivity(val threshold: Float) {
    LOW(14f),
    MEDIUM(9f),
    HIGH(6f),
  }

  private var started = false
  private var lastNs = 0L
  private var slowX = 0f
  private var slowY = 0f
  private var slowZ = 0f
  private var movement = 0f

  private var joltStartNs = NONE
  private var joltZ = 0f
  private var joltSideways = 0f
  private var movementBeforeJolt = 0f
  private var nextJoltNs = 0L

  private var taps = 0
  private var lastTapNs = 0L
  private var ignoreTapsUntilNs = 0L

  private var touching = false
  private var touchEndNs = NONE
  private val pendingTouchNs = ArrayDeque<Long>()
  private val pendingTouchStarts = ArrayDeque<Boolean>()

  fun onSample(timeNs: Long, x: Float, y: Float, z: Float) {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
      return
    }
    while (pendingTouchNs.isNotEmpty() && pendingTouchNs.first() <= timeNs) {
      applyTouch(pendingTouchNs.removeFirst(), pendingTouchStarts.removeFirst())
    }
    if (!started || timeNs <= lastNs || timeNs - lastNs > MAX_SAMPLE_GAP_NS) {
      restart(timeNs, x, y, z)
      return
    }
    val dt = (timeNs - lastNs) / 1e9f
    lastNs = timeNs
    val fastX = x - slowX
    val fastY = y - slowY
    val fastZ = z - slowZ
    val slowAlpha = dt / (SLOW_TAU_S + dt)
    slowX += fastX * slowAlpha
    slowY += fastY * slowAlpha
    slowZ += fastZ * slowAlpha

    if (joltStartNs == NONE) {
      if (abs(fastZ) >= threshold * MEASURED_RATIO && timeNs >= nextJoltNs) {
        joltStartNs = timeNs
        joltZ = abs(fastZ)
        joltSideways = max(abs(fastX), abs(fastY))
        movementBeforeJolt = movement
      }
    } else {
      joltZ = max(joltZ, abs(fastZ))
      joltSideways = max(joltSideways, max(abs(fastX), abs(fastY)))
      if (timeNs - joltStartNs >= JOLT_NS) {
        onJoltMeasured(timeNs)
      }
    }

    val size = sqrt(fastX * fastX + fastY * fastY + fastZ * fastZ)
    movement += (size - movement) * (dt / (MOVEMENT_TAU_S + dt))

    finishTaps(timeNs)
  }

  fun onTouchStart(timeNs: Long) {
    onTouch(timeNs, start = true)
  }

  fun onTouchEnd(timeNs: Long) {
    onTouch(timeNs, start = false)
  }

  private fun onTouch(timeNs: Long, start: Boolean) {
    when {
      !started || abs(timeNs - lastNs) > MAX_TOUCH_OFFSET_NS -> applyTouch(lastNs, start)
      timeNs <= lastNs && pendingTouchNs.isEmpty() -> applyTouch(timeNs, start)
      else -> {
        pendingTouchNs.addLast(maxOf(timeNs, pendingTouchNs.lastOrNull() ?: timeNs))
        pendingTouchStarts.addLast(start)
      }
    }
  }

  private fun applyTouch(timeNs: Long, start: Boolean) {
    touching = start
    if (start) {
      taps = 0
    } else {
      touchEndNs = timeNs
    }
  }

  private fun restart(timeNs: Long, x: Float, y: Float, z: Float) {
    started = true
    lastNs = timeNs
    slowX = x
    slowY = y
    slowZ = z
    movement = 0f
    joltStartNs = NONE
    taps = 0
    nextJoltNs = timeNs + SETTLE_NS
  }

  private fun onJoltMeasured(timeNs: Long) {
    val startNs = joltStartNs
    joltStartNs = NONE
    val following = taps > 0 && startNs - lastTapNs <= MAX_TAP_INTERVAL_NS
    val needed = if (following) threshold * FOLLOWING_TAP_RATIO else threshold
    if (joltZ < needed) {
      listener.onJolt(
        String.format(java.util.Locale.ROOT, "Back tap jolt %.1f: too light, %.1f needed", joltZ, needed)
      )
      return
    }
    nextJoltNs = startNs + RING_NS
    val stillnessRatio = if (taps == 0) STILL_BEFORE_FIRST_TAP else STILL_BEFORE_NEXT_TAP
    val rejection =
      when {
        touching || (touchEndNs != NONE && timeNs - touchEndNs < TOUCH_GUARD_NS) -> "screen touched"
        startNs < ignoreTapsUntilNs -> "just after a run"
        joltZ < SCREEN_AXIS_RATIO * joltSideways -> "sideways"
        movementBeforeJolt > stillnessRatio * joltZ -> "moving"
        else -> null
      }
    listener.onJolt(
      String.format(
        java.util.Locale.ROOT,
        "Back tap jolt %.1f, sideways %.1f, movement before %.2f: %s",
        joltZ,
        joltSideways,
        movementBeforeJolt,
        rejection ?: "tap ${taps + 1}",
      )
    )
    if (rejection != null) {
      if (rejection != "just after a run") {
        taps = 0
      }
      return
    }
    if (taps > 0 && startNs - lastTapNs > MAX_TAP_INTERVAL_NS) {
      taps = 0
    }
    if (taps < MAX_TAPS) {
      taps++
      lastTapNs = startNs
    }
  }

  private fun finishTaps(timeNs: Long) {
    if (taps == 0) {
      return
    }
    val sinceLastTap = timeNs - lastTapNs
    val count =
      when {
        sinceLastTap < TOUCH_GUARD_NS -> return
        taps == MAX_TAPS -> MAX_TAPS
        taps == 2 && !waitForTriple -> 2
        sinceLastTap > MAX_TAP_INTERVAL_NS -> taps
        else -> return
      }
    taps = 0
    ignoreTapsUntilNs = timeNs + MAX_TAP_INTERVAL_NS
    if (count >= 2) {
      listener.onTaps(count)
    }
  }

  private companion object {
    const val NONE = -1L
    const val MAX_TAPS = 3
    const val MS = 1_000_000L

    const val SLOW_TAU_S = 0.02f
    const val MOVEMENT_TAU_S = 0.1f
    const val MAX_SAMPLE_GAP_NS = 100 * MS
    const val SETTLE_NS = 100 * MS
    const val MEASURED_RATIO = 0.5f
    const val FOLLOWING_TAP_RATIO = 0.75f
    const val JOLT_NS = 25 * MS
    const val RING_NS = 90 * MS
    const val MAX_TAP_INTERVAL_NS = 450 * MS
    const val TOUCH_GUARD_NS = 150 * MS
    const val MAX_TOUCH_OFFSET_NS = 2_000 * MS
    const val SCREEN_AXIS_RATIO = 1.2f
    const val STILL_BEFORE_FIRST_TAP = 0.25f
    const val STILL_BEFORE_NEXT_TAP = 0.45f
  }
}
