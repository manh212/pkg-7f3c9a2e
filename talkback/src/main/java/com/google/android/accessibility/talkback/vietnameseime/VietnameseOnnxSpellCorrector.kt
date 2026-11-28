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

package com.google.android.accessibility.talkback.vietnameseime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer
import java.util.Locale

/**
 * Runs the downloaded offline Vietnamese Seq2Seq ONNX spell-correction model
 * (e.g. nrl-ai/vn-spell-correction-small-onnx-int8) via onnxruntime-android.
 * Loaded lazily on demand when the user triggers full-field spell checking.
 */
object VietnameseOnnxSpellCorrector {
  private const val MAX_DECODE_STEPS = 256

  data class CorrectionResult(
    val correctedText: String,
    val changedWords: Int,
    val usedAiModel: Boolean,
  )

  private val lock = Any()
  @Volatile private var encoderSession: OrtSession? = null
  @Volatile private var decoderSession: OrtSession? = null
  @Volatile private var tokenizer: BartphoTokenizer? = null

  fun release() {
    synchronized(lock) {
      runCatching { encoderSession?.close() }
      runCatching { decoderSession?.close() }
      encoderSession = null
      decoderSession = null
      tokenizer = null
    }
  }

  fun correctFullText(context: Context, text: String): CorrectionResult? {
    if (text.isBlank()) return CorrectionResult(text, 0, usedAiModel = false)
    if (!VietnameseSpellModelManager.isReady(context)) return null
    return synchronized(lock) {
      try {
        ensureLoaded(context)
        val enc = encoderSession ?: return@synchronized null
        val dec = decoderSession ?: return@synchronized null
        val tok = tokenizer ?: return@synchronized null

        val lines = text.split("\n")
        val correctedLines = lines.map { line ->
          if (line.isBlank()) line else correctLine(enc, dec, tok, line)
        }
        val joined = correctedLines.joinToString("\n")
        val changed = countChangedWords(text, joined)
        CorrectionResult(joined, changed, usedAiModel = true)
      } catch (_: Exception) {
        null
      }
    }
  }

  private fun ensureLoaded(context: Context) {
    if (encoderSession != null && decoderSession != null && tokenizer != null) return
    val env = OrtEnvironment.getEnvironment()
    val opts = OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
      setInterOpNumThreads(1)
      setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    opts.use { sessionOptions ->
      val preparedTokenizer = BartphoTokenizer.load(
        VietnameseSpellModelManager.tokenizerFile(context),
        VietnameseSpellModelManager.vocabularyFile(context),
      )
      val encoder = env.createSession(VietnameseSpellModelManager.encoderFile(context).absolutePath, sessionOptions)
      try {
        val decoder = env.createSession(VietnameseSpellModelManager.decoderFile(context).absolutePath, sessionOptions)
        encoderSession = encoder
        decoderSession = decoder
        tokenizer = preparedTokenizer
      } catch (error: Exception) {
        encoder.close()
        throw error
      }
    }
  }

  private fun correctLine(
    encoder: OrtSession,
    decoder: OrtSession,
    tok: BartphoTokenizer,
    line: String,
  ): String {
    val env = OrtEnvironment.getEnvironment()
    val inputIds = tok.encode(line)
    val attentionMask = LongArray(inputIds.size) { 1L }
    val shape = longArrayOf(1L, inputIds.size.toLong())

    OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape).use { inputIdsTensor ->
      OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape).use { maskTensor ->
        val encInputs = LinkedHashMap<String, OnnxTensor>()
        for (name in encoder.inputNames) {
          when {
            name.contains("mask", ignoreCase = true) -> encInputs[name] = maskTensor
            else -> encInputs[name] = inputIdsTensor
          }
        }
        encoder.run(encInputs).use { encOut ->
          val hiddenState = encOut[0] as? OnnxTensor ?: return line
          val generated = ArrayList<Int>(inputIds.size + 16)
          generated += tok.decoderStartId
          val maxSteps = minOf(MAX_DECODE_STEPS, inputIds.size * 2 + 16)
          var ended = false

          for (step in 0 until maxSteps) {
            val decIds = LongArray(generated.size) { generated[it].toLong() }
            val decShape = longArrayOf(1L, decIds.size.toLong())
            val nextToken = OnnxTensor.createTensor(env, LongBuffer.wrap(decIds), decShape).use { decTensor ->
              val decInputs = LinkedHashMap<String, OnnxTensor>()
              for (name in decoder.inputNames) {
                when {
                  name.contains("encoder_hidden", ignoreCase = true) -> decInputs[name] = hiddenState
                  name.contains("encoder_attention", ignoreCase = true) -> decInputs[name] = maskTensor
                  name.contains("input_ids", ignoreCase = true) -> decInputs[name] = decTensor
                }
              }
              decoder.run(decInputs, setOf("logits")).use { decOut ->
                val logits = decOut[0].value
                argmaxLastStep(logits)
              }
            }
            if (nextToken < 0) return line
            if (nextToken == tok.eosId) {
              ended = true
              break
            }
            generated += nextToken
          }
          if (!ended) return line
          val decoded = tok.decode(generated)
          return if (decoded.isBlank()) line else decoded
        }
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun argmaxLastStep(logitsValue: Any?): Int {
    val batch = logitsValue as? Array<Array<FloatArray>> ?: return -1
    if (batch.isEmpty() || batch[0].isEmpty()) return -1
    val last = batch[0][batch[0].size - 1]
    var bestIdx = -1
    var bestVal = Float.NEGATIVE_INFINITY
    for (i in last.indices) {
      if (last[i] > bestVal) {
        bestVal = last[i]
        bestIdx = i
      }
    }
    return bestIdx
  }

  internal fun countChangedWords(original: String, corrected: String): Int {
    val a = original.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val b = corrected.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val minLen = minOf(a.size, b.size)
    var diff = kotlin.math.abs(a.size - b.size)
    for (i in 0 until minLen) {
      if (a[i].lowercase(Locale.ROOT) != b[i].lowercase(Locale.ROOT)) {
        diff++
      }
    }
    return diff
  }
}
