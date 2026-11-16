package com.google.android.accessibility.talkback.preference.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.provider.Settings;
import com.google.android.accessibility.talkback.preference.base.SettingsSearchNavigation;
import com.google.android.accessibility.utils.preference.BasePreferencesActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TalkBackSearchIndexablesProviderTest {
  @Test
  public void contractExtrasAreDetected() {
    assertFalse(
        TalkBackSearchIndexablesProvider.isFromSearchIndexablesContract(new Intent()));
    assertFalse(TalkBackSearchIndexablesProvider.isFromSearchIndexablesContract(null));

    Intent withKey = new Intent();
    withKey.putExtra(Settings.EXTRA_FRAGMENT_ARG_KEY, "pref_some_key");
    assertTrue(TalkBackSearchIndexablesProvider.isFromSearchIndexablesContract(withKey));

    Intent withFragment = new Intent();
    withFragment.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, "com.example.SomeFragment");
    assertTrue(TalkBackSearchIndexablesProvider.isFromSearchIndexablesContract(withFragment));
  }

  @Test
  public void explicitFragmentIsAssigned() {
    Intent intent = new Intent();
    intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, "com.example.SomeFragment");
    TalkBackSearchIndexablesProvider.assignToFragmentFromSearch(intent);
    assertEquals(
        "com.example.SomeFragment",
        intent.getStringExtra(BasePreferencesActivity.FRAGMENT_NAME));
  }

  @Test
  public void keyIsKeptAsTargetWhenServiceIsUnavailable() {
    Intent intent = new Intent();
    intent.putExtra(Settings.EXTRA_FRAGMENT_ARG_KEY, "pref_some_key");
    TalkBackSearchIndexablesProvider.assignToFragmentFromSearch(intent);
    Bundle args = intent.getBundleExtra(BasePreferencesActivity.FRAGMENT_ARGS);
    assertNotNull(args);
    assertEquals("pref_some_key", args.getString(SettingsSearchNavigation.TARGET_KEY));
    assertNull(intent.getStringExtra(BasePreferencesActivity.FRAGMENT_NAME));
  }
}
