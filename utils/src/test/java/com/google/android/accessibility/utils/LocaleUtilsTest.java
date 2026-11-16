package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocaleUtilsTest {
  @Test
  public void parseLocaleString_scriptIsNotCountry() {
    Locale locale = LocaleUtils.parseLocaleString("zh-Hant-TW");
    assertEquals("zh", locale.getLanguage());
    assertEquals("Hant", locale.getScript());
    assertEquals("TW", locale.getCountry());
  }

  @Test
  public void parseLocaleString_languageAndCountry() {
    Locale locale = LocaleUtils.parseLocaleString("en-US");
    assertEquals("en", locale.getLanguage());
    assertEquals("US", locale.getCountry());
  }

  @Test
  public void parseLocaleString_languageOnly() {
    assertEquals("en", LocaleUtils.parseLocaleString("en").getLanguage());
  }

  @Test
  public void parseLocaleString_malformedReturnsNull() {
    assertNull(LocaleUtils.parseLocaleString("a_b"));
    assertNull(LocaleUtils.parseLocaleString(""));
    assertNull(LocaleUtils.parseLocaleString(null));
  }
}
