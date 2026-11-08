package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BilingualSpeechTextTest {
  @Test
  public void originalFirst_keepsEachVoiceProfile() {
    BilingualSpeechText speech =
        new BilingualSpeechText("Hello", "Xin chào", "english", "vietnamese", true);
    assertEquals("Hello", speech.first().toString());
    assertEquals("Xin chào", speech.second().toString());
    assertEquals("english", speech.firstProfile());
    assertEquals("vietnamese", speech.secondProfile());
  }

  @Test
  public void translatedFirst_reversesTheOrderOnly() {
    BilingualSpeechText speech =
        new BilingualSpeechText("Hello", "Xin chào", "english", "vietnamese", false);
    assertEquals("Xin chào", speech.first().toString());
    assertEquals("Hello", speech.second().toString());
    assertEquals("vietnamese", speech.firstProfile());
    assertEquals("english", speech.secondProfile());
    assertEquals("Xin chào", speech.toString());
  }
}
