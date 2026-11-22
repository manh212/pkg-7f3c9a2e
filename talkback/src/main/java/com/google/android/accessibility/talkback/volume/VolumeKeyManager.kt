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

package com.google.android.accessibility.talkback.volume

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.annotation.MainThread
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.monitor.AudioPlaybackMonitor

object VolumeKeyManager {
  const val KEY_MANAGED = "pref_volume_keys_managed"
  const val KEY_BOTH_ACTION = "pref_volume_both_action"
  const val KEY_UP_PRESS_ACTION = "pref_volume_up_press_action"
  const val KEY_UP_HOLD_ACTION = "pref_volume_up_hold_action"
  const val KEY_UP_HOLD_LONG_ACTION = "pref_volume_up_hold_long_action"
  const val KEY_UP_HOLD_LOCKED_ACTION = "pref_volume_up_hold_locked_action"
  const val KEY_UP_HOLD_LONG_LOCKED_ACTION = "pref_volume_up_hold_long_locked_action"
  const val KEY_UP_PRESS_LOCKED_ACTION = "pref_volume_up_press_locked_action"
  const val KEY_UP_HOLD_SCREEN_OFF_ACTION = "pref_volume_up_hold_screen_off_action"
  const val KEY_UP_HOLD_LONG_SCREEN_OFF_ACTION = "pref_volume_up_hold_long_screen_off_action"
  const val KEY_UP_PRESS_SCREEN_OFF_ACTION = "pref_volume_up_press_screen_off_action"
  const val KEY_DOWN_PRESS_ACTION = "pref_volume_down_press_action"
  const val KEY_DOWN_HOLD_ACTION = "pref_volume_down_hold_action"
  const val KEY_DOWN_HOLD_LONG_ACTION = "pref_volume_down_hold_long_action"
  const val KEY_DOWN_HOLD_LOCKED_ACTION = "pref_volume_down_hold_locked_action"
  const val KEY_DOWN_HOLD_LONG_LOCKED_ACTION = "pref_volume_down_hold_long_locked_action"
  const val KEY_DOWN_PRESS_LOCKED_ACTION = "pref_volume_down_press_locked_action"
  const val KEY_DOWN_HOLD_SCREEN_OFF_ACTION = "pref_volume_down_hold_screen_off_action"
  const val KEY_DOWN_HOLD_LONG_SCREEN_OFF_ACTION = "pref_volume_down_hold_long_screen_off_action"
  const val KEY_DOWN_PRESS_SCREEN_OFF_ACTION = "pref_volume_down_press_screen_off_action"
  const val KEY_SHORT_HOLD_MS = "pref_volume_short_hold_ms"
  const val KEY_LONG_HOLD_MS = "pref_volume_long_hold_ms"

  private const val KEY_MIGRATED = "pref_volume_keys_migrated"
  private const val OLD_KEY_VOLUME_ACTIVATION = "pref_stopwatch_volume_activation"
  private const val ACTION_UNASSIGNED = "UNASSIGNED"
  private const val ACTION_INCREASE_CONTEXTUAL_VOLUME = "INCREASE_CONTEXTUAL_VOLUME"
  private const val ACTION_DECREASE_CONTEXTUAL_VOLUME = "DECREASE_CONTEXTUAL_VOLUME"
  private const val ACTION_TOGGLE_STOPWATCH = "TOGGLE_STOPWATCH"
  // Must match R.string.shortcut_value_answer_call: the stored action value of the
  // Answer call command.
  private const val ACTION_ANSWER_CALL = "ANSWER_CALL"
  const val DEFAULT_SHORT_HOLD_MS = 1500L
  const val DEFAULT_LONG_HOLD_MS = 4000L
  private const val MIN_HOLD_GAP_MS = 500L
  private const val CHORD_MS = 150L
  private const val MULTI_PRESS_MS = 400L
  private const val SLIDER_JUMP_MS = 2000L
  private const val HOLD_WAKE_LOCK_TIMEOUT_MS = 10_000L

