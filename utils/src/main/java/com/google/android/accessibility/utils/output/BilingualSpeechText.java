package com.google.android.accessibility.utils.output;

import java.util.ArrayList;
import java.util.List;

/**
 * A text and its translation, spoken each with its own voice profile.
 *
 * <p>Normally the two texts are spoken one after the other, in the chosen order. In parallel mode
 * they are spoken at the same time instead: the speech controller speaks one of them through the
 * speech queue while a registered {@link SpeechControllerImpl.ParallelBilingualVoice} speaks the
 * other on its own voice.
 */
public final class BilingualSpeechText implements CharSequence {
  private final CharSequence original;
  private final CharSequence translated;
  private final String originalProfile;
  private final String translatedProfile;
  private final boolean originalFirst;
  private final boolean parallel;
  private final String audioStream;

  public BilingualSpeechText(
      CharSequence original,
      CharSequence translated,
      String originalProfile,
      String translatedProfile,
      boolean originalFirst) {
    this(original, translated, originalProfile, translatedProfile, originalFirst, false, "");
  }

  /**
   * @param parallel whether the two texts are spoken at the same time instead of one after the
   *     other
   * @param audioStream a {@code FailoverTextToSpeech} stream value the speech is spoken on, or an
   *     empty string to use the stream speech normally uses
   */
  public BilingualSpeechText(
      CharSequence original,
      CharSequence translated,
      String originalProfile,
      String translatedProfile,
      boolean originalFirst,
      boolean parallel,
      String audioStream) {
    this.original = original;
    this.translated = translated;
    this.originalProfile = originalProfile;
    this.translatedProfile = translatedProfile;
    this.originalFirst = originalFirst;
    this.parallel = parallel;
    this.audioStream = audioStream == null ? "" : audioStream;
  }

  public CharSequence original() {
    return original;
  }

  public CharSequence translated() {
    return translated;
  }

  public CharSequence first() {
    return originalFirst ? original : translated;
  }

  public CharSequence second() {
    return originalFirst ? translated : original;
  }

  public String originalProfile() {
    return originalProfile;
  }

  public String translatedProfile() {
    return translatedProfile;
  }

  public String firstProfile() {
    return originalFirst ? originalProfile : translatedProfile;
  }

  public String secondProfile() {
    return originalFirst ? translatedProfile : originalProfile;
  }

  public boolean isParallel() {
    return parallel;
  }

  public String audioStream() {
    return audioStream;
  }

  /** One piece of the speech: a text and the voice profile that reads it. */
  public static final class Segment {
    private final CharSequence text;
    private final String profile;

    Segment(CharSequence text, String profile) {
      this.text = text;
      this.profile = profile;
    }

    public CharSequence text() {
      return text;
    }

    public String profile() {
      return profile;
    }
  }

  /** The pieces to speak one after the other, in order: the first text whole, then the second. */
  public List<Segment> segments() {
    List<Segment> segments = new ArrayList<>();
    segments.add(new Segment(first(), firstProfile()));
    segments.add(new Segment(second(), secondProfile()));
    return segments;
  }

  @Override
  public int length() {
    return translated.length();
  }

  @Override
  public char charAt(int index) {
    return translated.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return translated.subSequence(start, end);
  }

  @Override
  public String toString() {
    return translated.toString();
  }
}
