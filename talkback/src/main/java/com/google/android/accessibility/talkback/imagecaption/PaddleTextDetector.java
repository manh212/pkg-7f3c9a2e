package com.google.android.accessibility.talkback.imagecaption;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

final class PaddleTextDetector implements AutoCloseable {
  private static final int MAX_SIDE = 2048;
  private static final int MAX_CANDIDATES = 3000;
  private static final double[] MEAN = {0.485, 0.456, 0.406};
  private static final double[] STD = {0.229, 0.224, 0.225};
  private final OrtEnvironment environment;
  private final OrtSession session;
  private final String inputName;

  PaddleTextDetector(File model) throws OrtException {
    if (!OpenCVLoader.initLocal()) {
      throw new IllegalStateException("OpenCV could not be loaded");
    }
    environment = OrtEnvironment.getEnvironment();
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
      options.setInterOpNumThreads(1);
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
      session = environment.createSession(model.getAbsolutePath(), options);
    }
    inputName = session.getInputNames().iterator().next();
  }

  List<Point[]> detect(Mat bgr) throws OrtException {
    double ratio = Math.min(1.0, (double) MAX_SIDE / Math.max(bgr.cols(), bgr.rows()));
    int width = Math.max(32, (int) Math.rint(bgr.cols() * ratio / 32) * 32);
    int height = Math.max(32, (int) Math.rint(bgr.rows() * ratio / 32) * 32);
    Mat resized = new Mat();
    Mat normalized = new Mat();
    List<Mat> channels = new ArrayList<>(3);
    try {
      Imgproc.resize(bgr, resized, new Size(width, height));
      resized.convertTo(normalized, CvType.CV_32FC3, 1.0 / 255);
      Core.split(normalized, channels);
      int area = width * height;
      FloatBuffer input = FloatBuffer.allocate(3 * area);
      float[] plane = new float[area];
      for (int channel = 0; channel < 3; channel++) {
        Mat values = channels.get(channel);
        Core.subtract(values, new Scalar(MEAN[channel]), values);
        Core.divide(values, new Scalar(STD[channel]), values);
        values.get(0, 0, plane);
        input.put(plane);
      }
      input.rewind();
      try (OnnxTensor tensor =
               OnnxTensor.createTensor(environment, input, new long[] {1, 3, height, width});
           OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
        OnnxTensor prediction = (OnnxTensor) result.get(0);
        long[] shape = prediction.getInfo().getShape();
        int rows = (int) shape[shape.length - 2];
        int columns = (int) shape[shape.length - 1];
        float[] probabilities = new float[rows * columns];
        prediction.getFloatBuffer().get(probabilities);
        return boxes(probabilities, rows, columns, bgr.rows(), bgr.cols());
      }
    } finally {
      resized.release();
      normalized.release();
      for (Mat channel : channels) {
        channel.release();
      }
    }
  }

  private static List<Point[]> boxes(
      float[] probabilities, int rows, int columns, int imageHeight, int imageWidth) {
    Mat scores = new Mat(rows, columns, CvType.CV_32FC1);
    Mat binary = new Mat(rows, columns, CvType.CV_8UC1);
    Mat hierarchy = new Mat();
    List<MatOfPoint> contours = new ArrayList<>();
    List<Point[]> boxes = new ArrayList<>();
    try {
      scores.put(0, 0, probabilities);
      Imgproc.threshold(scores, scores, 0.2, 255, Imgproc.THRESH_BINARY);
      scores.convertTo(binary, CvType.CV_8UC1);
      scores.put(0, 0, probabilities);
      Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
      for (int i = 0; i < Math.min(MAX_CANDIDATES, contours.size()); i++) {
        MatOfPoint2f contour = new MatOfPoint2f(contours.get(i).toArray());
        try {
          RotatedRect box = Imgproc.minAreaRect(contour);
          double width = box.size.width;
          double height = box.size.height;
          if (Math.min(width, height) < 3) {
            continue;
          }
          Point[] corners = new Point[4];
          box.points(corners);
          if (score(scores, corners) < 0.45) {
            continue;
          }
          double margin = 1.4 * width * height / (2 * (width + height));
          box.size = new Size(width + 2 * margin, height + 2 * margin);
          if (Math.min(box.size.width, box.size.height) < 5) {
            continue;
          }
          box.points(corners);
          Point[] ordered = order(corners);
          for (Point point : ordered) {
            point.x = Math.max(0, Math.min(imageWidth - 1, point.x * imageWidth / columns));
            point.y = Math.max(0, Math.min(imageHeight - 1, point.y * imageHeight / rows));
          }
          boxes.add(ordered);
        } finally {
          contour.release();
        }
      }
    } finally {
      scores.release();
      binary.release();
      hierarchy.release();
      for (MatOfPoint contour : contours) {
        contour.release();
      }
    }
    boxes.sort(Comparator.comparingDouble((Point[] box) -> box[0].y)
        .thenComparingDouble(box -> box[0].x));
    for (int i = 1; i < boxes.size(); i++) {
      for (int j = i; j > 0; j--) {
        Point[] left = boxes.get(j - 1);
        Point[] right = boxes.get(j);
        if (Math.abs(left[0].y - right[0].y) >= 10 || left[0].x <= right[0].x) {
          break;
        }
        Collections.swap(boxes, j - 1, j);
      }
    }
    return boxes;
  }

  private static double score(Mat scores, Point[] points) {
    MatOfPoint polygon = new MatOfPoint(points);
    Mat mask = null;
    Mat region = null;
    try {
      Rect bounds = Imgproc.boundingRect(polygon);
      int left = Math.max(0, bounds.x);
      int top = Math.max(0, bounds.y);
      int right = Math.min(scores.cols(), bounds.x + bounds.width);
      int bottom = Math.min(scores.rows(), bounds.y + bounds.height);
      if (left >= right || top >= bottom) {
        return 0;
      }
      Point[] local = new Point[points.length];
      for (int i = 0; i < points.length; i++) {
        local[i] = new Point(points[i].x - left, points[i].y - top);
      }
      polygon.fromArray(local);
      mask = Mat.zeros(bottom - top, right - left, CvType.CV_8UC1);
      Imgproc.fillConvexPoly(mask, polygon, new Scalar(255));
      region = scores.submat(top, bottom, left, right);
      return Core.mean(region, mask).val[0];
    } finally {
      polygon.release();
      if (mask != null) {
        mask.release();
      }
      if (region != null) {
        region.release();
      }
    }
  }

  private static Point[] order(Point[] points) {
    java.util.Arrays.sort(points, Comparator.comparingDouble(point -> point.x));
    Point topLeft = points[0].y <= points[1].y ? points[0] : points[1];
    Point bottomLeft = topLeft == points[0] ? points[1] : points[0];
    Point topRight = points[2].y <= points[3].y ? points[2] : points[3];
    Point bottomRight = topRight == points[2] ? points[3] : points[2];
    return new Point[] {topLeft, topRight, bottomRight, bottomLeft};
  }

  @Override
  public void close() throws OrtException {
    session.close();
  }
}
