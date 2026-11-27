package com.google.android.accessibility.talkback.commands

import android.app.Application
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.EdgeGestureActions
import com.google.android.accessibility.talkback.gesture.MenuGestureActions
import com.google.android.accessibility.talkback.gesture.NavigationGestureActions
import com.google.android.accessibility.talkback.keyboard.TalkBackPhysicalKeyboardShortcut as Key
import com.google.android.accessibility.talkback.preference.base.GestureListPreference
import com.google.android.accessibility.talkback.recognition.RecognitionMode
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class CommandCatalogCoverageTest {
  private val context: Application = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    FormFactorUtils.initialize(context)
  }

  @Test
  fun commonCatalogContainsEveryAssignableProviderCommand() {
    val values = Commands.categories(context).flatMap { it.commands }.map { it.value }.toSet()
    val expected = buildSet {
      addAll(GestureListPreference.getAssignableGestureActions(context).keys)
      addAll(EdgeGestureActions.Action.entries.map { context.getString(it.value) })
      addAll(NavigationGestureActions.CATEGORIES.flatMap { it.actions }.map { it.value })
      addAll(MenuGestureActions.ENTRIES.map { it.value(context) })
      addAll(RecognitionMode.entries.map(ExtendedCommandActions::liveRecognitionModeAction))
      addAll(ExtendedCommandActions.QUICK_DEVICE_CONTROLS.map(ExtendedCommandActions::quickControlAction))
      add(ExtendedCommandActions.ACTION_LIVE_RECOGNITION_OPEN)
      add(ExtendedCommandActions.ACTION_TOGGLE_DIRECT_TOUCH)
      add(ExtendedCommandActions.ACTION_OPEN_NOTIFICATION_LIST)
      add(ExtendedCommandActions.ACTION_OPEN_NOTIFICATION_HISTORY)
      add(ExtendedCommandActions.ACTION_TOGGLE_BACK_TAP)
      remove(context.getString(R.string.shortcut_value_unassigned))
    }
    assertTrue("Missing commands: ${expected - values}", values.containsAll(expected))
  }

  @Test
  fun restoredCommandsHaveOneEntryInTheirFunctionalCategory() {
    val categories = Commands.categoriesWithoutApps(context)
    for ((value, category) in listOf(
      R.string.edge_gesture_value_previous_sentence to R.string.shortcut_title_navigate_by_text,
      R.string.edge_gesture_value_next_sentence to R.string.shortcut_title_navigate_by_text,
      R.string.shortcut_value_add_to_clipboard to R.string.commands_category_clipboard_copy,
      R.string.shortcut_value_read_current_time to R.string.commands_category_clock_timer,
    )) {
      val commandValue = context.getString(value)
      assertEquals(1, categories.flatMap { it.commands }.count { it.value == commandValue })
      assertTrue(categories.single { it.titleRes == category }.commands.any { it.value == commandValue })
      assertNotNull(Commands.find(context, commandValue))
    }
  }

  @Test
  fun existingKeyboardActionsResolveToTheSameCommonCommands() {
    for ((keyboard, value) in listOf(
      Key.SELECT_PREVIOUS_READING_GROUP to R.string.shortcut_value_select_previous_group,
      Key.SELECT_NEXT_READING_GROUP to R.string.shortcut_value_select_next_group,
      Key.ANNOUNCE_CURRENT_TIME_AND_DATE to R.string.shortcut_value_announce_current_time_and_date,
      Key.TRANSLATE_LAST_SPOKEN_PHRASE to R.string.shortcut_value_translate_last_spoken_phrase,
    )) {
      val commandValue = context.getString(value)
      assertEquals(commandValue, Commands.valueOfKeyboardAction(context, keyboard))
      assertEquals(keyboard, Commands.find(context, commandValue)?.keyboardAction)
      assertEquals(keyboard.getKey(context.resources), Commands.find(context, commandValue)?.keyboardKey(context))
    }
  }
}
