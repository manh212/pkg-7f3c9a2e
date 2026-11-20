package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class DiagnosticsPreferenceActionsTest {

  @Test
  public void smallDiagnosticsCanBeCopied() {
    assertTrue(DiagnosticsPreferenceActions.canCopyToClipboard("Log entry"));
    assertTrue(DiagnosticsPreferenceActions.canCopyToClipboard(new String(new char[100_000])));
  }

  @Test
  public void oversizedDiagnosticsCannotReachTheClipboard() {
    assertFalse(DiagnosticsPreferenceActions.canCopyToClipboard(new String(new char[100_001])));
    assertFalse(DiagnosticsPreferenceActions.canCopyToClipboard(null));
  }
}
