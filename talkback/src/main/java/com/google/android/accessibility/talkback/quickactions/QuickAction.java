/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.quickactions;

import androidx.annotation.ArrayRes;
import androidx.annotation.StringRes;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One settings quick action: a section's most-used setting, offered in the Actions reading
 * control. An action is one press: a toggle flips in place, a list cycles to its next value, a
 * slider lands focus on its row, an opener opens its feature. Nothing here ever resets or
 * reseeds a current value.
 */
public final class QuickAction {

  public enum Kind {
    /** Flip a boolean preference in place and speak the new state. */
    TOGGLE,
    /** Advance a list preference by one value, wrapping, and speak the new entry. */
    CYCLE_LIST,
    /** Open the owning sub-screen and land focus on the live slider row. */
    FOCUS_SLIDER,
    /** Open a feature. */
    OPEN,
  }

  public final QuickActionSections.Section section;
  public final @StringRes int titleResId;
  public final Kind kind;
  /** Preference key for TOGGLE and CYCLE_LIST; null otherwise. */
  public final @Nullable String prefKey;
  /** Entry labels for CYCLE_LIST from a resource array; 0 when the entries come from code. */
  public final @ArrayRes int entriesResId;
  /** Entry values for CYCLE_LIST from a resource array; 0 when the values come from code. */
  public final @ArrayRes int valuesResId;
  /**
   * Special-case id for the performer (a code-built list, a slider, an opener); null for the
   * generic paths.
   */
  public final @Nullable String performerId;
  /** Current value a TOGGLE reads when the preference was never set. */
  public final boolean toggleDefault;
  /** Current value a resource-array CYCLE_LIST reads when the preference was never set. */
  public final @Nullable String cycleDefault;

  private QuickAction(
      QuickActionSections.Section section,
      @StringRes int titleResId,
      Kind kind,
      @Nullable String prefKey,
      @ArrayRes int entriesResId,
      @ArrayRes int valuesResId,
      @Nullable String performerId,
      boolean toggleDefault,
      @Nullable String cycleDefault) {
    this.section = section;
    this.titleResId = titleResId;
    this.kind = kind;
    this.prefKey = prefKey;
    this.entriesResId = entriesResId;
    this.valuesResId = valuesResId;
    this.performerId = performerId;
    this.toggleDefault = toggleDefault;
    this.cycleDefault = cycleDefault;
  }

  public static QuickAction toggle(
      QuickActionSections.Section section,
      @StringRes int titleResId,
      String prefKey,
      boolean defaultValue) {
    return new QuickAction(
        section, titleResId, Kind.TOGGLE, prefKey, 0, 0, null, defaultValue, null);
  }

  public static QuickAction cycleList(
      QuickActionSections.Section section,
      @StringRes int titleResId,
      String prefKey,
      @ArrayRes int entriesResId,
      @ArrayRes int valuesResId,
      String defaultValue) {
    return new QuickAction(
        section,
        titleResId,
        Kind.CYCLE_LIST,
        prefKey,
        entriesResId,
        valuesResId,
        null,
        false,
        defaultValue);
  }

  /** A list whose entries and values come from code, handled by {@code performerId}. */
  public static QuickAction cycleSpecial(
      QuickActionSections.Section section,
      @StringRes int titleResId,
      String prefKey,
      String performerId) {
    return new QuickAction(
        section, titleResId, Kind.CYCLE_LIST, prefKey, 0, 0, performerId, false, null);
  }

  /** A toggle whose flip needs a special path, handled by {@code performerId}. */
  public static QuickAction toggleSpecial(
      QuickActionSections.Section section,
      @StringRes int titleResId,
      String prefKey,
      boolean defaultValue,
      String performerId) {
    return new QuickAction(
        section, titleResId, Kind.TOGGLE, prefKey, 0, 0, performerId, defaultValue, null);
  }

  public static QuickAction focusSlider(
      QuickActionSections.Section section, @StringRes int titleResId, String performerId) {
    return new QuickAction(
        section, titleResId, Kind.FOCUS_SLIDER, null, 0, 0, performerId, false, null);
  }

  public static QuickAction open(
      QuickActionSections.Section section, @StringRes int titleResId, String performerId) {
    return new QuickAction(
        section, titleResId, Kind.OPEN, null, 0, 0, performerId, false, null);
  }
}
