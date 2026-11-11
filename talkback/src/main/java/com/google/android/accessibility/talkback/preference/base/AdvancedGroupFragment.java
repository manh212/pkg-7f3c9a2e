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

import com.google.android.accessibility.talkback.R;

/**
 * Main settings group holding the developer settings, the Meridian wallpaper, the resume and
 * Play-installer rows, and the way into backup &amp; reset and scripts &amp; labels. It extends
 * {@link AdvancedSettingFragment} so the Play-installer switch on this screen gets the same
 * on-resume upkeep it had on the Help Center screen.
 */
public class AdvancedGroupFragment extends AdvancedSettingFragment {

  public AdvancedGroupFragment() {
    super(R.xml.settings_group_advanced);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_advanced_settings);
  }
}
