package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import io.meridian.licensing.ForumProtocol;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ForumTopicDraftTest {
  private SharedPreferences preferences;

  @Before
  public void setUp() {
    preferences = ForumTopicDraft.preferences(RuntimeEnvironment.getApplication());
    preferences.edit().clear().apply();
  }

  @Test
  public void reopeningRestoresAllFields() {
    String category = ForumProtocol.categories().get(1);
    ForumTopicDraft saved =
        ForumTopicDraft.save(preferences, category, "Title", "First\nSecond", "Yes\nNo");
    assertEquals(saved, ForumTopicDraft.load(
        ForumTopicDraft.preferences(RuntimeEnvironment.getApplication())));
    assertTrue(saved.clearAfterPublishing(preferences));
    assertEquals("", ForumTopicDraft.load(preferences).body());
  }

  @Test
  public void publishingAnEarlierVersionKeepsNewerEdits() {
    String category = ForumProtocol.categories().get(0);
    ForumTopicDraft posted = ForumTopicDraft.save(preferences, category, "Title", "Original", "");
    ForumTopicDraft updated = ForumTopicDraft.save(preferences, category, "Title", "Revised", "");
    assertFalse(posted.clearAfterPublishing(preferences));
    assertEquals(updated, ForumTopicDraft.load(preferences));
  }
}