  private val handler = Handler(Looper.getMainLooper())
  private var holdWakeLock: PowerManager.WakeLock? = null
  private val upPress = Press(true)
  private val downPress = Press(false)

  /** The screen state a volume-key press was started in. */
  private enum class VolumeKeyState { UNLOCKED, LOCKED, SCREEN_OFF }

  private class Press(val isUp: Boolean) {
    var service: TalkBackService? = null
    var active = false
    var aborted = false
    var holdFired = false
    var singleFired = false
    var jumpFired = false
    var shortArmed = false
    var shortFired = false
    var longFired = false
    var wakeLockHeld = false
    var locked = false
    var screenOff = false
    var touching = false
    var speechVolume = false
    var managed = false
    var multi = false
    var pressUse = TalkBackService.VOLUME_KEY_CHANGES_VOLUME
    var pressAction = ACTION_UNASSIGNED
    var shortHoldAction = ACTION_UNASSIGNED
    var longHoldAction = ACTION_UNASSIGNED
    var pressRunnable: Runnable? = null
    var shortHoldRunnable: Runnable? = null
    var longHoldRunnable: Runnable? = null
    var tapRunnable: Runnable? = null
    var jumpRunnable: Runnable? = null
    var tapCount = 0
  }

  @JvmStatic
  @MainThread
  fun onKeyEvent(
    service: TalkBackService,
    event: KeyEvent,
    triggerModifierPressed: Boolean,
  ): Boolean {
    val press = when (event.keyCode) {
      KeyEvent.KEYCODE_VOLUME_UP -> upPress
      KeyEvent.KEYCODE_VOLUME_DOWN -> downPress
      else -> return false
    }
    if (triggerModifierPressed && !press.active) {
      reset()
      return false
    }
    return when (event.action) {
      KeyEvent.ACTION_DOWN ->
        if (event.repeatCount > 0) onRepeat(press) else onDown(service, press)
      KeyEvent.ACTION_UP -> onUp(press, event.isCanceled)
      else -> press.active
    }
  }

