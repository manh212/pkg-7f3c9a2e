package com.google.android.accessibility.talkback.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import com.google.android.gms.tasks.Tasks
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

internal class GuidedPhotoDetector(private val context: Context) : AutoCloseable {
  private val faces = FaceDetection.getClient(FaceDetectorOptions.Builder()
    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
    .setMinFaceSize(.08f)
    .enableTracking()
    .build())
  private val objects: ObjectDetector
  private val quality = PhotoQualityAnalyzer()

  init {
    objects = try {
      val bytes = OfflineModels.file(context, OfflineModels.objects).readBytes()
      val model = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
      ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
        .setScoreThreshold(.55f)
        .setMaxResults(10)
        .build())
    } catch (error: Exception) {
      faces.close()
      throw error
    }
  }

  fun analyze(bitmap: Bitmap): PhotoObservation {
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    val foundFaces = Tasks.await(faces.process(InputImage.fromBitmap(bitmap, 0)), 2, TimeUnit.SECONDS)
      .map { face ->
        val b = face.boundingBox
        val extraX = b.width() * .18f
        val extraTop = b.height() * .25f
        PhotoSubject(
          "face:${face.trackingId ?: "untracked"}", context.getString(R.string.guided_photo_face),
          PhotoBox((b.left - extraX) / width, (b.top - extraTop) / height,
            (b.right + extraX) / width, (b.bottom + b.height() * .08f) / height),
          PhotoSubjectKind.FACE,
          facingCamera = abs(face.headEulerAngleY) < 25 && abs(face.headEulerAngleX) < 25,
        )
      }
    val foundObjects = BitmapImageBuilder(bitmap).build().use { image ->
      objects.detect(image).detections().mapNotNull { detection ->
        val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
        val name = category.categoryName()
        val label = LABELS[name] ?: return@mapNotNull null
        val b = detection.boundingBox()
        val box = PhotoBox(b.left / width, b.top / height, b.right / width, b.bottom / height)
        if (name == "person" && foundFaces.any { face ->
            face.box.cx in box.left..box.right && face.box.cy in box.top..box.bottom
          }) return@mapNotNull null
        PhotoSubject("object:$name", label, box, PhotoSubjectKind.OBJECT, category.score())
      }
    }
    val subjects = (foundFaces + foundObjects).toMutableList()
    val document = DocumentAlignmentScanner.analyze(bitmap)
    document.observedCorners?.let { corners ->
      val box = PhotoBox(corners.minOf { it.x }.toFloat() / width,
        corners.minOf { it.y }.toFloat() / height,
        corners.maxOf { it.x }.toFloat() / width, corners.maxOf { it.y }.toFloat() / height)
      val conflicts = subjects.any { it.key != "object:book" && it.box.overlaps(box) > .5f }
      if (!conflicts) {
        subjects.add(PhotoSubject("document", context.getString(R.string.guided_photo_document), box,
          PhotoSubjectKind.DOCUMENT, documentState = document.state))
      }
    }
    return quality.analyze(bitmap, subjects)
  }

  override fun close() {
    faces.close()
    objects.close()
  }

  companion object {
    internal val LABELS = mapOf(
      "person" to "người", "bicycle" to "xe đạp", "car" to "ô tô", "motorcycle" to "xe máy",
      "airplane" to "máy bay", "bus" to "xe buýt", "train" to "tàu hỏa", "truck" to "xe tải",
      "boat" to "thuyền", "traffic light" to "đèn giao thông", "fire hydrant" to "trụ cứu hỏa",
      "stop sign" to "biển dừng", "parking meter" to "máy tính phí đỗ xe", "bench" to "ghế dài",
      "bird" to "con chim", "cat" to "con mèo", "dog" to "con chó", "horse" to "con ngựa",
      "sheep" to "con cừu", "cow" to "con bò", "elephant" to "con voi", "bear" to "con gấu",
      "zebra" to "ngựa vằn", "giraffe" to "hươu cao cổ", "backpack" to "ba lô", "umbrella" to "ô dù",
      "handbag" to "túi xách", "tie" to "cà vạt", "suitcase" to "va li", "frisbee" to "đĩa ném",
      "skis" to "ván trượt tuyết", "snowboard" to "ván trượt tuyết", "sports ball" to "quả bóng",
      "kite" to "diều", "baseball bat" to "gậy bóng chày", "baseball glove" to "găng bóng chày",
      "skateboard" to "ván trượt", "surfboard" to "ván lướt sóng", "tennis racket" to "vợt tennis",
      "bottle" to "chai", "wine glass" to "ly", "cup" to "cốc", "fork" to "nĩa", "knife" to "dao",
      "spoon" to "thìa", "bowl" to "bát", "banana" to "đồ ăn, quả chuối", "apple" to "đồ ăn, quả táo",
      "sandwich" to "đồ ăn, bánh mì kẹp", "orange" to "đồ ăn, quả cam", "broccoli" to "đồ ăn, súp lơ",
      "carrot" to "đồ ăn, củ cà rốt", "hot dog" to "đồ ăn, bánh mì xúc xích", "pizza" to "đồ ăn, bánh pizza",
      "donut" to "đồ ăn, bánh vòng", "cake" to "đồ ăn, bánh ngọt", "chair" to "ghế", "couch" to "ghế sofa",
      "potted plant" to "chậu cây", "bed" to "giường", "dining table" to "bàn ăn", "toilet" to "bồn cầu",
      "tv" to "tivi hoặc màn hình", "laptop" to "máy tính xách tay", "mouse" to "chuột máy tính",
      "remote" to "điều khiển từ xa", "keyboard" to "bàn phím", "cell phone" to "điện thoại",
      "microwave" to "lò vi sóng", "oven" to "lò nướng", "toaster" to "máy nướng bánh", "sink" to "bồn rửa",
      "refrigerator" to "tủ lạnh", "book" to "quyển sách", "clock" to "đồng hồ", "vase" to "bình hoa",
      "scissors" to "kéo", "teddy bear" to "gấu bông", "hair drier" to "máy sấy tóc", "toothbrush" to "bàn chải",
    )
  }
}

internal class PhotoQualityAnalyzer {
  private var previous: IntArray? = null

  fun analyze(bitmap: Bitmap, subjects: List<PhotoSubject>): PhotoObservation {
    val width = 160
    val height = max(3, bitmap.height * width / bitmap.width)
    val reduced = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val colors = IntArray(width * height)
    try {
      reduced.getPixels(colors, 0, width, 0, 0, width, height)
    } finally {
      if (reduced !== bitmap) reduced.recycle()
    }
    val gray = IntArray(colors.size) { i ->
      val c = colors[i]
      (Color.red(c) * .299f + Color.green(c) * .587f + Color.blue(c) * .114f).toInt()
    }
    var sum = 0.0
    var squares = 0.0
    for (y in 1 until height - 1) for (x in 1 until width - 1) {
      val i = y * width + x
      val laplacian = gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4 * gray[i]
      sum += laplacian
      squares += laplacian.toDouble() * laplacian
    }
    val count = (width - 2) * (height - 2)
    val variance = (squares / count - (sum / count) * (sum / count)).coerceAtLeast(0.0)
    val old = previous
    val movement = if (old != null && old.size == gray.size) {
      gray.indices.sumOf { abs(gray[it] - old[it]).toDouble() }.div(gray.size).toFloat()
    } else 100f
    previous = gray
    return PhotoObservation(subjects, gray.average().toFloat(), variance.toFloat(), movement, false)
  }
}
