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

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
object VietnameseLexicon {
  private const val ASSET_FILE = "vi_keyboard_lexicon.txt"
  private const val PERSONAL_FILE = "vietnamese_personal_model.json"
  private const val MAX_PERSONAL_UNIGRAMS = 2_000
  private const val MAX_PERSONAL_BIGRAMS = 4_000

  private val lock = Any()
  @Volatile private var baseLoaded = false
  @Volatile private var personalLoaded = false

  private val baseUnigrams = LinkedHashMap<String, Int>()
  private val baseBigrams = HashMap<String, LinkedHashMap<String, Int>>()
  private val asciiIndex = HashMap<String, MutableList<String>>()

  private val personalUnigrams = LinkedHashMap<String, Int>()
  private val personalBigrams = HashMap<String, LinkedHashMap<String, Int>>()

  internal fun ensureLoaded(context: Context?) {
    if (!baseLoaded && context != null) {
      synchronized(lock) {
        if (!baseLoaded) {
          loadBaseFromAssets(context.applicationContext)
          baseLoaded = true
        }
      }
    }
    if (!personalLoaded && context != null) {
      synchronized(lock) {
        if (!personalLoaded) {
          loadPersonal(context.applicationContext)
          personalLoaded = true
        }
      }
    }
  }
fun suggestWords(
    context: Context?,
    previousWord: String?,
    currentPrefix: String,
    maxResults: Int = 4,
  ): List<String> {
    ensureLoaded(context)
    val prevKey = previousWord?.let(::normalizeWord)?.takeIf { it.isNotEmpty() }
    val rawPrefix = normalizeWord(currentPrefix)
    val asciiPrefix = foldAscii(rawPrefix)

    synchronized(lock) {
      val scored = HashMap<String, Int>()

      if (rawPrefix.isEmpty()) {
        if (prevKey != null) {
          baseBigrams[prevKey]?.forEach { (next, freq) ->
            scored[next] = (scored[next] ?: 0) + freq
          }
          personalBigrams[prevKey]?.forEach { (next, count) ->
            scored[next] = (scored[next] ?: 0) + count * 12
          }
        }
        if (scored.isEmpty()) {
          personalUnigrams.entries.sortedByDescending { it.value }.take(maxResults).forEach { (w, c) ->
            scored[w] = (scored[w] ?: 0) + c * 10
          }
          if (scored.size < maxResults) {
            baseUnigrams.entries.take(maxResults * 2).forEach { (w, f) ->
              scored[w] = (scored[w] ?: 0) + f
            }
          }
        }
      } else {
        val candidates = LinkedHashSet<String>()
        for (word in personalUnigrams.keys) {
          if (word != rawPrefix && (word.startsWith(rawPrefix) || foldAscii(word).startsWith(asciiPrefix))) {
            candidates += word
          }
        }
        for (word in baseUnigrams.keys) {
          if (word != rawPrefix && (word.startsWith(rawPrefix) || foldAscii(word).startsWith(asciiPrefix))) {
            candidates += word
          }
        }
        if (prevKey != null) {
          personalBigrams[prevKey]?.keys?.forEach { word ->
            if (word != rawPrefix && (word.startsWith(rawPrefix) || foldAscii(word).startsWith(asciiPrefix))) {
              candidates += word
            }
          }
          baseBigrams[prevKey]?.keys?.forEach { word ->
            if (word != rawPrefix && (word.startsWith(rawPrefix) || foldAscii(word).startsWith(asciiPrefix))) {
              candidates += word
            }
          }
        }

        for (candidate in candidates) {
          var score = baseUnigrams[candidate] ?: 40
          score += (personalUnigrams[candidate] ?: 0) * 10
          if (prevKey != null) {
            score += (baseBigrams[prevKey]?.get(candidate) ?: 0) * 2
            score += (personalBigrams[prevKey]?.get(candidate) ?: 0) * 16
          }
          if (candidate.startsWith(rawPrefix)) {
            score += 25
          }
          if (foldAscii(candidate) == asciiPrefix) {
            score += 45
          }
          scored[candidate] = score
        }
      }

      return scored.entries
        .filter { it.value > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length }.thenBy { it.key })
        .take(maxResults)
        .map { it.key }
    }
  }
