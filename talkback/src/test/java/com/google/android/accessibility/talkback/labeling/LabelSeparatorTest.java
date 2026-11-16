package com.google.android.accessibility.talkback.labeling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.google.android.accessibility.talkback.labeling.LabelSeparator.LabelWrapper;
import com.google.android.accessibility.utils.labeling.Label;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabelSeparatorTest {
  private static Label label(
      String pkg, String signature, String view, String text, String locale, int version) {
    return new Label(pkg, signature, view, text, locale, version, "", 0);
  }

  @Test
  public void hashCode_withNullFields_doesNotThrow() {
    LabelWrapper wrapper =
        new LabelWrapper(label("com.example.app", null, null, "text", null, 1));
    int hash = wrapper.hashCode();
    LabelWrapper same =
        new LabelWrapper(label("com.example.app", null, null, "other", null, 1));
    assertEquals(hash, same.hashCode());
  }

  @Test
  public void hashCode_distinguishesDifferentSignatures() {
    LabelWrapper first =
        new LabelWrapper(label("com.example.app", "sig-a", "view", "text", "en", 1));
    LabelWrapper second =
        new LabelWrapper(label("com.example.app", "sig-b", "view", "text", "en", 1));
    assertNotEquals(first.hashCode(), second.hashCode());
  }

  @Test
  public void hashCode_equalWrappersAgree() {
    LabelWrapper first =
        new LabelWrapper(label("com.example.app", "sig", "view", "text", "en", 1));
    LabelWrapper second =
        new LabelWrapper(label("com.example.app", "sig", "view", "other", "en", 1));
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  @Test
  public void separate_labelsWithNullSignature_areMatchedAsConflicts() {
    Label existing = label("com.example.app", null, "view", "old", "en", 1);
    Label imported = label("com.example.app", null, "view", "new", "en", 1);
    LabelSeparator separator = new LabelSeparator(List.of(existing), List.of(imported));
    assertEquals(List.of(existing), separator.getExistingConflictLabels());
    assertEquals(List.of(imported), separator.getImportedConflictLabels());
    assertEquals(List.of(), separator.getImportedNewLabels());
  }
}
