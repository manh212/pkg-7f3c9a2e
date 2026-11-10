package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
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

  @Test
  public void sequential_segmentsAreTheTwoWholeTexts() {
    BilingualSpeechText speech =
        new BilingualSpeechText("Hello world", "Xin chào thế giới", "english", "vietnamese", true);
    List<BilingualSpeechText.Segment> segments = speech.segments();
    assertEquals(2, segments.size());
    assertEquals("Hello world", segments.get(0).text().toString());
    assertEquals("english", segments.get(0).profile());
    assertEquals("Xin chào thế giới", segments.get(1).text().toString());
    assertEquals("vietnamese", segments.get(1).profile());
  }

  @Test
  public void parallel_keepsBothTextsAndTheChosenStream() {
    BilingualSpeechText speech =
        new BilingualSpeechText(
            "One comes first. Two comes second.",
            "Một đến trước. Hai đến sau.",
            "english",
            "vietnamese",
            true,
            true,
            "media");
    assertTrue(speech.isParallel());
    assertEquals("media", speech.audioStream());
    assertEquals("One comes first. Two comes second.", speech.original().toString());
    assertEquals("Một đến trước. Hai đến sau.", speech.translated().toString());
    assertEquals("english", speech.originalProfile());
    assertEquals("vietnamese", speech.translatedProfile());
  }
}
