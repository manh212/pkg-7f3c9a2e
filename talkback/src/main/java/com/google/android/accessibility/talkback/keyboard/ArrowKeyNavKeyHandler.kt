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

package com.google.android.accessibility.talkback.keyboard

import android.content.Context
import android.view.KeyEvent

class ArrowKeyNavKeyHandler(private val context: Context, private val callbacks: Callbacks) {

  interface Callbacks {

    fun perform(command: ArrowKeyNavCommand)

    fun isEditingText(): Boolean
  }

  private val heldKeys = LinkedHashMap<Int, Long>()

  private val consumedKeys = HashSet<Int>()

  private val pendingCommands = ArrayDeque<Pair<Int, ArrowKeyNavCommand>>()

  fun reset() {
    heldKeys.clear()
    consumedKeys.clear()
    pendingCommands.clear()
  }

  fun onKeyEvent(event: KeyEvent, canAct: Boolean): Boolean {
    val keyCode = event.keyCode
    if (keyCode == KeyEvent.KEYCODE_UNKNOWN || KeyEvent.isModifierKey(keyCode)) {
      return false
    }
    return when (event.action) {
      KeyEvent.ACTION_DOWN -> onKeyDown(event, canAct)
      KeyEvent.ACTION_UP -> onKeyUp(keyCode)
      else -> keyCode in consumedKeys
    }
  }

  fun onKeyEventConsumedElsewhere(event: KeyEvent) {
    if (event.action == KeyEvent.ACTION_UP) {
      heldKeys.remove(event.keyCode)
      consumedKeys.remove(event.keyCode)
      pendingCommands.removeAll { it.first == event.keyCode }
    }
  }

  private fun onKeyDown(event: KeyEvent, canAct: Boolean): Boolean {
    val keyCode = event.keyCode
    if (event.repeatCount > 0) {
      return keyCode in consumedKeys
    }

    heldKeys.entries.removeIf { event.downTime - it.value > STALE_KEY_MILLIS }
    val otherKey = heldKeys.keys.lastOrNull { it != keyCode }
    heldKeys[keyCode] = event.downTime
    if (!canAct) {
      return false
    }

    val on = ArrowKeyNav.isOn(context)
    val modifiers = event.modifiers and KeyCombo.KEY_EVENT_MODIFIER_MASK
    if (otherKey != null) {
      val command = ArrowKeyNav.commandFor(context, ArrowKeyNav.keys(modifiers, otherKey, keyCode))
      if (command != null && (on || command == ArrowKeyNavCommand.TOGGLE)) {

        pendingCommands.clear()
        consumedKeys += keyCode
        callbacks.perform(command)
        return true
      }
    }

    val command =
      ArrowKeyNav.commandFor(context, ArrowKeyNav.keys(modifiers, keyCode)) ?: return false
    if (command != ArrowKeyNavCommand.TOGGLE && (!on || callbacks.isEditingText())) {
      return false
    }
    consumedKeys += keyCode
    if (on && startsTwoKeyCommand(modifiers, keyCode)) {
      pendingCommands.removeAll { it.first == keyCode }
      pendingCommands.addLast(keyCode to command)
    } else {
      callbacks.perform(command)
    }
    return true
  }

  private fun onKeyUp(keyCode: Int): Boolean {
    heldKeys.remove(keyCode)
    if (!consumedKeys.remove(keyCode)) {
      return false
    }
    pendingCommands.firstOrNull { it.first == keyCode }?.let {
      pendingCommands.remove(it)
      callbacks.perform(it.second)
    }
    return true
  }

  private fun startsTwoKeyCommand(modifiers: Int, keyCode: Int): Boolean =
    ArrowKeyNavCommand.entries.any {
      val keys = ArrowKeyNav.keysFor(context, it)
      val prefix = KeyCombo.extractPrefixKeyCode(keys)
      keys != ArrowKeyNav.UNASSIGNED &&
        prefix != KeyEvent.KEYCODE_UNKNOWN &&
        KeyCombo.extractModifiers(keys) == modifiers &&
        (prefix == keyCode || KeyCombo.extractKeyCode(keys) == keyCode)
    }

  private companion object {
    const val STALE_KEY_MILLIS = 30_000L
  }
}
