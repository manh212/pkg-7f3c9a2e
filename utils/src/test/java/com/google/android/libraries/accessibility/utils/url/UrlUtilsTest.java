package com.google.android.libraries.accessibility.utils.url;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UrlUtilsTest {
  @Test
  public void openUrlWithIntent_nullUrlDoesNothing() {
    Context context = RuntimeEnvironment.getApplication();
    UrlUtils.openUrlWithIntent(context, null);
  }

  @Test
  public void openUrlWithIntent_validUrlDoesNotThrow() {
    Context context = RuntimeEnvironment.getApplication();
    UrlUtils.openUrlWithIntent(context, "https://example.com");
  }
}
