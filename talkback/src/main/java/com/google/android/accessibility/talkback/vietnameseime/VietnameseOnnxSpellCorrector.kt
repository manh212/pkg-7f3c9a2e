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
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale

/**
 * Runs the downloaded offline Vietnamese Seq2Seq ONNX spell-correction model
 * (e.g. nrl-ai/vn-spell-correction-small-onnx-int8) via onnxruntime-android.
 * Loaded lazily on demand when the user triggers full-field spell checking.
 */
object VietnameseOnnxSpellCorrector {
  private const val MAX_INPUT_TOKENS = 256
  private const val MAX_DECODE_STEPS = 256
  private const val SPIECE_UNDERLINE = '\u2581'

  data class CorrectionResult(
    val correctedText: String,
    val changedWords: Int,
    val usedAiModel: Boolean,
  )

  private class SimpleTokenizer(
    private val tokenToId: Map<String, Int>,
    private val idToToken: Map<Int, String>,
    val unkId: Int,
    val eosId: Int,
    val padId: Int,
    val bosId: Int,
    val decoderStartId: Int,
    private val isSentencePiece: Boolean,
  ) {
    fun encode(text: String): LongArray {
      val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
      if (normalized.isEmpty()) return longArrayOf(eosId.toLong())
      val ids = ArrayList<Long>(normalized.length)
      if (bosId >= 0 && bosId != decoderStartId) {
        ids += bosId.toLong()
      }
      val words = normalized.split(Regex("\\s+"))
      for ((idx, rawWord) in words.withIndex()) {
        if (rawWord.isEmpty()) continue
        val piece = if (isSentencePiece) "$SPIECE_UNDERLINE$rawWord" else if (idx > 0) " $rawWord" else rawWord
        val exact = tokenToId[piece]
        if (exact != null) {
          ids += exact.toLong()
        } else {
          encodeGreedySubwords(piece, ids)
        }
        if (ids.size >= MAX_INPUT_TOKENS - 1) break
      }
      ids += eosId.toLong()
      return ids.toLongArray()
    }

    private fun encodeGreedySubwords(word: String, out: MutableList<Long>) {
      var pos = 0
      val len = word.length
      while (pos < len && out.size < MAX_INPUT_TOKENS - 1) {
        var matchedId: Int? = null
        var matchedEnd = pos + 1
        var end = len
        while (end > pos) {
          val sub = word.substring(pos, end)
          val id = tokenToId[sub] ?: if (pos > 0 && !isSentencePiece) tokenToId["##$sub"] else null
          if (id != null) {
            matchedId = id
            matchedEnd = end
            break
          }
          end--
        }
        out += (matchedId ?: unkId).toLong()
        pos = matchedEnd
      }
    }

    fun decode(ids: List<Int>): String {
      val sb = StringBuilder()
      for (id in ids) {
        if (id == padId || id == eosId || id == bosId || id == decoderStartId) continue
        val tok = idToToken[id] ?: continue
        if (tok.startsWith("<") && tok.endsWith(">")) continue
        if (isSentencePiece) {
          sb.append(tok.replace(SPIECE_UNDERLINE, ' '))
        } else if (tok.startsWith("##")) {
          sb.append(tok.substring(2))
        } else {
          sb.append(tok)
        }
      }
      return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
  }

  private val lock = Any()
  @Volatile private var encoderSession: OrtSession? = null
  @Volatile private var decoderSession: OrtSession? = null
  @Volatile private var tokenizer: SimpleTokenizer? = null

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
      encoderSession = env.createSession(VietnameseSpellModelManager.encoderFile(context).absolutePath, sessionOptions)
      decoderSession = env.createSession(VietnameseSpellModelManager.decoderFile(context).absolutePath, sessionOptions)
    }
    tokenizer = loadTokenizer(
      VietnameseSpellModelManager.tokenizerFile(context),
      VietnameseSpellModelManager.configFile(context),
    )
  }

  private fun loadTokenizer(tokFile: File, cfgFile: File): SimpleTokenizer {
    val cfgJson = JSONObject(cfgFile.readText(Charsets.UTF_8))
    val tokJson = JSONObject(tokFile.readText(Charsets.UTF_8))
    val tokenToId = HashMap<String, Int>(32768)
    val idToToken = HashMap<Int, String>(32768)
    var isSentencePiece = false

    val modelObj = tokJson.optJSONObject("model")
    val vocabAny = modelObj?.opt("vocab")
    if (vocabAny is JSONObject) {
      val keys = vocabAny.keys()
      while (keys.hasNext()) {
        val k = keys.next()
        val id = vocabAny.optInt(k, -1)
        if (id >= 0) {
          tokenToId[k] = id
          idToToken[id] = k
          if (k.startsWith(SPIECE_UNDERLINE)) isSentencePiece = true
        }
      }
    } else if (vocabAny is org.json.JSONArray) {
      isSentencePiece = true
      for (i in 0 until vocabAny.length()) {
        val entry = vocabAny.optJSONArray(i) ?: continue
        val token = entry.optString(0, "")
        if (token.isNotEmpty()) {
          tokenToId[token] = i
          idToToken[i] = token
        }
      }
    }
    val added = tokJson.optJSONArray("added_tokens")
    if (added != null) {
      for (i in 0 until added.length()) {
        val item = added.optJSONObject(i) ?: continue
        val content = item.optString("content", "")
        val id = item.optInt("id", -1)
        if (content.isNotEmpty() && id >= 0) {
          tokenToId[content] = id
          idToToken[id] = content
        }
      }
    }

    val padId = cfgJson.optInt("pad_token_id", tokenToId["<pad>"] ?: 0)
    val eosId = cfgJson.optInt("eos_token_id", tokenToId["</s>"] ?: 1)
    val bosId = cfgJson.optInt("bos_token_id", tokenToId["<s>"] ?: -1)
    val unkId = tokenToId["<unk>"] ?: tokenToId["[UNK]"] ?: 2
    val decoderStartId = cfgJson.optInt("decoder_start_token_id", if (bosId >= 0) bosId else padId)

    return SimpleTokenizer(
      tokenToId = tokenToId,
      idToToken = idToToken,
      unkId = unkId,
      eosId = eosId,
      padId = padId,
      bosId = bosId,
      decoderStartId = decoderStartId,
      isSentencePiece = isSentencePiece,
    )
  }

  private fun correctLine(
    encoder: OrtSession,
    decoder: OrtSession,
    tok: SimpleTokenizer,
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
              decoder.run(decInputs).use { decOut ->
                val logits = decOut[0].value
                argmaxLastStep(logits)
              }
            }
            if (nextToken < 0 || nextToken == tok.eosId) break
            generated += nextToken
          }
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
