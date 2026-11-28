package com.google.android.accessibility.talkback.recognition

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.imagecaption.OfflineModels
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.accessibility.utils.log.LogUtils
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer

internal class GuidedPhotoDetector(private val context: Context) : AutoCloseable {
  private var faces = faceDetector(false)
  private var fastFaces = false
  private var slowFrames = 0
  private var objects: ObjectDetector? = null
  private var objectPath: String? = null
  private val failedModels = mutableSetOf<String>()
  private val frames = GuidedPhotoFrameAnalyzer(context.getString(R.string.guided_photo_document))

  private fun faceDetector(fast: Boolean): FaceDetector = FaceDetection.getClient(FaceDetectorOptions.Builder()
    .setPerformanceMode(if (fast) FaceDetectorOptions.PERFORMANCE_MODE_FAST else FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
    .setMinFaceSize(.04f)
    .enableTracking()
    .build())

  private fun objectDetector(): ObjectDetector? {
    for (asset in listOf(OfflineModels.guidedObjects, OfflineModels.objects)) {
      val file = OfflineModels.file(context, asset)
      if (!file.isFile || file.length() != asset.size) continue
      val signature = "${file.path}:${file.lastModified()}"
      if (signature in failedModels) continue
      if (objectPath == signature) return objects
      try {
        val bytes = file.readBytes()
        val model = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
        val next = ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
          .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
          .setScoreThreshold(.5f)
          .setMaxResults(10)
          .build())
        objects?.close()
        objects = next
        objectPath = signature
        return next
      } catch (error: Exception) {
        failedModels.add(signature)
        LogUtils.w("GuidedPhoto", "Object model unavailable (%s)", error.javaClass.simpleName)
      } catch (error: LinkageError) {
        failedModels.add(signature)
        LogUtils.w("GuidedPhoto", "Object library unavailable (%s)", error.javaClass.simpleName)
      }
    }
    return objects
  }

  fun analyze(bitmap: Bitmap): PhotoObservation {
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    val started = SystemClock.elapsedRealtime()
    val foundFaces = Tasks.await(faces.process(InputImage.fromBitmap(bitmap, 0)))
      .map { face ->
        val b = face.boundingBox
        PhotoSubject(
          "face:${face.trackingId ?: "untracked"}", context.getString(R.string.guided_photo_face),
          PhotoBox(b.left / width, b.top / height, b.right / width, b.bottom / height),
          PhotoSubjectKind.FACE,
        )
      }
    if (!fastFaces) {
      slowFrames = if (SystemClock.elapsedRealtime() - started > 400L) slowFrames + 1 else 0
      if (slowFrames >= 3) {
        val next = faceDetector(true)
        faces.close()
        faces = next
        fastFaces = true
      }
    }
    return frames.analyze(bitmap, foundFaces) { image -> detectObjects(image, width, height) }
  }

  private fun detectObjects(image: MPImage, width: Float, height: Float): List<PhotoSubject> {
    val engine = objectDetector() ?: return emptyList()
    return try {
      engine.detect(image).detections().mapNotNull { detection ->
        val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
        val name = category.categoryName()
        val label = LABELS[name] ?: return@mapNotNull null
        val b = detection.boundingBox()
        PhotoSubject("object:$name", label,
          PhotoBox(b.left / width, b.top / height, b.right / width, b.bottom / height),
          PhotoSubjectKind.OBJECT, category.score())
      }
    } catch (error: Exception) {
      disableObjects(error)
      emptyList()
    } catch (error: LinkageError) {
      disableObjects(error)
      emptyList()
    }
  }

  private fun disableObjects(error: Throwable) {
    objectPath?.let { failedModels.add(it) }
    try { objects?.close() }
    catch (_: Exception) { Unit }
    catch (_: LinkageError) { Unit }
    objects = null
    objectPath = null
    LogUtils.w("GuidedPhoto", "Object inference unavailable (%s)", error.javaClass.simpleName)
  }

  override fun close() {
    faces.close()
    objects?.close()
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
