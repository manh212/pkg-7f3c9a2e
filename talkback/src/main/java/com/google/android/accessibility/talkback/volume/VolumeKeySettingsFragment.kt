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

package com.google.android.accessibility.talkback.volume

import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment

/**
 * Volume key settings: whether SandFly manages the volume keys itself, and one sub-screen per
 * key with the actions of a press, of pressing the key 2, 3 or 4 times quickly, of a short
 * hold and of a long hold, separately for when the screen is on, when the screen is locked,
 * and when the screen is off.
 */
class VolumeKeySettingsFragment : TalkbackBaseFragment(R.xml.volume_key_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.volume_key_settings_title)
}
