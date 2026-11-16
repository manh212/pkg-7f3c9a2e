package com.google.android.libraries.accessibility.utils.url;

import android.content.Context;
import android.text.style.URLSpan;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpannableUrlTest {
  @Test
  public void onClick_nullUrlDoesNotThrow() {
    Context context = RuntimeEnvironment.getApplication();
    SpannableUrl.create("text", new URLSpan(null)).onClick(context);
  }

  @Test
  public void path_nullUrlReturnsNull() {
    SpannableUrl url = SpannableUrl.create("text", new URLSpan(null));
    org.junit.Assert.assertNull(url.path());
  }
}
