package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.talkback.quicksettings.QuickSettingsItemsFragment;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.Group;
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText;
import java.util.ArrayList;
import java.util.List;

public final class SelectorGroupsFragment extends TalkbackBaseFragment {
  @Override
  public CharSequence getTitle() {
    return getText(R.string.selector_groups_title);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    SelectorGroupManager.ensureInitialized(requireContext());
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    getParentFragmentManager()
        .setFragmentResultListener(
            SelectorGroupScreenUi.RESULT,
            this,
            (requestKey, result) -> {
              refresh();
              String groupId = result.getString(SelectorGroupScreenUi.FOCUS_ID, "");
              String target = groupId.isEmpty() ? "selector_group_create" : "selector_group_" + groupId;
              SelectorGroupScreenUi.focus(
                  this, target, result.getString(SelectorGroupScreenUi.MESSAGE));
            });
    refresh();
  }

  @Override
  public void onResume() {
    super.onResume();
    SelectorGroupManager.ensureInitialized(requireContext());
    refresh();
  }

  private void refresh() {
    Context context = requireContext();
    PreferenceScreen screen = getPreferenceScreen();
    screen.removeAll();

    Preference add = new Preference(context);
    add.setKey("selector_group_create");
    add.setTitle(R.string.sgm_add_group);
    add.setIconSpaceReserved(false);
    add.setOnPreferenceClickListener(preference -> {
      SelectorGroupScreenUi.open(this, new SelectorGroupCreateFragment(), new Bundle());
      return true;
    });
    screen.addPreference(CreateNewShortcut.mark(add));

    Preference customControls = new Preference(context);
    customControls.setKey("selector_custom_reading_controls");
    customControls.setTitle(R.string.custom_reading_controls_title);
    customControls.setSummary(R.string.custom_reading_controls_summary);
    customControls.setIconSpaceReserved(false);
    customControls.setOnPreferenceClickListener(preference -> {
      SelectorGroupScreenUi.open(this, new CustomReadingControlsFragment(), new Bundle());
      return true;
    });
    screen.addPreference(customControls);

    PreferenceCategory groupsCategory = new PreferenceCategory(context);
    groupsCategory.setKey("selector_navigation_groups");
    groupsCategory.setTitle(R.string.selector_groups_title);
    screen.addPreference(groupsCategory);

    List<Group> allGroups = SelectorGroupManager.getGroups(context);
    for (int i = 0; i < allGroups.size(); i++) {
      Group group = allGroups.get(i);
      int index = i;
      int total = allGroups.size();
      Preference row = new ActionablePreference(context, () -> {
        List<PreferenceCustomActions.Entry> actions = new ArrayList<>();
        if (!group.enabled || SelectorGroupManager.canDisableGroup(context, group.id)) {
          actions.add(new PreferenceCustomActions.Entry(
              getString(group.enabled ? R.string.notification_off : R.string.notification_on),
              () -> {
                toggleGroupEnabled(group.id, !group.enabled);
                return kotlin.Unit.INSTANCE;
              }));
        }
        actions.add(new PreferenceCustomActions.Entry(
            getString(R.string.sgm_rename),
            () -> {
              showRenameDialog(group);
              return kotlin.Unit.INSTANCE;
            }));
        if (index > 0) {
          actions.add(new PreferenceCustomActions.Entry(
              getString(R.string.sgm_move_up),
              () -> {
                SelectorGroupManager.moveGroup(context, group.id, -1);
                refresh();
                return kotlin.Unit.INSTANCE;
              }));
        }
        if (index < total - 1) {
          actions.add(new PreferenceCustomActions.Entry(
              getString(R.string.sgm_move_down),
              () -> {
                SelectorGroupManager.moveGroup(context, group.id, 1);
                refresh();
                return kotlin.Unit.INSTANCE;
              }));
        }
        if (allGroups.size() > 1 && SelectorGroupManager.canDisableGroup(context, group.id)) {
          actions.add(new PreferenceCustomActions.Entry(
              getString(R.string.sgm_delete),
              () -> {
                confirmDeleteGroup(group);
                return kotlin.Unit.INSTANCE;
              }));
        }
        return actions;
      });
      row.setKey("selector_group_" + group.id);
      row.setTitle(group.name);
      row.setSummary(
          getString(
              R.string.sgm_group_summary,
              group.items.size(),
              getString(
                  group.enabled
                      ? R.string.sgm_on
                      : R.string.sgm_off)));
      row.setIconSpaceReserved(false);
      row.setOnPreferenceClickListener(preference -> {
        Bundle args = new Bundle();
        args.putString(SelectorGroupScreenUi.GROUP_ID, group.id);
        SelectorGroupScreenUi.open(this, new SelectorGroupItemsFragment(), args);
        return true;
      });
      groupsCategory.addPreference(row);
    }

    PreferenceCategory options = new PreferenceCategory(context);
    options.setKey("selector_group_options");
    options.setTitle(R.string.selector_group_options);
    screen.addPreference(options);
    addExistingSwitch(
        options,
        R.string.pref_selector_auto_switch_key,
        R.bool.pref_selector_auto_switch_default,
        R.string.title_pref_selector_auto_switch,
        0);
    addExistingSwitch(
        options,
        R.string.pref_selection_mode_reading_control_key,
        R.bool.pref_selection_mode_reading_control_default,
        R.string.title_pref_selection_mode_reading_control,
        R.string.summary_pref_selection_mode_reading_control);
    addExistingSwitch(
        options,
        R.string.pref_selector_links_controls_web_only_key,
        R.bool.pref_selector_links_controls_web_only_default,
        R.string.title_pref_selector_links_controls_web_only,
        0);

    PreferenceCategory menus = new PreferenceCategory(context);
    menus.setKey("selector_related_menus");
    menus.setTitle(R.string.title_pref_category_context_menu);
    screen.addPreference(menus);

    Preference contextMenu = new Preference(context);
    contextMenu.setKey(getString(R.string.pref_category_manage_context_menu_key));
    contextMenu.setTitle(R.string.title_pref_category_context_menu);
    contextMenu.setIconSpaceReserved(false);
    contextMenu.setFragment(ContextMenuFragment.class.getName());
    menus.addPreference(contextMenu);

    Preference customMenus = new Preference(context);
    customMenus.setKey("pref_custom_menus_screen");
    customMenus.setTitle(R.string.custom_menus_title);
    customMenus.setIconSpaceReserved(false);
    customMenus.setFragment(CustomMenusFragment.class.getName());
    menus.addPreference(customMenus);

    Preference quickSettings = new Preference(context);
    quickSettings.setKey("pref_quick_settings_items_screen");
    quickSettings.setTitle(R.string.title_pref_quick_settings_items);
    quickSettings.setIconSpaceReserved(false);
    quickSettings.setFragment(QuickSettingsItemsFragment.class.getName());
    menus.addPreference(quickSettings);
  }

