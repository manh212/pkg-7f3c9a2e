package com.google.android.accessibility.talkback.commands

import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.keyboard.TalkBackPhysicalKeyboardShortcut as Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class ContextualVolumeAndGboardCommandsTest {

  @Test
  fun contextualVolumeCommandsAreSeparateInMediaCategoryWithContextualLabels() {
    val context = RuntimeEnvironment.getApplication()
    val mediaCategory =
      Commands.categoriesWithoutApps(context).firstOrNull {
        it.titleRes == R.string.commands_category_media
      }
    assertNotNull(mediaCategory)
    val mediaCommands = mediaCategory!!.commands

    val inc =
      mediaCommands.firstOrNull {
        it.value == context.getString(R.string.shortcut_value_increase_contextual_volume)
      }
    val dec =
      mediaCommands.firstOrNull {
        it.value == context.getString(R.string.shortcut_value_decrease_contextual_volume)
      }
    assertNotNull(inc)
    assertNotNull(dec)
    assertEquals("Tăng âm lượng theo ngữ cảnh", inc!!.label)
    assertEquals("Giảm âm lượng theo ngữ cảnh", dec!!.label)
  }

  @Test
  fun gboardVoiceInputAndCombinedMediaCommandAreRemovedFromCommandsList() {
    val context = RuntimeEnvironment.getApplication()
    val allCommands = Commands.categoriesWithoutApps(context).flatMap { it.commands }
    val allValues = allCommands.map { it.value }.toSet()

    assertFalse(allValues.contains(context.getString(R.string.shortcut_value_voice_input)))
    assertFalse(
      allValues.contains(context.getString(R.string.shortcut_value_media_control_or_voice_input))
    )

    val mediaControl =
      allCommands.firstOrNull {
        it.value == context.getString(R.string.shortcut_value_media_control)
      }
    assertNotNull(mediaControl)
    assertEquals(Key.PLAY_PAUSE_MEDIA, mediaControl!!.keyboardAction)
    assertTrue(allValues.contains(context.getString(R.string.shortcut_value_dictate)))
  }
}
