/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.imagecaption;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.licensing.DeviceLicenseManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline text recognition for callers in the screen reader's process.
 *
 * The recognition itself runs in {@link OcrService}'s process (see {@link OcrEngine} there):
 * its native libraries can kill the process they run in on some phones, and that must never take
 * the screen reader down. A call whose process stops comes back null, like a recognition that
 * failed. Callers must already be on a background thread; a call waits for the other process.
 */
public final class TesseractOcr {

  private TesseractOcr() {}

  public static final class TextLine {
    public final String text;
    public final Rect bounds;
    public final List<TextLine> words;
    public final int confidence;

    public TextLine(String text, Rect bounds) {
      this(text, bounds, Collections.emptyList());
    }

    public TextLine(String text, Rect bounds, List<TextLine> words) {
      this(text, bounds, words, -1);
    }

    public TextLine(String text, Rect bounds, List<TextLine> words, int confidence) {
      this.text = text;
      this.bounds = new Rect(bounds);
      this.words = new ArrayList<>(words);
      this.confidence = confidence;
    }
  }

  public static boolean isDataDownloaded(Context context) {
    return OcrModelDownload.isReady(context);
  }

  @Nullable
  public static String recognizeText(Context context, Bitmap bitmap) {
    List<TextLine> lines = recognize(context, bitmap, false);
    if (lines == null) {
      return null;
    }
    List<String> text = new ArrayList<>(lines.size());
    for (TextLine line : lines) {
      text.add(line.text);
    }
    return String.join("\n", text);
  }

  @Nullable
  public static List<TextLine> recognizeSegments(Context context, Bitmap bitmap) {
    return recognize(context, bitmap, true);
  }

  @Nullable
  private static List<TextLine> recognize(Context context, Bitmap bitmap, boolean includeWords) {
    if (!DeviceLicenseManager.allows(context, "ocr") || !isDataDownloaded(context)) {
      return null;
    }
    return RemoteOcr.recognize(context, bitmap, includeWords);
  }
}
