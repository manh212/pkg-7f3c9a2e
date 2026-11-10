package com.google.android.accessibility.talkback.dictation

import com.google.android.accessibility.talkback.dictation.DictationSettings.OnlineRoute
import com.google.android.accessibility.talkback.dictation.DictationSettings.Recognition
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationOptionsTest {
  @Test fun speechServicesExcludeAutomaticAndGoogleOnDevice() {
    assertEquals(
      listOf(Recognition.RAMBLER, Recognition.ZIPFORMER, Recognition.ANDROID),
      Recognition.entries,
    )
  }

  @Test fun onlineRouteDoesNotExposeModelNames() {
    assertEquals(listOf(OnlineRoute.GEMINI, OnlineRoute.DESCRIPTIONS), OnlineRoute.entries)
  }
}
