/*
 * Copyright (C) 2026 The SandFly Authors
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

package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import com.google.android.accessibility.utils.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpeechCleanupUtilsTest {

  private final Context context = RuntimeEnvironment.getApplication();

  @Test
  public void characterToName_allVerbosity_namesAllAndMostSymbols() {
    assertEquals(
        context.getString(R.string.symbol_period),
        SpeechCleanupUtils.characterToName(context, '.', SpeechCleanupUtils.ALL));
    assertEquals(
        context.getString(R.string.symbol_ampersand),
        SpeechCleanupUtils.characterToName(context, '&', SpeechCleanupUtils.ALL));
  }

  @Test
  public void characterToName_mostVerbosity_namesAllAndMostSymbols() {
    assertEquals(
        context.getString(R.string.symbol_period),
        SpeechCleanupUtils.characterToName(context, '.', SpeechCleanupUtils.MOST));
    assertEquals(
        context.getString(R.string.symbol_ampersand),
        SpeechCleanupUtils.characterToName(context, '&', SpeechCleanupUtils.MOST));
  }

  @Test
  public void characterToName_someVerbosity_namesOnlyAllSymbols() {
    assertEquals(
        context.getString(R.string.symbol_period),
        SpeechCleanupUtils.characterToName(context, '.', SpeechCleanupUtils.SOME));
    assertNull(SpeechCleanupUtils.characterToName(context, '&', SpeechCleanupUtils.SOME));
  }
}
