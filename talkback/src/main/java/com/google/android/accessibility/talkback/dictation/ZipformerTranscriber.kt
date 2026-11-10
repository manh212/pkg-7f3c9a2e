/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.dictation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/**
 * The Kaldi-style filterbank features the Zipformer model was trained on: 80 mel bins from 16 kHz
 * samples in -1 to 1, 25 ms Povey windows every 10 ms, no dithering, edges reflected rather than
 * cut. Verified against the model's published sample outputs.
 */
internal object ZipformerFeatures {
  private const val FRAME_LENGTH = 400
  private const val FRAME_SHIFT = 160
  private const val FFT_SIZE = 512
  private const val NUM_BINS = 80
  private const val PREEMPHASIS = 0.97f
  private const val LOG_FLOOR = 1.1920929e-07f

  private val window = FloatArray(FRAME_LENGTH) { i ->
    (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FRAME_LENGTH - 1))).pow(0.85).toFloat()
  }

  private val melFilters: Array<FloatArray> by lazy {
    fun mel(freq: Double) = 1127.0 * ln(1.0 + freq / 700.0)
    val low = mel(20.0)
    val delta = (mel(7600.0) - low) / (NUM_BINS + 1)
    Array(NUM_BINS) { bin ->
      val left = low + bin * delta
      val center = left + delta
      val right = center + delta
      FloatArray(FFT_SIZE / 2 + 1) { k ->
        val m = mel(k * 16000.0 / FFT_SIZE)
        when {
          m <= center -> if (m > left) ((m - left) / (center - left)).toFloat() else 0f
          else -> if (m < right) ((right - m) / (right - center)).toFloat() else 0f
        }
      }
    }
  }

  /** Log-mel features of [samples], one 80-value row per 10 ms frame. */
  fun compute(samples: FloatArray): Array<FloatArray> {
    val n = samples.size
    if (n == 0) return emptyArray()
    val numFrames = (n + FRAME_SHIFT / 2) / FRAME_SHIFT
    val features = Array(numFrames) { FloatArray(NUM_BINS) }
    val frame = FloatArray(FRAME_LENGTH)
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)
    for (t in 0 until numFrames) {
      val start = t * FRAME_SHIFT
      for (i in 0 until FRAME_LENGTH) frame[i] = reflected(samples, start + i)
      var mean = 0.0
      for (v in frame) mean += v
      mean /= FRAME_LENGTH
      var previous = (frame[0] - mean).toFloat()
      frame[0] = previous
      for (i in 1 until FRAME_LENGTH) {
        val current = (frame[i] - mean).toFloat()
        frame[i] = current - PREEMPHASIS * previous
        previous = current
      }
      for (i in 0 until FRAME_LENGTH) {
        re[i] = frame[i] * window[i]
        im[i] = 0f
      }
      for (i in FRAME_LENGTH until FFT_SIZE) {
        re[i] = 0f
        im[i] = 0f
      }
      fft(re, im)
      val power = FloatArray(FFT_SIZE / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
      for (bin in 0 until NUM_BINS) {
        val filter = melFilters[bin]
        var energy = 0f
        for (k in power.indices) energy += filter[k] * power[k]
        features[t][bin] = ln(maxOf(energy, LOG_FLOOR).toDouble()).toFloat()
      }
    }
    return features
  }

  /** [samples] at [index], reflecting at both ends as Kaldi does when edges are not snipped. */
  private fun reflected(samples: FloatArray, index: Int): Float {
    val n = samples.size
    if (n == 1) return samples[0]
    val period = 2 * n - 2
    var i = index % period
    if (i >= n) i = period - i
    return samples[i]
  }

  /** In-place iterative radix-2 FFT over [re] and [im], which must have power-of-two length. */
  private fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j or bit
      if (i < j) {
        re[i] = re[j].also { re[j] = re[i] }
        im[i] = im[j].also { im[j] = im[i] }
      }
    }
    var length = 2
    while (length <= n) {
      val angle = -2.0 * Math.PI / length
      val wRe = cos(angle).toFloat()
      val wIm = kotlin.math.sin(angle).toFloat()
      for (i in 0 until n step length) {
        var curRe = 1f
        var curIm = 0f
        for (k in 0 until length / 2) {
          val even = i + k
          val odd = even + length / 2
          val oRe = re[odd] * curRe - im[odd] * curIm
          val oIm = re[odd] * curIm + im[odd] * curRe
          re[odd] = re[even] - oRe
          im[odd] = im[even] - oIm
          re[even] += oRe
          im[even] += oIm
          val nextRe = curRe * wRe - curIm * wIm
          curIm = curRe * wIm + curIm * wRe
          curRe = nextRe
        }
      }
      length = length shl 1
    }
  }
}

/** The model's token table and how recognized token ids become dictation text. */
internal object ZipformerText {
  /** Parses a tokens.txt file: each line is a token piece and its id, separated by a space. */
  fun loadTokens(file: File): Map<Int, String> =
    file
      .readLines(Charsets.UTF_8)
      .filter { it.isNotEmpty() }
      .associate { line ->
        val split = line.lastIndexOf(' ')
        line.substring(split + 1).toInt() to line.substring(0, split)
      }

  /** Joins the [ids] pieces into text: pieces use '▁' where a space belongs. */
  fun detokenize(ids: List<Int>, tokens: Map<Int, String>): String =
    ids.joinToString("") { tokens[it] ?: "" }.replace('▁', ' ').trim()

