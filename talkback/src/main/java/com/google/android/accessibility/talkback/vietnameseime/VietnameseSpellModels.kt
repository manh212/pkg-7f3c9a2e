package com.google.android.accessibility.talkback.vietnameseime

import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import java.io.File
import java.io.IOException

internal object VietnameseSpellModels {
  const val REVISION = "c0cf880734f464515e4ad9c1ff47e5baeda4e708"
  private const val ROOT = "https://huggingface.co/nrl-ai/vn-spell-correction-small-onnx-int8/resolve/$REVISION/"
  data class Spec(val name: String, val size: Long, val sha256: String) {
    val url get() = ROOT + name
  }
  val files = listOf(
    Spec("encoder_model.onnx", 74_422_110, "dc013171c2b9c476ca35fa1cd74faf4b7e9d2c9900fbba007e679fc1fd7a015b"),
    Spec("decoder_model.onnx", 119_823_976, "667c9880620815ad11c7939f84987f07569b8e3342cfc8f3872a9491e8574676"),
    Spec("sentencepiece.bpe.model", 5_069_051, "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865"),
    Spec("dict.txt", 360_025, "4af19721ccc7a2bf2ef9fa63344377659e5b7a2652b764bc964edd8ed86827ae"),
    Spec("config.json", 906, "dee5bec26bb6486db9431171a9a59c2e799080be06eba8c66f5f99c234f9b8db"),
  )
  val totalBytes get() = files.sumOf { it.size }
  private const val RECEIPT = "installed-revision"

  fun ready(directory: File): Boolean = runCatching {
    File(directory, RECEIPT).readText() == REVISION && files.all {
      File(directory, it.name).let { file -> file.isFile && file.length() == it.size }
    }
  }.getOrDefault(false)

  fun install(
    directory: File,
    downloader: ModelDownloader,
    cancelled: () -> Boolean,
    progress: (Long) -> Unit,
    required: List<Spec> = files,
  ): ModelDownloader.Result {
    try {
      if (!directory.isDirectory && !directory.mkdirs()) return ModelDownloader.Result.Failed(ModelFiles.Failure.STORAGE)
      File(directory, RECEIPT).delete()
      val remaining = required.sumOf { spec ->
        val target = File(directory, spec.name)
        if (target.isFile && target.length() == spec.size) 0L
        else (spec.size - ModelFiles.partFile(target).length()).coerceAtLeast(0)
      }
      if (directory.usableSpace > 0 && directory.usableSpace < remaining + 16L * 1024 * 1024) {
        return ModelDownloader.Result.Failed(ModelFiles.Failure.LOW_SPACE)
      }
      var completed = 0L
      for (spec in required) {
        if (cancelled()) return ModelDownloader.Result.Cancelled
        val target = File(directory, spec.name)
        val valid = target.isFile && target.length() == spec.size &&
          target.inputStream().use(ModelFiles::sha256) == spec.sha256
        if (!valid) {
          val result = downloader.download(spec.url, target, spec.size, spec.sha256,
            { bytes -> progress(completed + bytes) }, cancelled)
          if (result != ModelDownloader.Result.Done) return result
        }
        completed += spec.size
        progress(completed)
      }
      if (cancelled()) return ModelDownloader.Result.Cancelled
      val temporary = File(directory, "$RECEIPT.part")
      temporary.writeText(REVISION)
      if (!temporary.renameTo(File(directory, RECEIPT))) return ModelDownloader.Result.Failed(ModelFiles.Failure.STORAGE)
      return ModelDownloader.Result.Done
    } catch (_: IOException) {
      return if (cancelled()) ModelDownloader.Result.Cancelled else ModelDownloader.Result.Failed(ModelFiles.Failure.STORAGE)
    }
  }
}
