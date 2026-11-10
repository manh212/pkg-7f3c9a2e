/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
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

import ai.onnxruntime.OrtException;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.licensing.DeviceLicenseManager;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public final class TesseractOcr implements AutoCloseable {
  private static final String TAG = "TesseractOcr";
  private static final Object ENGINE_LOCK = new Object();
  private static final long IDLE_TIMEOUT_MS = 180000L;
  private static final Handler IDLE_HANDLER;
  private static final Runnable IDLE_RELEASE = TesseractOcr::onIdleTimeout;
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final int LINE_HEIGHT = 48;
  private static final int PADDING = 6;
  private static final int MAX_LINE_BYTES = 64 * 1024 * 1024;
  private static TesseractOcr engine;
  private static long lastUsedMs;

  static {
    HandlerThread idleThread = new HandlerThread("tesseract-idle");
    idleThread.setDaemon(true);
    idleThread.start();
    IDLE_HANDLER = new Handler(idleThread.getLooper());
  }

  private final PaddleTextDetector detector;
  private final TessBaseAPI recognizer;

  private TesseractOcr(Context context) throws OrtException {
    detector = new PaddleTextDetector(OcrModelDownload.detectorFile(context));
    TessBaseAPI api = null;
    try {
      api = new TessBaseAPI();
      String root = OcrModelDownload.directory(context).getAbsolutePath() + File.separator;
      if (!api.init(root, "vie", TessBaseAPI.OcrEngineMode.OEM_LSTM_ONLY)) {
        throw new IllegalStateException("Vietnamese Tesseract model could not be loaded");
      }
      api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE);
    } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
      try {
        detector.close();
      } catch (OrtException closeError) {
        e.addSuppressed(closeError);
      } finally {
        if (api != null) {
          api.recycle();
        }
      }
      throw e;
    }
    recognizer = api;
  }

  public static boolean isDataDownloaded(Context context) {
    return OcrModelDownload.isReady(context);
  }

  private static void onIdleTimeout() {
    synchronized (ENGINE_LOCK) {
      if (engine == null) {
        return;
      }
      long remaining = IDLE_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastUsedMs);
      if (remaining <= 0) {
        engine.close();
        engine = null;
      } else {
        IDLE_HANDLER.postDelayed(IDLE_RELEASE, remaining);
      }
    }
  }

  public static final class TextLine {
    public final String text;
    public final Rect bounds;
    public final List<TextLine> words;

    public TextLine(String text, Rect bounds) {
      this(text, bounds, Collections.emptyList());
    }

    public TextLine(String text, Rect bounds, List<TextLine> words) {
      this.text = text;
      this.bounds = new Rect(bounds);
      this.words = new ArrayList<>(words);
    }
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
    synchronized (ENGINE_LOCK) {
      try {
        if (engine == null) {
          engine = new TesseractOcr(context);
        }
        return engine.read(bitmap, includeWords);
      } catch (OrtException | RuntimeException | LinkageError | OutOfMemoryError e) {
        LogUtils.w(TAG, "Offline OCR failed: %s", e.getClass().getSimpleName());
        return null;
      } finally {
        if (engine != null) {
          lastUsedMs = SystemClock.elapsedRealtime();
          IDLE_HANDLER.removeCallbacks(IDLE_RELEASE);
          IDLE_HANDLER.postDelayed(IDLE_RELEASE, IDLE_TIMEOUT_MS);
        }
      }
    }
  }

  private List<TextLine> read(Bitmap bitmap, boolean includeWords) throws OrtException {
    Bitmap software = bitmap.getConfig() == Bitmap.Config.ARGB_8888
        ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);
    if (software == null) {
      throw new IllegalStateException("OCR bitmap could not be copied");
    }
    Mat rgba = new Mat();
    Mat bgr = new Mat();
    try {
      Utils.bitmapToMat(software, rgba);
      Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
      rgba.release();
      List<TextLine> lines = new ArrayList<>();
      for (Point[] box : detector.detect(bgr)) {
        TextLine line = readLine(bgr, box, includeWords);
        if (line != null) {
          lines.add(line);
        }
      }
      return lines;
    } finally {
      rgba.release();
      bgr.release();
      if (software != bitmap) {
        software.recycle();
      }
    }
  }

  @Nullable
  private TextLine readLine(Mat image, Point[] box, boolean includeWords) {
    double width = Math.max(distance(box[0], box[1]), distance(box[3], box[2]));
    double height = Math.max(distance(box[0], box[3]), distance(box[1], box[2]));
    if (height > 1.5 * width) {
      box = new Point[] {box[1], box[2], box[3], box[0]};
      double swap = width;
      width = height;
      height = swap;
    }
    if (width < 1 || height < 1) {
      return null;
    }
    int cropWidth = (int) Math.ceil(width);
    int cropHeight = (int) Math.ceil(height);
    int lineWidth = Math.max(1, (int) Math.rint(cropWidth * (double) LINE_HEIGHT / cropHeight));
    int paddedWidth = lineWidth + 2 * PADDING;
    int paddedHeight = LINE_HEIGHT + 2 * PADDING;
    if ((long) paddedWidth * paddedHeight * 3 > MAX_LINE_BYTES) {
      return null;
    }
    MatOfPoint2f source = new MatOfPoint2f(box);
    MatOfPoint2f target = new MatOfPoint2f(
        new Point(0, 0), new Point(cropWidth, 0),
        new Point(cropWidth, cropHeight), new Point(0, cropHeight));
    Mat transform = Imgproc.getPerspectiveTransform(source, target);
    Mat crop = new Mat();
    Mat resized = new Mat();
    Mat padded = new Mat();
    try {
      Imgproc.warpPerspective(image, crop, transform, new Size(cropWidth, cropHeight),
          Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
      Imgproc.cvtColor(crop, crop, Imgproc.COLOR_BGR2RGB);
      if (darkBorder(crop)) {
        Core.bitwise_not(crop, crop);
      }
      Imgproc.resize(crop, resized, new Size(lineWidth, LINE_HEIGHT), 0, 0, Imgproc.INTER_CUBIC);
      Core.copyMakeBorder(resized, padded, PADDING, PADDING, PADDING, PADDING,
          Core.BORDER_CONSTANT, new Scalar(255, 255, 255));
      byte[] pixels = new byte[paddedWidth * paddedHeight * 3];
      padded.get(0, 0, pixels);
      recognizer.setImage(pixels, paddedWidth, paddedHeight, 3, paddedWidth * 3);
      String text = normalize(recognizer.getUTF8Text());
      if (text.isEmpty()) {
        return null;
      }
      float[] corners = new float[8];
      for (int i = 0; i < box.length; i++) {
        corners[i * 2] = (float) box[i].x;
        corners[i * 2 + 1] = (float) box[i].y;
      }
      Rect bounds = boundingRect(corners, image.cols(), image.rows());
      if (bounds.isEmpty()) {
        return null;
      }
      List<TextLine> words = includeWords
          ? readWords(corners, lineWidth, image.cols(), image.rows()) : Collections.emptyList();
      return new TextLine(text, bounds, words);
    } finally {
      recognizer.clear();
      source.release();
      target.release();
      transform.release();
      crop.release();
      resized.release();
      padded.release();
    }
  }

  private List<TextLine> readWords(float[] corners, int lineWidth, int width, int height) {
    List<TextLine> words = new ArrayList<>();
    ResultIterator iterator = recognizer.getResultIterator();
    if (iterator == null) {
      return words;
    }
    try {
      int level = TessBaseAPI.PageIteratorLevel.RIL_WORD;
      iterator.begin();
      do {
        String text = normalize(iterator.getUTF8Text(level));
        int[] box = iterator.getBoundingBox(level);
        if (!text.isEmpty() && box != null && box.length == 4) {
          Rect bounds = projectWord(box, corners, lineWidth, width, height);
          if (!bounds.isEmpty()) {
            words.add(new TextLine(text, bounds));
          }
        }
      } while (iterator.next(level));
      return words;
    } finally {
      iterator.delete();
    }
  }

  static Rect projectWord(int[] box, float[] corners, int lineWidth, int width, int height) {
    float right = PADDING + lineWidth;
    float bottom = PADDING + LINE_HEIGHT;
    float[] crop = {PADDING, PADDING, right, PADDING, right, bottom, PADDING, bottom};
    Matrix toImage = new Matrix();
    if (!toImage.setPolyToPoly(crop, 0, corners, 0, 4)) {
      return new Rect();
    }
    float[] points = {box[0], box[1], box[2], box[1], box[2], box[3], box[0], box[3]};
    toImage.mapPoints(points);
    return boundingRect(points, width, height);
  }

  private static Rect boundingRect(float[] points, int width, int height) {
    float left = points[0];
    float top = points[1];
    float right = left;
    float bottom = top;
    for (int i = 2; i < points.length; i += 2) {
      left = Math.min(left, points[i]);
      top = Math.min(top, points[i + 1]);
      right = Math.max(right, points[i]);
      bottom = Math.max(bottom, points[i + 1]);
    }
    return new Rect(
        Math.max(0, (int) Math.floor(left)), Math.max(0, (int) Math.floor(top)),
        Math.min(width, (int) Math.ceil(right)), Math.min(height, (int) Math.ceil(bottom)));
  }

  private static boolean darkBorder(Mat crop) {
    int width = crop.cols();
    int height = crop.rows();
    byte[] top = new byte[3 * width];
    byte[] bottom = new byte[3 * width];
    byte[] row = new byte[3 * width];
    crop.get(0, 0, top);
    crop.get(height - 1, 0, bottom);
    long total = 0;
    for (int i = 0; i < top.length; i++) {
      total += (top[i] & 255) + (bottom[i] & 255);
    }
    for (int y = 0; y < height; y++) {
      crop.get(y, 0, row);
      for (int channel = 0; channel < 3; channel++) {
        total += (row[channel] & 255) + (row[3 * (width - 1) + channel] & 255);
      }
    }
    return total < 127L * 6 * (width + height);
  }

  private static double distance(Point first, Point second) {
    return Math.hypot(first.x - second.x, first.y - second.y);
  }

  private static String normalize(@Nullable String text) {
    return text == null ? "" : WHITESPACE.matcher(
        Normalizer.normalize(text, Normalizer.Form.NFC).trim()).replaceAll(" ");
  }

  @Override
  public void close() {
    try {
      detector.close();
    } catch (OrtException e) {
      LogUtils.w(TAG, "Could not close the OCR detector");
    } finally {
      recognizer.recycle();
    }
  }
}
