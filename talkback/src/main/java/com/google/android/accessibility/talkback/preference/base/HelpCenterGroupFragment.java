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

package com.google.android.accessibility.talkback.preference.base;

import android.os.Bundle;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.training.TutorialInitiator;
import com.google.android.accessibility.utils.FormFactorUtils;

/** Main settings group holding the tutorial, the about page, logs and community entries. */
public class HelpCenterGroupFragment extends AdvancedSettingFragment {

  public HelpCenterGroupFragment() {
    super(R.xml.settings_group_help_center);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_group_help_center);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    assignTutorialIntent();
  }

  /**
   * The Tutorial row opens the tutorial itself instead of an intermediate screen, so it carries
   * the tutorial intent and no fragment. {@link TalkBackPreferenceFragment} attaches {@link
   * TutorialAndHelpFragment} to this row while loading the screen, which would win over the
   * intent when tapped, so the fragment is detached again here. On TV the row keeps the
   * fragment behaviour it has today.
   */
  private void assignTutorialIntent() {
    if (FormFactorUtils.isAndroidTv()) {
      return;
    }
    final Preference prefTutorial = findPreferenceByResId(R.string.pref_tutorial_and_help_key);
    if (prefTutorial == null) {
      return;
    }
    prefTutorial.setFragment(null);
    prefTutorial.setIntent(TutorialInitiator.createTutorialIntent(getActivity()));
  }
}