  private fun onDown(service: TalkBackService, press: Press): Boolean {
    if (press.active) abort(press)
    // While a call rings, a volume key with Answer call assigned still runs its commands;
    // any other key, and any key during a call, goes to Android as before.
    if ((service.isPhoneCallActive() && !isRingingCallAnsweredBy(service, press.isUp))
      || isAlarmRinging(service)) {
      reset()
      return false
    }
    if (press.tapCount > 0 && contextChanged(service, press)) {
      clearTaps(press)
      releaseHoldWakeLock(press)
    }
    press.tapRunnable?.let(handler::removeCallbacks)
    press.tapRunnable = null
    val other = if (press === upPress) downPress else upPress
    if (other.tapCount > 0 && !other.active) finishTaps(other)
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    press.service = service
    press.active = true
    press.aborted = false
    press.holdFired = false
    press.singleFired = false
    press.jumpFired = false
    press.shortArmed = false
    press.shortFired = false
    press.longFired = false
    press.locked = isKeyguardLocked(service)
    press.screenOff = isScreenOff(service)
    press.touching = service.isTouchInteracting()
    press.speechVolume = service.shouldAdjustSpeechVolumeWhileSpeaking()
    press.managed = prefs.getBoolean(KEY_MANAGED, false)
    val state = stateOf(press)
    // Multi-press is on exactly when a multi-press action is assigned for this
    // key in this state; there is no separate multi-press switch.
    press.multi = anyMultiAssigned(prefs, press.isUp, state)
    if (other.active) {
      val alreadyAborted = other.aborted
      abort(other)
      abort(press)
      if (!alreadyAborted) {
        runAction(service, prefs.getString(KEY_BOTH_ACTION, "TOGGLE_TOUCH_EXPLORATION")
          ?: "TOGGLE_TOUCH_EXPLORATION", press.touching)
      }
      return true
    }
    press.shortHoldAction = if (press.managed) {
      prefs.getString(shortHoldKey(press.isUp, state), defaultHoldAction(press.isUp, state))
        ?: defaultHoldAction(press.isUp, state)
    } else ACTION_UNASSIGNED
    press.longHoldAction = if (press.managed) {
      prefs.getString(longHoldKey(press.isUp, state), ACTION_UNASSIGNED) ?: ACTION_UNASSIGNED
    } else ACTION_UNASSIGNED
    // With the screen off the device can sleep, and a plain Handler.postDelayed
    if (press.screenOff
      && (press.shortHoldAction != ACTION_UNASSIGNED
        || press.longHoldAction != ACTION_UNASSIGNED
        || press.multi)) {
      if (!press.wakeLockHeld) {
        acquireHoldWakeLock(service)
        press.wakeLockHeld = true
      }
    }
    press.pressAction = if (press.managed) {
      prefs.getString(pressKey(press.isUp, state), defaultPressAction(press.isUp))
        ?: defaultPressAction(press.isUp)
    } else contextualAction(press.isUp)
    press.pressUse = if (press.locked || press.speechVolume) TalkBackService.VOLUME_KEY_CHANGES_VOLUME
      else service.resolveVolumeKeyUse(false)
    // While the focus is on a slider, the volume keys adjust it: any hold action
    // assigned to this key is skipped for this press, so a two-second hold always
    // jumps the slider to its maximum or minimum instead of running that action.
    if (press.pressUse == TalkBackService.VOLUME_KEY_ADJUSTS_SLIDER) {
      press.shortHoldAction = ACTION_UNASSIGNED
      press.longHoldAction = ACTION_UNASSIGNED
    }
    // Press timer: runs the press action once the chord window has passed, but
    // only when neither multi-press nor any hold is assigned for this press.
    val pressRunnable = Runnable {
      if (contextChanged(service, press)) abort(press)
      if (press.active && !press.aborted) {
        if (!press.multi && press.shortHoldAction == ACTION_UNASSIGNED
          && press.longHoldAction == ACTION_UNASSIGNED) {
          runPressAction(press)
          press.singleFired = true
        }
      }
    }
    press.pressRunnable = pressRunnable
    handler.postDelayed(pressRunnable, CHORD_MS)
    val shortHoldMs = readShortHoldMs(prefs)
    val longHoldMs = readLongHoldMs(prefs, shortHoldMs)
    val shortHoldRunnable = Runnable {
      if (contextChanged(service, press)) abort(press)
      if (press.active && !press.aborted) {
        if (press.shortHoldAction != ACTION_UNASSIGNED) {
          vibrateHoldCue(service, isLong = false)
          if (press.longHoldAction == ACTION_UNASSIGNED) {
            fireShortHold(press)
          } else {
            press.shortArmed = true
          }
        } else if (press.longHoldAction == ACTION_UNASSIGNED && press.multi) {
          clearTaps(press)
          press.holdFired = true
          runPressAction(press)
        }
      }
    }
    press.shortHoldRunnable = shortHoldRunnable
    handler.postDelayed(shortHoldRunnable, shortHoldMs)
    val longHoldRunnable = Runnable {
      if (contextChanged(service, press)) abort(press)
      if (press.active && !press.aborted && press.longHoldAction != ACTION_UNASSIGNED) {
        vibrateHoldCue(service, isLong = true)
        fireLongHold(press)
      }
    }
    press.longHoldRunnable = longHoldRunnable
    handler.postDelayed(longHoldRunnable, longHoldMs)
    // While the focus is on a slider and this press adjusts it, a two-second hold jumps
    // the slider to its maximum (volume up) or minimum (volume down) instead of stepping
    // it. Any hold action assigned to this key is skipped for a slider press (see above),
    // so the jump is armed whenever the press adjusts a slider.
    if (press.pressUse == TalkBackService.VOLUME_KEY_ADJUSTS_SLIDER) {
      val jumpRunnable = Runnable {
        if (press.active && !press.aborted && !press.jumpFired) {
          press.jumpFired = true
          service.jumpSliderToEdge(press.isUp)
        }
      }
      press.jumpRunnable = jumpRunnable
      handler.postDelayed(jumpRunnable, SLIDER_JUMP_MS)
    }
    return true
  }

