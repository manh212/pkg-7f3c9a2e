/*
 * Copyright (C) 2024 Google Inc.
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
package com.google.android.accessibility.talkback.preference.base;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import android.util.AttributeSet;
import androidx.preference.ListPreference;
import androidx.preference.ListPreferenceDialogFragmentCompat;
import com.google.android.accessibility.talkback.R;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Speak punctuation verbosity preference. The user can single-select the verbosity level by all,
 * most or some.
 *
 * <ul>
 *   <li>All: has high verbosity that TalkBack would announce all the defined symbols and
 *       punctuation,
 *   <li>Most: has medium verbosity,
 *   <li>Some: will let TTS engine handle speaking punctuation and symbol,
 * </ul>
 */
public class PunctuationListPreference extends ListPreference {

  public static final String VERBOSITY_WEB_ADDRESS =
      "https://support.google.com/accessibility/android?p=change_verbosity";

  public PunctuationListPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  protected void onPrepareDialogBuilder(
      AlertDialog.Builder builder, DialogInterface.OnClickListener listener) {
    builder.setSingleChoiceItems(getEntries(), findIndexOfValue(getValue()), listener);
    builder.setNegativeButton(
        R.string.learn_more,
        (dialog, which) -> {
          // Jumps to Android Accessibility help for change_verbosity.
          Uri uriUrl = Uri.parse(VERBOSITY_WEB_ADDRESS);
          Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
          getContext().startActivity(launchBrowser);
        });
  }

  /** Dialog fragment of the preference. */
  public static class CustomListPreferenceDialogFragment
      extends ListPreferenceDialogFragmentCompat {

    private int clickedDialogEntryIndex;

    /**
     * Creates an {@code ListPreferenceDialogFragmentCompat} instance for the specified preference.
     *
     * @param key preference key for storing data
     */
    public static ListPreferenceDialogFragmentCompat newInstance(String key) {
      final ListPreferenceDialogFragmentCompat fragment =
          new PunctuationListPreference.CustomListPreferenceDialogFragment();
      final Bundle b = new Bundle(1);
      b.putString(ARG_KEY, key);
      fragment.setArguments(b);
      return fragment;
    }

    private PunctuationListPreference getCustomizablePreference() {
      return (PunctuationListPreference) getPreference();
    }

    private @Nullable String getClickedEntryIndexKey() {
      Bundle arguments = getArguments();
      return arguments == null ? null : arguments.getString(ARG_KEY);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
      super.onPrepareDialogBuilder(builder);
      clickedDialogEntryIndex =
          getCustomizablePreference().findIndexOfValue(getCustomizablePreference().getValue());
      getCustomizablePreference().onPrepareDialogBuilder(builder, getOnItemClickListener());
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
      Dialog dialog = super.onCreateDialog(savedInstanceState);
      if (savedInstanceState != null) {
        String key = getClickedEntryIndexKey();
        if (key != null) {
          clickedDialogEntryIndex = savedInstanceState.getInt(key, clickedDialogEntryIndex);
        }
      }
      return dialog;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
      super.onSaveInstanceState(outState);
      String key = getClickedEntryIndexKey();
      if (key != null) {
        outState.putInt(key, clickedDialogEntryIndex);
      }
    }

    private DialogInterface.OnClickListener getOnItemClickListener() {
      return (dialog, which) -> {
        setClickedDialogEntryIndex(which);
        String value = getValue();
        if (value != null && getCustomizablePreference().callChangeListener(value)) {
          getCustomizablePreference().setValue(value);
        }
        // Dismiss dialog when the item clicked.
        dismiss();
      };
    }

    private void setClickedDialogEntryIndex(int which) {
      clickedDialogEntryIndex = which;
    }

    private String getValue() {
      final ListPreference preference = getCustomizablePreference();
      if (clickedDialogEntryIndex >= 0 && preference.getEntryValues() != null) {
        return preference.getEntryValues()[clickedDialogEntryIndex].toString();
      } else {
        return null;
      }
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
      final ListPreference preference = getCustomizablePreference();
      final String value = getValue();
      if (positiveResult && value != null) {
        if (preference.callChangeListener(value)) {
          preference.setValue(value);
        }
      }
    }
  }
}
