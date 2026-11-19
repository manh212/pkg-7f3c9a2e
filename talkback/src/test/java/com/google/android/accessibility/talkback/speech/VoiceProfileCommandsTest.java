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

package com.google.android.accessibility.talkback.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.fragment.app.FragmentActivity;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.commands.Commands;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Tests the "Use voice profile" commands, one per voice profile, ported from SandFly
 * commit 1554d5238: the command values in {@link VoiceProfileNames}, their names in
 * {@link Commands}, and the "Voice profiles" command group, which only shows while at
 * least one profile exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VoiceProfileCommandsTest {

  private ActivityController<FragmentActivity> controller;
  private FragmentActivity activity;
  private Context context;
  private SharedPreferences prefs;

  @Before
  public void setUp() {
    controller = Robolectric.buildActivity(FragmentActivity.class);
    activity = controller.get();
    activity.setTheme(R.style.TalkBackSettings);
    FormFactorUtils.initialize(activity);
    context = activity;
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    controller.setup();
  }

  @After
  public void tearDown() {
    prefs.edit().clear().commit();
    controller.pause().stop().destroy();
  }

  @Test
  public void commandValues_roundTripThroughPrefix() {
    assertEquals("VOICE_PROFILE:", VoiceProfileNames.commandOf(""));
    assertEquals("VOICE_PROFILE:1", VoiceProfileNames.commandOf("1"));
    assertTrue(VoiceProfileNames.isCommand(VoiceProfileNames.commandOf("")));
    assertTrue(VoiceProfileNames.isCommand(VoiceProfileNames.commandOf("1")));
    assertEquals("", VoiceProfileNames.idOfCommand(VoiceProfileNames.commandOf("")));
    assertEquals("1", VoiceProfileNames.idOfCommand(VoiceProfileNames.commandOf("1")));
  }

  @Test
  public void isCommand_rejectsOtherValues() {
    assertFalse(VoiceProfileNames.isCommand(null));
    assertFalse(VoiceProfileNames.isCommand(""));
    assertFalse(VoiceProfileNames.isCommand("VOICE_PROFILE"));
    assertFalse(VoiceProfileNames.isCommand("GESTURE_PROFILE:1"));
  }

  @Test
  public void commandLabel_defaultProfile_usesDefaultName() {
    String expected =
        context.getString(
            R.string.shortcut_use_voice_profile,
            context.getString(R.string.voice_profile_default));
    assertEquals(expected, VoiceProfileNames.commandLabel(context, prefs, "VOICE_PROFILE:"));
  }

  @Test
  public void commandLabel_existingProfile_usesItsName() {
    addProfile("1", "Reading");
    assertEquals(
        context.getString(R.string.shortcut_use_voice_profile, "Reading"),
        VoiceProfileNames.commandLabel(context, prefs, "VOICE_PROFILE:1"));
  }

  @Test
  public void commandLabel_deletedProfile_returnsNull() {
    assertNull(VoiceProfileNames.commandLabel(context, prefs, "VOICE_PROFILE:9"));
  }

  @Test
  public void commandLabel_nonCommand_returnsNull() {
    assertNull(VoiceProfileNames.commandLabel(context, prefs, "GESTURE_PROFILE:1"));
    assertNull(VoiceProfileNames.commandLabel(context, prefs, null));
  }

  @Test
  public void findAndLabel_resolveProfileCommand() {
    addProfile("1", "Reading");
    String value = VoiceProfileNames.commandOf("1");
    String expected = context.getString(R.string.shortcut_use_voice_profile, "Reading");
    Commands.Command command = Commands.find(context, value);
    assertNotNull(command);
    assertEquals(value, command.getValue());
    assertEquals(expected, command.getLabel());
    assertEquals(expected, Commands.label(context, value));
  }

  @Test
  public void findAndLabel_defaultCommand() {
    addProfile("1", "Reading");
    String value = VoiceProfileNames.commandOf("");
    String expected =
        context.getString(
            R.string.shortcut_use_voice_profile,
            context.getString(R.string.voice_profile_default));
    assertNotNull(Commands.find(context, value));
    assertEquals(expected, Commands.label(context, value));
  }

  @Test
  public void find_deletedProfile_returnsNull() {
    assertNull(Commands.find(context, "VOICE_PROFILE:9"));
    assertNull(Commands.label(context, "VOICE_PROFILE:9"));
  }

  @Test
  public void categories_listsDefaultThenProfiles() {
    addProfile("1", "Reading");
    Commands.Category group = voiceProfilesGroup(Commands.categories(context));
    assertNotNull("Voice profiles group is missing", group);
    assertEquals(R.string.title_pref_voice_profiles, group.getTitleRes());
    List<String> values = group.getCommands().stream().map(Commands.Command::getValue).toList();
    assertEquals(
        List.of(VoiceProfileNames.commandOf(""), VoiceProfileNames.commandOf("1")), values);
  }

  @Test
  public void categories_withoutProfiles_hasNoGroup() {
    assertNull(voiceProfilesGroup(Commands.categories(context)));
  }

  private Commands.Category voiceProfilesGroup(List<Commands.Category> categories) {
    for (Commands.Category category : categories) {
      if (category.getTitleRes() == R.string.title_pref_voice_profiles) {
        return category;
      }
    }
    return null;
  }

  private void addProfile(String id, String name) {
    prefs
        .edit()
        .putString(VoiceProfiles.PREF_IDS, id)
        .putString(VoiceProfiles.key(id, VoiceProfiles.NAME), name)
        .commit();
  }
}