  private fun onRepeat(press: Press): Boolean {
    if (!press.active) return false
    val service = press.service ?: return true
    if (contextChanged(service, press)) abort(press)
    if (!press.aborted) {
      // Repeat the fired hold action when it adjusts the contextual volume.
      val firedHoldAction = when {
        press.longFired -> press.longHoldAction
        press.shortFired -> press.shortHoldAction
        else -> null
      }
      if (firedHoldAction != null && firedHoldAction == contextualAction(press.isUp)) {
        runAction(service, firedHoldAction, press.touching, press.speechVolume)
      } else if ((press.singleFired || press.holdFired)
        && press.shortHoldAction == ACTION_UNASSIGNED
        && press.longHoldAction == ACTION_UNASSIGNED
        && !press.jumpFired) {
        runPressAction(press, repeating = true)
      }
    }
    return true
  }

  private fun onUp(press: Press, cancelled: Boolean): Boolean {
    if (!press.active) return false
    val service = press.service
    if (cancelled || service == null || contextChanged(service, press)) abort(press)
    press.pressRunnable?.let(handler::removeCallbacks)
    press.pressRunnable = null
    press.shortHoldRunnable?.let(handler::removeCallbacks)
    press.shortHoldRunnable = null
    press.longHoldRunnable?.let(handler::removeCallbacks)
    press.longHoldRunnable = null
    press.jumpRunnable?.let(handler::removeCallbacks)
    press.jumpRunnable = null
    press.active = false
    // Held past the short threshold but released before the long one: the armed
    // short hold fires now.
    if (!press.aborted && press.shortArmed && !press.longFired) {
      press.shortArmed = false
      press.shortFired = true
      press.holdFired = true
      if (service != null) {
        runAction(service, press.shortHoldAction, press.touching, press.speechVolume)
      }
    } else if (!press.aborted && !press.holdFired && !press.singleFired) {
      if (press.multi) {
        press.tapCount++
        if (press.tapCount == 4) finishTaps(press)
        else {
          val runnable = Runnable { finishTaps(press) }
          press.tapRunnable = runnable
          handler.postDelayed(runnable, MULTI_PRESS_MS)
          return true
        }
      } else runPressAction(press)
    }
    releaseHoldWakeLock(press)
    return true
  }

  private fun fireShortHold(press: Press) {
    val service = press.service ?: return
    if (!press.active || press.aborted || press.holdFired) return
    clearTaps(press)
    press.holdFired = true
    press.shortFired = true
    runAction(service, press.shortHoldAction, press.touching, press.speechVolume)
  }

  private fun fireLongHold(press: Press) {
    val service = press.service ?: return
    if (!press.active || press.aborted || press.holdFired) return
    clearTaps(press)
    press.shortArmed = false
    press.holdFired = true
    press.longFired = true
    runAction(service, press.longHoldAction, press.touching, press.speechVolume)
  }

  private fun finishTaps(press: Press) {
    val count = press.tapCount
    clearTaps(press)
    val service = press.service
    try {
      if (service == null || count == 0 || contextChanged(service, press)) return
      if (count == 1) runPressAction(press)
      else {
        val prefs = SharedPreferencesUtils.getSharedPreferences(service)
        val action = prefs.getString(multiKey(press.isUp, count, stateOf(press)), ACTION_UNASSIGNED)
          ?: ACTION_UNASSIGNED
        runAction(service, action, press.touching, press.speechVolume)
      }
    } finally {
      if (!press.active) releaseHoldWakeLock(press)
    }
  }

  private fun runPressAction(press: Press, repeating: Boolean = false) {
    val service = press.service ?: return
    if (press.pressUse != TalkBackService.VOLUME_KEY_CHANGES_VOLUME) {
      service.performVolumeKeyUse(press.pressUse, press.isUp)
    } else if (!press.managed && !press.speechVolume) {
      service.adjustUnmanagedVolumeKey(press.isUp, press.touching, repeating)
    } else if (!repeating || press.pressAction == ACTION_INCREASE_CONTEXTUAL_VOLUME ||
      press.pressAction == ACTION_DECREASE_CONTEXTUAL_VOLUME) {
      runAction(service, press.pressAction, press.touching, press.speechVolume)
    }
  }

