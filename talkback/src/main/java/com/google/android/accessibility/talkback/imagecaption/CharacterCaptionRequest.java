/*
 * Copyright (C) 2021 Google Inc.
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

import static com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType.OCR;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;

/**
 * A {@link CaptionRequest} for performing OCR (optical character recognition) to recognize text
 * from the screenshot.
 */
public class CharacterCaptionRequest extends CaptionRequest {

  private final AccessibilityService service;
  private final Bitmap screenCapture;

  /** This object takes ownership of node, caller should not recycle. */
  public CharacterCaptionRequest(
      int requestId,
      AccessibilityService service,
      AccessibilityNodeInfoCompat node,
      Bitmap screenCapture,
      @NonNull OnFinishListener onFinishListener,
      @NonNull OnErrorListener onErrorListener,
      boolean isUserRequested) {
    super(requestId, node, onFinishListener, onErrorListener, isUserRequested);
    this.service = service;
    this.screenCapture = screenCapture;
  }

  /**
   * Captures screen and performs ocr(optical character recognition) to recognize text for the given
   * node.
   */
  @Override
  public void perform() {
    if (!com.google.android.accessibility.talkback.licensing.DeviceLicenseManager.allows(service, "ocr")) {
      onCaptionStart();
      onError(ERROR_TEXT_RECOGNITION_NO_RESULT);
      return;
    }
    onCaptionStart();
    if (!TesseractOcr.isDataDownloaded(service)) {
      if (isUserRequested()) {
        Toast.makeText(service, R.string.ocr_offline_model_missing, Toast.LENGTH_SHORT).show();
      }
      onError(ERROR_TEXT_RECOGNITION_NO_RESULT);
      return;
    }
    Handler mainHandler = new Handler(Looper.getMainLooper());
    new Thread(
            () -> {
              String text = TesseractOcr.recognizeText(service, screenCapture);
              mainHandler.post(
                  () -> {
                    if (text == null) {
                      onError(ERROR_TEXT_RECOGNITION_NO_RESULT);
                    } else {
                      onCaptionFinish(Result.create(OCR, text));
                    }
                  });
            })
        .start();
    runTimeoutRunnable();
  }
}