fun recordChosenSuggestion(
    context: Context?,
    previousWord: String?,
    chosenWord: String,
    rejectedTopWord: String? = null,
  ) {
    val cleanChosen = normalizeWord(chosenWord)
    if (cleanChosen.isEmpty()) return
    ensureLoaded(context)
    val prev = previousWord?.let(::normalizeWord)?.takeIf { it.isNotEmpty() }
    val rejected = rejectedTopWord?.let(::normalizeWord)?.takeIf { it.isNotEmpty() && it != cleanChosen }
    synchronized(lock) {
      bumpUnigram(cleanChosen, delta = 3)
      if (prev != null) {
        bumpBigram(prev, cleanChosen, delta = 5)
        if (rejected != null) {
          bumpBigram(prev, rejected, delta = -2)
        }
      }
      if (context != null) savePersonalLocked(context.applicationContext)
    }
  }

  private fun bumpUnigram(word: String, delta: Int) {
    val next = (personalUnigrams[word] ?: 0) + delta
    personalUnigrams[word] = next.coerceIn(-20, 5_000)
    if (personalUnigrams.size > MAX_PERSONAL_UNIGRAMS) {
      val iterator = personalUnigrams.entries.iterator()
      if (iterator.hasNext()) {
        iterator.next()
        iterator.remove()
      }
    }
  }

  private fun bumpBigram(prev: String, next: String, delta: Int) {
    val map = personalBigrams.getOrPut(prev) { LinkedHashMap() }
    val updated = (map[next] ?: 0) + delta
    map[next] = updated.coerceIn(-20, 5_000)
    var total = 0
    personalBigrams.values.forEach { total += it.size }
    if (total > MAX_PERSONAL_BIGRAMS) {
      val firstKey = personalBigrams.keys.firstOrNull()
      if (firstKey != null && firstKey != prev) {
        personalBigrams.remove(firstKey)
      }
    }
  }

  private fun loadBaseFromAssets(context: Context) {
    try {
      context.assets.open(ASSET_FILE).bufferedReader(Charsets.UTF_8).useLines { seq ->
        parseLines(seq.toList())
      }
    } catch (_: Exception) {
    }
  }

  private fun parseLines(lines: List<String>) {
    for (raw in lines) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#")) continue
      val parts = line.split('\t')
      when (parts.firstOrNull()) {
        "U" -> if (parts.size >= 3) {
          val w = normalizeWord(parts[1])
          val f = parts[2].toIntOrNull() ?: continue
          if (w.isNotEmpty()) {
            baseUnigrams[w] = maxOf(baseUnigrams[w] ?: 0, f)
            asciiIndex.getOrPut(foldAscii(w)) { mutableListOf() }.add(w)
          }
        }
        "B" -> if (parts.size >= 4) {
          val p = normalizeWord(parts[1])
          val n = normalizeWord(parts[2])
          val f = parts[3].toIntOrNull() ?: continue
          if (p.isNotEmpty() && n.isNotEmpty()) {
            val inner = baseBigrams.getOrPut(p) { LinkedHashMap() }
            inner[n] = maxOf(inner[n] ?: 0, f)
          }
        }
      }
    }
  }

  private fun loadPersonal(context: Context) {
    val file = File(context.filesDir, PERSONAL_FILE)
    if (!file.exists()) return
    try {
      val root = JSONObject(file.readText(Charsets.UTF_8))
      root.optJSONObject("u")?.let { uObj ->
        val keys = uObj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          personalUnigrams[k] = uObj.optInt(k, 0)
        }
      }
      root.optJSONObject("b")?.let { bObj ->
        val prevKeys = bObj.keys()
        while (prevKeys.hasNext()) {
          val prev = prevKeys.next()
          val innerObj = bObj.optJSONObject(prev) ?: continue
          val innerMap = LinkedHashMap<String, Int>()
          val nextKeys = innerObj.keys()
          while (nextKeys.hasNext()) {
            val next = nextKeys.next()
            innerMap[next] = innerObj.optInt(next, 0)
          }
          if (innerMap.isNotEmpty()) {
            personalBigrams[prev] = innerMap
          }
        }
      }
    } catch (_: Exception) {
    }
  }

  private fun savePersonalLocked(context: Context) {
    try {
      val uObj = JSONObject()
      personalUnigrams.forEach { (k, v) -> uObj.put(k, v) }
      val bObj = JSONObject()
      personalBigrams.forEach { (prev, inner) ->
        val innerObj = JSONObject()
        inner.forEach { (next, v) -> innerObj.put(next, v) }
        bObj.put(prev, innerObj)
      }
      val root = JSONObject().put("u", uObj).put("b", bObj)
      File(context.filesDir, PERSONAL_FILE).writeText(root.toString(), Charsets.UTF_8)
    } catch (_: Exception) {
    }
  }

  internal fun normalizeWord(raw: String): String =
    Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

  internal fun foldAscii(word: String): String {
    val decomposed = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val sb = StringBuilder(decomposed.length)
    for (ch in decomposed) {
      when {
        ch == 'đ' -> sb.append('d')
        Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }
}
