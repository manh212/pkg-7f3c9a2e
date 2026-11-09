/*
 * Copyright (C) 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * The service info applied when the service connects is applied again at locked boot
 * completion, so the framework re-registers the service's requests (such as touch
 * exploration) once the keyguard is up. The locked-boot call itself needs a live pipeline
 * and is covered by inspection; these tests cover the re-applied info.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TalkBackServiceLockedBootTest {

  private TalkBackService service;
  private SharedPreferences prefs;

  @Before
  public void setUp() {
    service = Robolectric.buildService(TalkBackService.class).create().get();
    prefs = SharedPreferencesUtils.getSharedPreferences(service);
    prefs.edit().clear().commit();
    ReflectionHelpers.setField(service, "prefs", prefs);
    service.setServiceInfo(new AccessibilityServiceInfo());
  }

  @After
  public void tearDown() {
    prefs.edit().clear().commit();
  }

  @Test
  public void applyServiceInfo_requestsTouchExplorationWhenPreferenceEnabled() {
    ReflectionHelpers.callInstanceMethod(service, "applyServiceInfo");

    AccessibilityServiceInfo info = service.getServiceInfo();
    assertNotNull(info);
    assertTrue(
        (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
  }

  @Test
  public void applyServiceInfo_clearsTouchExplorationWhenPreferenceDisabled() {
    prefs
        .edit()
        .putBoolean(service.getString(R.string.pref_explore_by_touch_key), false)
        .commit();

    ReflectionHelpers.callInstanceMethod(service, "applyServiceInfo");

    AccessibilityServiceInfo info = service.getServiceInfo();
    assertNotNull(info);
    assertFalse(
        (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
  }
}
