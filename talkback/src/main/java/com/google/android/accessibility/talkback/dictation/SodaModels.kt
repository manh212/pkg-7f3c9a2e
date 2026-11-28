package com.google.android.accessibility.talkback.dictation

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.google.android.accessibility.talkback.actor.gemini.local.ModelDownloader
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

internal class SodaModels private constructor(context: Context) {
  sealed interface State {
    data object Idle : State
    data class Downloading(val percent: Int) : State
    data class Failed(val failure: ModelFiles.Failure) : State
  }

  val root = File(context.applicationContext.noBackupFilesDir, "soda-vi-3071")
  val directory = File(root, "installed")
  val library get() = File(directory, LIBRARY)
  val languagePack get() = File(directory, "SODAModels")
  private val main = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor { Thread(it, "soda-download") }
  private val listeners = CopyOnWriteArraySet<() -> Unit>()
  private val cancelled = AtomicBoolean(false)
  private val downloader = ModelDownloader()
  @Volatile var state: State = State.Idle
    private set

  fun isInstalled() = File(directory, "complete").readTextOrNull() == INSTALL_ID &&
    library.length() == LIBRARY_BYTES &&
    File(languagePack, "metadata").length() == 2365L &&
    File(languagePack, "SODA_punctuation_model.tflite").length() == 872912L

  fun addListener(listener: () -> Unit) { listeners.add(listener) }
  fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

  fun startDownload() {
    if (state is State.Downloading) return
    cancelled.set(false)
    update(State.Downloading(0))
    worker.execute {
      try {
        root.mkdirs()
        if (StatFs(root.path).availableBytes < 320_000_000L) {
          update(State.Failed(ModelFiles.Failure.LOW_SPACE))
          return@execute
        }
        val apk = File(root, "engine.apk")
        val model = File(root, "vi.crx")
        for ((index, artifact) in ARTIFACTS.withIndex()) {
          val target = if (index == 0) apk else model
          if (target.length() == artifact.size && target.inputStream().use(ModelFiles::sha256) == artifact.hash) continue
          var reported = 0L
          val result = downloader.download(
            artifact.url, target, artifact.size, artifact.hash,
            onProgress = { bytes ->
              val now = SystemClock.elapsedRealtime()
              if (now - reported >= 500) {
                reported = now
                val base = if (index == 0) 0L else ARTIFACTS[0].size
                update(State.Downloading(((base + bytes) * 95 / DOWNLOAD_BYTES).toInt().coerceIn(0, 95)))
              }
            },
            isCancelled = cancelled::get,
          )
          when (result) {
            is ModelDownloader.Result.Failed -> {
              update(State.Failed(result.failure))
              return@execute
            }
            ModelDownloader.Result.Cancelled -> {
              update(State.Idle)
              return@execute
            }
            ModelDownloader.Result.Done -> Unit
          }
        }
        update(State.Downloading(96))
        val staging = File(root, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
          ZipFile(apk).use { zip ->
            val entry = zip.getEntry("lib/arm64-v8a/$LIBRARY") ?: throw IOException("Missing SODA engine")
            val output = File(staging, LIBRARY)
            zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
            if (output.length() != LIBRARY_BYTES || output.inputStream().use(ModelFiles::sha256) != LIBRARY_HASH) {
              throw IOException("SODA engine verification failed")
            }
            if (!output.setReadOnly()) throw IOException("Cannot protect SODA engine")
          }
          extractModel(model, staging, cancelled::get)
          if (cancelled.get()) throw InterruptedException()
          File(staging, "complete").writeText(INSTALL_ID)
          directory.deleteRecursively()
          if (!staging.renameTo(directory)) throw IOException("Cannot install SODA data")
          apk.delete()
          model.delete()
          update(State.Idle)
        } finally {
          staging.deleteRecursively()
        }
      } catch (_: InterruptedException) {
        update(State.Idle)
      } catch (_: IOException) {
        update(State.Failed(ModelFiles.Failure.STORAGE))
      } catch (_: SecurityException) {
        update(State.Failed(ModelFiles.Failure.STORAGE))
      }
    }
  }

  fun cancelDownload() {
    cancelled.set(true)
    downloader.cancel()
  }

  fun delete() {
    cancelDownload()
    worker.execute {
      root.deleteRecursively()
      update(State.Idle)
    }
  }

  private fun update(next: State) {
    state = next
    main.post { listeners.forEach { it() } }
  }

  private fun File.readTextOrNull(): String? = try { if (isFile) readText() else null } catch (_: IOException) { null }

  companion object {
    const val LIBRARY = "libintegrated_shared_object.so"
    const val LIBRARY_BYTES = 33_995_120L
    const val LIBRARY_HASH = "663d680041624f87bfadc146686b3475781d2e1d84d5f8919df8a56ff097d3ab"
    const val INSTALL_ID = "pixelboard-1.3.6_soda-vi-1.3071.0"
    const val DOWNLOAD_BYTES = 182_934_930L
    private data class Artifact(val url: String, val size: Long, val hash: String)
    private val ARTIFACTS = listOf(
      Artifact(
        "https://github.com/Akshayykadam/PixelBoard/releases/download/1.3.6/PixelBoard-1.3.6.apk",
        127_683_634,
        "862ff3a7c277f1e5d6cc2655793a78ad3bfa312e99ebbda60cada4bf918b5760",
      ),
      Artifact(
        "https://www.google.com/dl/release2/chrome_component/adbc67t5pwbumjm4f3sobxlxc4zq_1.3071.0/bpnckojighgibleldbofdjmfdipfeiak_1.3071.0_all_admmim4oko7kdfv3tmvhauqvrjpq.crx3",
        55_251_296,
        "ea7f53a36184517025c598197fee6233ad4970a6965103f68b80a08b3e44fa74",
      ),
    )
    @Volatile private var instance: SodaModels? = null
    fun get(context: Context): SodaModels = instance ?: synchronized(this) {
      instance ?: SodaModels(context.applicationContext).also { instance = it }
    }
    fun supported(): Boolean = Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS

    internal fun extractModel(archive: File, target: File, cancelled: () -> Boolean) {
      var total = 0L
      var count = 0
      ZipFile(archive).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("SODAModels/") }.forEach { entry ->
          if (cancelled()) throw InterruptedException()
          val file = File(target, entry.name).canonicalFile
          if (!file.path.startsWith(File(target, "SODAModels").canonicalPath + File.separator)) {
            throw IOException("Invalid SODA archive path")
          }
          if (entry.size !in 1..40_000_000L) throw IOException("Invalid SODA model size")
          total += entry.size
          if (total > 56_000_000L || ++count > 40) throw IOException("Oversized SODA archive")
          file.parentFile?.mkdirs()
          zip.getInputStream(entry).use { input ->
            file.outputStream().use { output ->
              val buffer = ByteArray(65536)
              var written = 0L
              while (true) {
                if (cancelled()) throw InterruptedException()
                val length = input.read(buffer)
                if (length < 0) break
                written += length
                if (written > entry.size) throw IOException("Invalid SODA entry")
                output.write(buffer, 0, length)
              }
              if (written != entry.size) throw IOException("Truncated SODA model")
            }
          }
        }
      }
      if (count != 24 || total != 55_237_690L) throw IOException("Incomplete SODA model")
    }
  }
}