  private fun runAction(
    service: TalkBackService, action: String, touching: Boolean, speechVolume: Boolean = false,
  ) {
    when (action) {
      ACTION_UNASSIGNED -> Unit
      ACTION_INCREASE_CONTEXTUAL_VOLUME, ACTION_DECREASE_CONTEXTUAL_VOLUME -> {
        val increase = action == ACTION_INCREASE_CONTEXTUAL_VOLUME
        if (speechVolume) service.adjustSpeechVolumeWhileSpeaking(increase)
        else service.adjustContextualVolume(increase, touching)
      }
      else -> service.performCatalogAction(action)
    }
  }

  private fun clearTaps(press: Press) {
    press.tapRunnable?.let(handler::removeCallbacks)
    press.tapRunnable = null
    press.tapCount = 0
  }

  private fun abort(press: Press) {
    press.aborted = true
    releaseHoldWakeLock(press)
    press.pressRunnable?.let(handler::removeCallbacks)
    press.pressRunnable = null
    press.shortHoldRunnable?.let(handler::removeCallbacks)
    press.shortHoldRunnable = null
    press.longHoldRunnable?.let(handler::removeCallbacks)
    press.longHoldRunnable = null
    press.jumpRunnable?.let(handler::removeCallbacks)
    press.jumpRunnable = null
    clearTaps(press)
  }

  private fun acquireHoldWakeLock(service: TalkBackService) {
    if (holdWakeLock == null) {
      holdWakeLock = service.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Meridian:VolumeKeyHold")
    }
    // Reference-counted: each acquire is balanced by one release in
    // releaseHoldWakeLock. The timeout is a safety net so a missed release
    // can never hold the CPU awake indefinitely.
    holdWakeLock?.acquire(HOLD_WAKE_LOCK_TIMEOUT_MS)
  }

  private fun releaseHoldWakeLock(press: Press) {
    if (!press.wakeLockHeld) return
    press.wakeLockHeld = false
    holdWakeLock?.let { if (it.isHeld) it.release() }
  }

  @JvmStatic
  fun reset() {
    listOf(upPress, downPress).forEach {
      abort(it)
      it.active = false
      it.service = null
    }
  }

