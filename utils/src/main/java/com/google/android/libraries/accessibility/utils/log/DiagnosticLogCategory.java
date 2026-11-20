package com.google.android.libraries.accessibility.utils.log;

import java.util.Locale;

public enum DiagnosticLogCategory {
  ALL("all"), FOCUS("focus"), READING_ORDER("reading_order"), GESTURES("gestures"),
  EVENTS("events"), PERFORMANCE("performance"), SPEECH("speech"),
  PREPARATION("preparation"), INPUT("input"), RECOGNITION("recognition"),
  CONNECTIVITY("connectivity"), SYSTEM("system");

  public final String value;

  DiagnosticLogCategory(String value) {
    this.value = value;
  }

  public static DiagnosticLogCategory fromValue(String value) {
    for (DiagnosticLogCategory category : values()) {
      if (category.value.equals(value)) return category;
    }
    return ALL;
  }

  public static DiagnosticLogCategory forTag(String tag) {
    if (tag == null) return SYSTEM;
    String name = tag.toLowerCase(Locale.ROOT);
    if (name.contains("prepar") || name.contains("pcmcache")
        || name.contains("speechcache") || name.contains("cachecontroller")) return PREPARATION;
    if (name.contains("focusprocessorforlogicalnavigation")) return READING_ORDER;
    if (name.contains("focus") || name.contains("a11yfocus")
        || name.contains("directionnavigationinterpreter")) return FOCUS;
    if (name.contains("travers") || name.contains("readingorder")
        || name.contains("navigationorder") || name.contains("nodepath")
        || name.contains("scroll") || name.contains("nodeprefetch")
        || name.contains("directionnavigationactor")) return READING_ORDER;
    if (name.contains("gesture") || name.contains("touch")
        || name.contains("tap") || name.contains("motion")) return GESTURES;
    if (name.contains("event") || name.contains("windowinterpreter")
        || name.contains("backgroundgate") || name.contains("eventqueue")) return EVENTS;
    if (name.contains("latency") || name.contains("performance")
        || name.contains("stall") || name.contains("jank")) return PERFORMANCE;
    if (name.contains("tts") || name.contains("speechcontroller")
        || name.contains("texttospeech") || name.contains("audio")
        || name.contains("feedbackfragments") || name.contains("sound")) return SPEECH;
    if (name.contains("braille") || name.contains("keyboard")
        || name.contains("typing") || name.contains("textinput")
        || name.contains("inputconnection") || name.contains("inputmethod")) return INPUT;
    if (name.contains("rambler") || name.contains("recognition")
        || name.contains("dictation") || name.contains("gemini")
        || name.contains("whisper") || name.contains("translation")
        || name.contains("googlespeech")) return RECOGNITION;
    if (name.contains("network") || name.contains("firebase")
        || name.contains("support") || name.contains("download")
        || name.contains("upload") || name.contains("updatechecker")
        || name.contains("license") || name.contains("activation")) return CONNECTIVITY;
    return SYSTEM;
  }
}
