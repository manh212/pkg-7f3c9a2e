package com.google.android.accessibility.utils.output;

public final class BilingualSpeechText implements CharSequence {
  private final CharSequence original;
  private final CharSequence translated;
  private final String originalProfile;
  private final String translatedProfile;
  private final boolean originalFirst;

  public BilingualSpeechText(
      CharSequence original,
      CharSequence translated,
      String originalProfile,
      String translatedProfile,
      boolean originalFirst) {
    this.original = original;
    this.translated = translated;
    this.originalProfile = originalProfile;
    this.translatedProfile = translatedProfile;
    this.originalFirst = originalFirst;
  }

  public CharSequence first() {
    return originalFirst ? original : translated;
  }

  public CharSequence second() {
    return originalFirst ? translated : original;
  }

  public String firstProfile() {
    return originalFirst ? originalProfile : translatedProfile;
  }

  public String secondProfile() {
    return originalFirst ? translatedProfile : originalProfile;
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
