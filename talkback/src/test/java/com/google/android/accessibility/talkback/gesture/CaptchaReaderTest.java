package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class CaptchaReaderTest {
  @Test
  public void preservesCaseAndDigitsInAnOcrLine() {
    assertEquals("aB01Z", CaptchaReader.extractCode(" a B 0 1 Z "));
  }

  @Test
  public void rejectsMultipleLinesAndNonCodeCharacters() {
    assertNull(CaptchaReader.extractCode("AB12\nCD34"));
    assertNull(CaptchaReader.extractCode("AB?12"));
  }
}