  private void toggleGroupEnabled(String groupId, boolean newValue) {
    Context context = requireContext();
    if (!newValue && !SelectorGroupManager.canDisableGroup(context, groupId)) {
      Toast.makeText(context, R.string.sgm_minimum_enabled, Toast.LENGTH_SHORT).show();
      return;
    }
    ArrayList<Group> updated = new ArrayList<>();
    for (Group item : SelectorGroupManager.getGroups(context)) {
      Group copy = item.copy();
      if (copy.id.equals(groupId)) {
        copy.enabled = newValue;
      }
      updated.add(copy);
    }
    if (SelectorGroupManager.saveGroups(context, updated)) {
      refresh();
    } else {
      Toast.makeText(context, R.string.sgm_save_failed, Toast.LENGTH_SHORT).show();
    }
  }

  private void showRenameDialog(Group group) {
    Context context = requireContext();
    KeyboardSafeEditText field = new KeyboardSafeEditText(context);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setSingleLine(true);
    field.setHint(R.string.sgm_group_name);
    field.setText(group.name);
    field.setSelection(field.length());
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(R.string.sgm_rename)
            .setView(field)
            .setPositiveButton(R.string.sgm_save, null)
            .setNegativeButton(R.string.sgm_cancel, null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      String name = field.getText().toString().trim();
                      if (name.isEmpty()) {
                        field.setError(getString(R.string.sgm_name_required));
                        return;
                      }
                      if (SelectorGroupManager.isNameTaken(context, name, group.id)) {
                        field.setError(getString(R.string.sgm_name_exists));
                        return;
                      }
                      ArrayList<Group> updated = new ArrayList<>();
                      for (Group item : SelectorGroupManager.getGroups(context)) {
                        Group copy = item.copy();
                        if (copy.id.equals(group.id)) {
                          copy.name = name;
                        }
                        updated.add(copy);
                      }
                      if (SelectorGroupManager.saveGroups(context, updated)) {
                        dialog.dismiss();
                        refresh();
                      } else {
                        Toast.makeText(context, R.string.sgm_save_failed, Toast.LENGTH_SHORT).show();
                      }
                    }));
    dialog.show();
  }

  private void confirmDeleteGroup(Group group) {
    Context context = requireContext();
    new AlertDialog.Builder(context)
        .setTitle(R.string.sgm_delete)
        .setMessage(getString(R.string.voice_profile_delete_confirm, group.name))
        .setPositiveButton(R.string.sgm_delete, (dialog, which) -> {
          SelectorGroupManager.removeGroup(context, group.id);
          refresh();
        })
        .setNegativeButton(R.string.sgm_cancel, null)
        .show();
  }

  private void addExistingSwitch(
      PreferenceCategory category,
      int keyResId,
      int defaultResId,
      int titleResId,
      int summaryResId) {
    Context context = requireContext();
    SwitchPreferenceCompat preference = new SwitchPreferenceCompat(context);
    preference.setKey(getString(keyResId));
    preference.setDefaultValue(context.getResources().getBoolean(defaultResId));
    preference.setTitle(titleResId);
    if (summaryResId != 0) {
      preference.setSummary(summaryResId);
    }
    category.addPreference(preference);
  }
}