  /**
   * The model writes in all capitals without punctuation. Dictation text is sentence-like
   * instead: everything lowered, with the first letter raised again.
   */
  fun format(text: String): String =
    text.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Recognizes Vietnamese speech with the Zipformer model in [directory], through ONNX Runtime.
 * The sessions are opened for each transcription and closed after it, so the model takes no
 * memory between dictations.
 */
class ZipformerTranscriber(private val directory: File) : SpeechTranscriber {
  @Volatile private var cancelled = false

  override fun transcribe(samples: FloatArray, language: String): String? {
    if (cancelled) return null
    if (
      ZipformerModels.Part.entries.any {
        File(directory, it.fileName).length() != it.sizeBytes
      }
    ) {
      return null
    }
    val tokens =
      try {
        ZipformerText.loadTokens(File(directory, ZipformerModels.Part.TOKENS.fileName))
      } catch (e: Exception) {
        LogUtils.e(TAG, "Could not read the Zipformer token table: %s", e)
        return null
      }
    val features = ZipformerFeatures.compute(samples)
    if (features.isEmpty()) return ""
    val environment = OrtEnvironment.getEnvironment()
    val options =
      OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      }
    val sessions =
      try {
        Triple(
          environment.createSession(file(ZipformerModels.Part.ENCODER).absolutePath, options),
          environment.createSession(file(ZipformerModels.Part.DECODER).absolutePath, options),
          environment.createSession(file(ZipformerModels.Part.JOINER).absolutePath, options),
        )
      } catch (e: Exception) {
        LogUtils.e(TAG, "Could not open the Zipformer model: %s", e)
        return null
      } finally {
        options.close()
      }
    val (encoder, decoder, joiner) = sessions
    try {
      val ids = decode(environment, encoder, decoder, joiner, features) ?: return null
      if (ids.isEmpty()) return ""
      return ZipformerText.format(ZipformerText.detokenize(ids, tokens))
    } catch (e: Exception) {
      LogUtils.e(TAG, "Zipformer recognition failed: %s", e)
      return null
    } finally {
      encoder.close()
      decoder.close()
      joiner.close()
    }
  }

  override fun cancel() {
    cancelled = true
  }

  private fun file(part: ZipformerModels.Part) = File(directory, part.fileName)

  /** Greedy transducer decoding: at each encoder frame, emit tokens until the blank wins. */
  private fun decode(
    environment: OrtEnvironment,
    encoder: OrtSession,
    decoder: OrtSession,
    joiner: OrtSession,
    features: Array<FloatArray>,
  ): List<Int>? {
    val flat = FloatArray(features.size * FEATURE_DIM)
    features.forEachIndexed { t, row -> row.copyInto(flat, t * FEATURE_DIM) }
    val encoderOut: Array<FloatArray>
    val encoderLength: Int
    OnnxTensor.createTensor(
        environment,
        FloatBuffer.wrap(flat),
        longArrayOf(1, features.size.toLong(), FEATURE_DIM.toLong()),
      )
      .use { input ->
        OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(features.size.toLong())),
            longArrayOf(1),
          )
          .use { lengths ->
            encoder.run(mapOf("x" to input, "x_lens" to lengths)).use { result ->
              @Suppress("UNCHECKED_CAST")
              encoderOut = (result[0].value as Array<Array<FloatArray>>)[0]
              encoderLength = (result[1].value as LongArray)[0].toInt()
            }
          }
      }
    val ids = mutableListOf<Int>()
    var context = longArrayOf(BLANK_ID, BLANK_ID)
    var t = 0
    while (t < encoderLength && !cancelled) {
      var emitted = 0
      while (!cancelled && emitted < MAX_SYMBOLS_PER_FRAME) {
        val token = step(environment, decoder, joiner, encoderOut[t], context) ?: return null
        if (token == BLANK_ID.toInt()) break
        ids.add(token)
        context = longArrayOf(context[1], token.toLong())
        emitted++
      }
      t++
    }
    return if (cancelled) null else ids
  }

  /** One decoder and joiner step: the winning token for [encoderFrame] after [context]. */
  private fun step(
    environment: OrtEnvironment,
    decoder: OrtSession,
    joiner: OrtSession,
    encoderFrame: FloatArray,
    context: LongArray,
  ): Int? {
    OnnxTensor.createTensor(environment, LongBuffer.wrap(context), longArrayOf(1, 2)).use { y ->
      decoder.run(mapOf("y" to y)).use { decoderResult ->
        val decoderOut = decoderResult[0] as OnnxTensor
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(encoderFrame),
            longArrayOf(1, ENCODER_DIM.toLong()),
          )
          .use { encoderTensor ->
            joiner
              .run(mapOf("encoder_out" to encoderTensor, "decoder_out" to decoderOut))
              .use { joinerResult ->
                @Suppress("UNCHECKED_CAST") val logits = (joinerResult[0].value as Array<FloatArray>)[0]
                var best = 0
                for (i in logits.indices) if (logits[i] > logits[best]) best = i
                return best
              }
          }
      }
    }
  }

  private companion object {
    const val TAG = "ZipformerTranscriber"
    const val FEATURE_DIM = 80
    const val ENCODER_DIM = 512
    const val BLANK_ID = 0L
    const val MAX_SYMBOLS_PER_FRAME = 30
  }
}
