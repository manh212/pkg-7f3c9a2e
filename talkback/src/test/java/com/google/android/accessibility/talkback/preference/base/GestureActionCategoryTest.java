package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.commands.Commands;
import com.google.android.accessibility.talkback.gesture.MenuGestureActions;
import com.google.android.accessibility.utils.FormFactorUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "vi")
public class GestureActionCategoryTest {
  private Context context;
  private GestureListPreference.ActionItem[] actions;
  private Map<String, String> categoryByAction;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    FormFactorUtils.initialize(context);
    actions = new GestureListPreference(context, null).getActionItems();
    categoryByAction = new HashMap<>();
    String category = "";
    for (GestureListPreference.ActionItem action : actions) {
      if (action.viewType == GestureListPreference.TYPE_TITLE) {
        category = action.text;
      } else {
        categoryByAction.put(action.value, category);
      }
    }
  }

  @Test
  public void specialActionsHaveFocusedCategories() {
    assertCategory(R.string.shortcut_value_auto_scroll, R.string.shortcut_title_basic_navigation);
    assertCategory(R.string.shortcut_value_read_current_location, R.string.gesture_group_notifications_status);
    assertCategory(R.string.shortcut_value_correct_text, R.string.shortcut_title_text_editing);
    assertCategory(R.string.shortcut_value_copy_focused, R.string.gesture_group_clipboard);
    assertCategory(R.string.shortcut_value_copy_more, R.string.gesture_group_clipboard);
    assertCategory(R.string.shortcut_value_clear_all_text, R.string.shortcut_title_text_editing);
    assertCategory(R.string.shortcut_value_show_custom_actions, R.string.shortcut_title_menu_control);
    assertCategory(R.string.shortcut_value_favorites, R.string.gesture_group_menu_text);
    assertCategory(R.string.shortcut_value_screen_recognition, R.string.gesture_group_screen_recognition);
    assertCategory(R.string.shortcut_value_subtitles, R.string.gesture_group_screen_recognition);
    assertCategory(R.string.shortcut_value_increase_volume, R.string.gesture_group_audio_media);
    assertCategory(R.string.shortcut_value_media_next, R.string.gesture_group_audio_media);
    assertCategory(R.string.shortcut_value_read_notifications, R.string.gesture_group_notifications_status);
    assertCategory(R.string.shortcut_value_monitor_focus, R.string.gesture_group_touch_accessibility);
    assertCategory(R.string.shortcut_value_task_assistant, R.string.gesture_group_utilities);
    assertCategory(R.string.shortcut_value_smart_context_gesture_1, R.string.smart_context_title);
    assertCategory(R.string.shortcut_value_smart_context_gesture_2, R.string.smart_context_title);
  }

  @Test
  public void menuActionsAreAssignedToSmallerGroupsWithoutLosingTheirValues() {
    String navigation = context.getString(R.string.gesture_group_menu_navigation);
    String feedback = context.getString(R.string.gesture_group_menu_speech);
    assertEquals(navigation, categoryByAction.get("MENU_ACTION:typo_suggestions_menu"));
    assertEquals(feedback, categoryByAction.get("MENU_ACTION:audio_ducking"));
    for (MenuGestureActions.Entry entry : MenuGestureActions.ENTRIES) {
      assertNotNull(context.getString(entry.label), categoryByAction.get(entry.value(context)));
    }
  }

  @Test
  public void otherIsSmallAndGroupLabelsAreUnique() {
    String other = context.getString(R.string.shortcut_title_special_features);
    int otherCount = 0;
    Set<String> titles = new HashSet<>();
    for (GestureListPreference.ActionItem action : actions) {
      if (action.viewType == GestureListPreference.TYPE_TITLE) {
        assertTrue(action.text, titles.add(action.text));
      }
      if (other.equals(categoryByAction.get(action.value))
          && action.viewType == GestureListPreference.TYPE_ACTION_ITEM) {
        otherCount++;
      }
    }
    assertTrue("Other has " + otherCount + " actions", otherCount <= 4);
  }

  @Test
  public void unifiedCommandsKeepWebHeadingsAndOtherKnownActionsOutOfOther() {
    Map<String, Integer> groups = new HashMap<>();
    List<String> other = new java.util.ArrayList<>();
    for (Commands.Category category : Commands.categories(context)) {
      for (Commands.Command command : category.getCommands()) {
        groups.put(command.getValue(), category.getTitleRes());
        if (category.getTitleRes() == R.string.shortcut_title_special_features) {
          other.add(command.getValue() + ": " + command.getLabel());
        }
      }
    }
    for (int level = 1; level <= 6; level++) {
      assertEquals(Integer.valueOf(R.string.commands_category_web), groups.get("NEXT_HEADING_" + level));
      assertEquals(Integer.valueOf(R.string.commands_category_web), groups.get("PREVIOUS_HEADING_" + level));
    }
    for (String value : new String[] {"NEXT_LANDMARK", "PREVIOUS_TABLE", "NEXT_LIST", "PREVIOUS_VISITED_LINK"}) {
      assertEquals(value, Integer.valueOf(R.string.commands_category_web), groups.get(value));
    }
    assertTrue("Uncategorized commands: " + other, other.isEmpty());
  }

  @Test
  public void allGestureActionsAreAvailableForKeyboardShortcuts() {
    Set<String> available = new HashSet<>();
    for (Commands.Category category : Commands.categories(context)) {
      for (Commands.Command command : category.getCommands()) {
        available.add(command.getValue());
      }
    }
    String unassigned = context.getString(R.string.shortcut_value_unassigned);
    for (GestureListPreference.ActionItem item : actions) {
      if (item.viewType == GestureListPreference.TYPE_ACTION_ITEM
          && !item.value.equals(unassigned)) {
        assertTrue("Missing keyboard command: " + item.value, available.contains(item.value));
      }
    }
  }

  private void assertCategory(int value, int category) {
    assertEquals(context.getString(category), categoryByAction.get(context.getString(value)));
  }
}