  private fun contextChanged(service: TalkBackService, press: Press): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    val locked = isKeyguardLocked(service)
    val screenOff = isScreenOff(service)
    val screenTransitionedFromOff = press.screenOff && !screenOff
    val stateMatches = screenTransitionedFromOff || (locked == press.locked && screenOff == press.screenOff)
    return prefs.getBoolean(KEY_MANAGED, false) != press.managed ||
      (!screenTransitionedFromOff && anyMultiAssigned(prefs, press.isUp, stateOf(locked, screenOff)) != press.multi) ||
      (service.isPhoneCallActive() && !isRingingCallAnsweredBy(service, press.isUp)) ||
      isAlarmRinging(service) ||
      !stateMatches
  }

  internal fun readShortHoldMs(prefs: SharedPreferences): Long {
    val raw = prefs.getString(KEY_SHORT_HOLD_MS, DEFAULT_SHORT_HOLD_MS.toString())?.toLongOrNull()
    return (raw ?: DEFAULT_SHORT_HOLD_MS).coerceIn(500L, 2000L)
  }

  internal fun readLongHoldMs(prefs: SharedPreferences, shortHoldMs: Long = readShortHoldMs(prefs)): Long {
    val raw = prefs.getString(KEY_LONG_HOLD_MS, DEFAULT_LONG_HOLD_MS.toString())?.toLongOrNull()
    val configured = (raw ?: DEFAULT_LONG_HOLD_MS).coerceIn(2000L, 4000L)
    return maxOf(configured, shortHoldMs + MIN_HOLD_GAP_MS)
  }

  private fun vibrateHoldCue(context: Context, isLong: Boolean) {
    try {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
      } ?: return
      if (!vibrator.hasVibrator()) return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effect = if (isLong) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_CLICK
        vibrator.vibrate(VibrationEffect.createPredefined(effect))
      } else {
        vibrator.vibrate(VibrationEffect.createOneShot(if (isLong) 45L else 24L, VibrationEffect.DEFAULT_AMPLITUDE))
      }
    } catch (_: RuntimeException) {
    }
  }

  private fun isKeyguardLocked(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

  private fun isScreenOff(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isInteractive == false

  private fun isAlarmRinging(context: Context): Boolean =
    AudioPlaybackMonitor(context).isPlaybackSourceActive(AudioPlaybackMonitor.PlaybackSource.USAGE_ALARM)

  /**
   * Whether any assigned action of the volume key [isUp] in its current screen state is
   * [command]: the press, short-hold and long-hold actions when volume keys are managed, and
   * the double, triple and quadruple-press actions, which also run when unmanaged.
   */
  fun hasCommand(service: TalkBackService, isUp: Boolean, command: String): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    val state = stateOf(isKeyguardLocked(service), isScreenOff(service))
    if (prefs.getBoolean(KEY_MANAGED, false)) {
      if (prefs.getString(pressKey(isUp, state), ACTION_UNASSIGNED) == command ||
        prefs.getString(shortHoldKey(isUp, state), ACTION_UNASSIGNED) == command ||
        prefs.getString(longHoldKey(isUp, state), ACTION_UNASSIGNED) == command) {
        return true
      }
    }
    return (2..4).any { count ->
      (prefs.getString(multiKey(isUp, count, state), ACTION_UNASSIGNED) ?: ACTION_UNASSIGNED) ==
        command
    }
  }

  /**
   * Whether a call rings and a press of the volume key [isUp] has Answer call assigned, so the
   * key still runs its commands while ringing instead of going to Android. The voice-action
   * monitor is not ready before the service finishes starting, so a missing monitor means no
   * ringing call.
   */
  private fun isRingingCallAnsweredBy(service: TalkBackService, isUp: Boolean): Boolean {
    val callState = try {
      service.getVoiceActionMonitor().getCurrentCallState()
    } catch (e: IllegalStateException) {
      return false
    }
    return callState == TelephonyManager.CALL_STATE_RINGING &&
      hasCommand(service, isUp, ACTION_ANSWER_CALL)
  }

  private fun stateOf(locked: Boolean, screenOff: Boolean): VolumeKeyState = when {
    screenOff -> VolumeKeyState.SCREEN_OFF
    locked -> VolumeKeyState.LOCKED
    else -> VolumeKeyState.UNLOCKED
  }

  private fun stateOf(press: Press): VolumeKeyState = stateOf(press.locked, press.screenOff)

  private fun anyMultiAssigned(prefs: SharedPreferences, isUp: Boolean, state: VolumeKeyState): Boolean =
    (2..4).any { count ->
      (prefs.getString(multiKey(isUp, count, state), ACTION_UNASSIGNED) ?: ACTION_UNASSIGNED) !=
        ACTION_UNASSIGNED
    }

  /** Preference keys whose action picker is restricted to lock-screen-safe actions. */
  fun lockScreenRestrictedKeys(isUp: Boolean): List<String> {
    val keys = mutableListOf<String>()
    for (state in listOf(VolumeKeyState.LOCKED, VolumeKeyState.SCREEN_OFF)) {
      keys += pressKey(isUp, state)
      keys += shortHoldKey(isUp, state)
      keys += longHoldKey(isUp, state)
      for (count in 2..4) keys += multiKey(isUp, count, state)
    }
    return keys
  }

  fun multiKey(isUp: Boolean, count: Int): String = multiKey(isUp, count, VolumeKeyState.UNLOCKED)

  private fun multiKey(isUp: Boolean, count: Int, state: VolumeKeyState): String {
    val suffix = when (state) {
      VolumeKeyState.UNLOCKED -> ""
      VolumeKeyState.LOCKED -> "_locked"
      VolumeKeyState.SCREEN_OFF -> "_screen_off"
    }
    return "pref_volume_${if (isUp) "up" else "down"}_${count}_press${suffix}_action"
  }

  private fun pressKey(isUp: Boolean, state: VolumeKeyState): String = when (state) {
    VolumeKeyState.UNLOCKED -> if (isUp) KEY_UP_PRESS_ACTION else KEY_DOWN_PRESS_ACTION
    VolumeKeyState.LOCKED -> if (isUp) KEY_UP_PRESS_LOCKED_ACTION else KEY_DOWN_PRESS_LOCKED_ACTION
    VolumeKeyState.SCREEN_OFF ->
      if (isUp) KEY_UP_PRESS_SCREEN_OFF_ACTION else KEY_DOWN_PRESS_SCREEN_OFF_ACTION
  }

  private fun shortHoldKey(isUp: Boolean, state: VolumeKeyState): String = when (state) {
    VolumeKeyState.UNLOCKED -> if (isUp) KEY_UP_HOLD_ACTION else KEY_DOWN_HOLD_ACTION
    VolumeKeyState.LOCKED -> if (isUp) KEY_UP_HOLD_LOCKED_ACTION else KEY_DOWN_HOLD_LOCKED_ACTION
    VolumeKeyState.SCREEN_OFF ->
      if (isUp) KEY_UP_HOLD_SCREEN_OFF_ACTION else KEY_DOWN_HOLD_SCREEN_OFF_ACTION
  }

  private fun longHoldKey(isUp: Boolean, state: VolumeKeyState): String = when (state) {
    VolumeKeyState.UNLOCKED -> if (isUp) KEY_UP_HOLD_LONG_ACTION else KEY_DOWN_HOLD_LONG_ACTION
    VolumeKeyState.LOCKED ->
      if (isUp) KEY_UP_HOLD_LONG_LOCKED_ACTION else KEY_DOWN_HOLD_LONG_LOCKED_ACTION
    VolumeKeyState.SCREEN_OFF ->
      if (isUp) KEY_UP_HOLD_LONG_SCREEN_OFF_ACTION else KEY_DOWN_HOLD_LONG_SCREEN_OFF_ACTION
  }

  private fun contextualAction(isUp: Boolean): String =
    if (isUp) ACTION_INCREASE_CONTEXTUAL_VOLUME else ACTION_DECREASE_CONTEXTUAL_VOLUME
  private fun defaultPressAction(isUp: Boolean): String = contextualAction(isUp)
  private fun defaultHoldAction(isUp: Boolean, state: VolumeKeyState): String =
    if (state == VolumeKeyState.LOCKED && isUp) ACTION_TOGGLE_STOPWATCH else ACTION_UNASSIGNED

  @JvmStatic
  fun migrate(context: Context) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (prefs.getBoolean(KEY_MIGRATED, false)) return
    val editor = prefs.edit()
    editor.putBoolean(KEY_MIGRATED, true)
    if (prefs.contains(OLD_KEY_VOLUME_ACTIVATION)) {
      if (prefs.getBoolean(OLD_KEY_VOLUME_ACTIVATION, true)) {
        editor.putBoolean(KEY_MANAGED, true)
      } else {
        editor.putBoolean(KEY_MANAGED, false)
        editor.putString(KEY_UP_HOLD_LOCKED_ACTION, ACTION_UNASSIGNED)
      }
      editor.remove(OLD_KEY_VOLUME_ACTIVATION)
    } else if (isUpgrade(context) && !prefs.contains(KEY_MANAGED)) {
      editor.putBoolean(KEY_MANAGED, true)
    }
    editor.apply()
  }

  @Suppress("DEPRECATION") // The flags overload of getPackageInfo needs API 33.
  private fun isUpgrade(context: Context): Boolean =
    try {
      val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
          )
        } else {
          context.packageManager.getPackageInfo(context.packageName, 0)
        }
      info.lastUpdateTime > info.firstInstallTime
    } catch (e: PackageManager.NameNotFoundException) {
      false
    }
}
