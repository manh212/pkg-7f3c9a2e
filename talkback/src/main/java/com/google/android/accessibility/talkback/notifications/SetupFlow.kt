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

package com.google.android.accessibility.talkback.notifications

import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks.Status

/**
 * Where the initial setup stands in its walk, kept apart from the screen so the walk itself
 * can be tested. There is no button between one permission and the next: dealing with a step
 * advances only after the system confirms a grant; denied steps remain visible until explicitly
 * skipped. A step whose grant cannot be checked advances when the walk returns from its page
 * all the same, and choosing the language moves on at once. Steps already granted are passed
 * over, and the end of the walk is the summary.
 */
class SetupFlow {
  /** The step showing, or the number of steps when the walk stands at its summary. */
  var step = 0
    private set

  /** Whether the step showing was opened from the summary, to be gone back to. */
  var reviewing = false
    private set

  private var openTapped = false
  private var wentAway = false

  /** Passes over the steps whose state is already granted. Never moves a step being reviewed. */
  fun settle(statuses: List<Status>) {
    if (reviewing) return
    while (step < statuses.size && statuses[step] == Status.GRANTED) {
      step++
    }
  }

  /** The step's own ask was tapped: a dialog or a system page is on its way. */
  fun tapOpen() {
    openTapped = true
  }

  fun completeOpen(stepCount: Int) {
    if (!openTapped) return
    openTapped = false
    wentAway = false
    if (reviewing) {
      reviewing = false
      step = stepCount
    } else step++
  }

  fun leaveScreen() {
    if (openTapped) wentAway = true
  }

  /**
   * Returning from system settings only advances when the permission is actually granted, or
   * when its grant cannot be checked at all. A denied request leaves the step visible for
   * another try or an explicit Skip.
   */
  fun returnToScreen(stepCount: Int, granted: Boolean, unverifiable: Boolean = false) {
    val advance = openTapped && wentAway && (granted || unverifiable)
    openTapped = false
    wentAway = false
    if (!advance) return
    if (reviewing) {
      reviewing = false
      step = stepCount
    } else {
      step++
    }
  }

  /** A language was chosen: the walk moves on at once, with no pending open surviving it. */
  fun chooseLanguage(stepCount: Int) {
    openTapped = false
    wentAway = false
    if (reviewing) {
      reviewing = false
      step = stepCount
    } else step++
  }

  /** Passes the step showing by, at once. */
  fun skip() {
    step++
  }

  /** Opens the step at [index] out of the summary. */
  fun review(index: Int) {
    step = index
    reviewing = true
  }

  /** Goes back to the summary from a step opened out of it. */
  fun backToSummary(stepCount: Int) {
    reviewing = false
    step = stepCount
  }

  /** Starts the walk over, as when the setup is left and opened again. */
  fun finish() {
    step = 0
    reviewing = false
    openTapped = false
    wentAway = false
  }

  /** Whether the walk stands at its summary. */
  fun atSummary(stepCount: Int): Boolean = !reviewing && step >= stepCount
}
