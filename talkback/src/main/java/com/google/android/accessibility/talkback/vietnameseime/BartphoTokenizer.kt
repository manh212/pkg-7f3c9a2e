package com.google.android.accessibility.talkback.vietnameseime

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class BartphoTokenizer private constructor(
  private val scores: Map<String, Float>,
  private val vocabulary: Map<String, Int>,
  private val normalization: SentencePieceNormalization,
) {
  val decoderStartId = 2
  val eosId = 2
  private val tokens = vocabulary.entries.associate { it.value to it.key }
  private val maximumPieceLength = scores.keys.maxOf(String::length)
  private val unknownScore = scores.values.min() - 10f

  fun encode(text: String, maximumTokens: Int = 256): LongArray {
    require(text.length <= 8192) { "Text exceeds the tokenizer limit" }
    val input = normalization.normalize(text)
    val best = FloatArray(input.length + 1) { Float.NEGATIVE_INFINITY }
    val previous = IntArray(input.length + 1) { -1 }
    val unknown = BooleanArray(input.length + 1)
    best[0] = 0f
    var start = 0
    while (start < input.length) {
      val characterEnd = start + Character.charCount(input.codePointAt(start))
      var hasCharacter = false
      for (end in characterEnd..minOf(input.length, start + maximumPieceLength)) {
        val score = scores[input.substring(start, end)] ?: continue
        if (end == characterEnd) hasCharacter = true
        val candidate = best[start] + score
        if (candidate > best[end]) {
          best[end] = candidate
          previous[end] = start
          unknown[end] = false
        }
      }
      if (!hasCharacter && best[start] + unknownScore > best[characterEnd]) {
        best[characterEnd] = best[start] + unknownScore
        previous[characterEnd] = start
        unknown[characterEnd] = true
      }
      start = characterEnd
    }
    val reversed = mutableListOf<Pair<String, Boolean>>()
    var end = input.length
    while (end > 0) {
      val begin = previous[end]
      require(begin >= 0) { "Cannot tokenize text" }
      reversed.add(input.substring(begin, end) to unknown[end])
      end = begin
    }
    val ids = mutableListOf(0L)
    var wasUnknown = false
    for ((piece, isUnknown) in reversed.asReversed()) {
      if (!isUnknown || !wasUnknown) ids.add((vocabulary[piece] ?: 3).toLong())
      wasUnknown = isUnknown
    }
    ids.add(eosId.toLong())
    require(ids.size <= maximumTokens) { "Text exceeds the model token limit" }
    return ids.toLongArray()
  }

  fun decode(ids: List<Int>): String = ids.asSequence()
    .filter { it > 3 && tokens[it] != "<mask>" }
    .mapNotNull(tokens::get)
    .joinToString("").replace('\u2581', ' ').trim()

  companion object {
    fun load(modelFile: File, vocabularyFile: File): BartphoTokenizer {
      val reader = SentencePieceProto(modelFile.readBytes())
      val scores = HashMap<String, Float>(300_000)
      var normalizer = ByteArray(0)
      var modelType = 1
      while (!reader.finished) {
        val tag = reader.varint()
        when (tag) {
          10 -> {
            val piece = SentencePieceProto(reader.bytes())
            var token = ""
            var score = 0f
            var type = 1
            while (!piece.finished) {
              when (val field = piece.varint()) {
                10 -> token = piece.bytes().toString(Charsets.UTF_8)
                21 -> score = Float.fromBits(piece.fixed32())
                24 -> type = piece.varint()
                else -> piece.skip(field)
              }
            }
            if (type == 1 && token.isNotEmpty() && score.isFinite()) scores[token] = score
          }
          18 -> {
            val trainer = SentencePieceProto(reader.bytes())
            while (!trainer.finished) {
              when (val field = trainer.varint()) {
                24 -> modelType = trainer.varint()
                else -> trainer.skip(field)
              }
            }
          }
          26 -> {
            val spec = SentencePieceProto(reader.bytes())
            while (!spec.finished) {
              when (val field = spec.varint()) {
                18 -> normalizer = spec.bytes()
                else -> spec.skip(field)
              }
            }
          }
          else -> reader.skip(tag)
        }
      }
      require(modelType == 1 && scores.isNotEmpty()) { "Unsupported SentencePiece model" }
      val vocabulary = linkedMapOf("<s>" to 0, "<pad>" to 1, "</s>" to 2, "<unk>" to 3)
      vocabularyFile.useLines { lines ->
        lines.forEach { line ->
          val token = line.trim().substringBefore(' ')
          if (token.isNotEmpty()) vocabulary[token] = vocabulary.size
        }
      }
      vocabulary.putIfAbsent("<mask>", vocabulary.size)
      return BartphoTokenizer(scores, vocabulary, SentencePieceNormalization(normalizer))
    }
  }
}

internal class SentencePieceNormalization(data: ByteArray) {
  private val trie: IntArray
  private val replacements: ByteArray

  init {
    if (data.isEmpty()) {
      trie = IntArray(0)
      replacements = ByteArray(0)
    } else {
      require(data.size >= 4)
      val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
      val bytes = buffer.int
      require(bytes >= 1024 && bytes % 1024 == 0 && bytes < data.size - 4)
      trie = IntArray(bytes / 4) { buffer.int }
      replacements = data.copyOfRange(4 + bytes, data.size)
      require(replacements.last() == 0.toByte())
    }
  }

  fun normalize(text: String): String {
    val source = text.toByteArray(Charsets.UTF_8)
    val output = ByteArrayOutputStream(source.size)
    var start = 0
    while (start < source.size) {
      var matchedLength = 0
      var replacement = 0
      if (trie.isNotEmpty()) {
        var position = offset(trie[0])
        var index = start
        while (index < source.size) {
          val label = source[index].toInt() and 255
          position = position xor label
          if (position !in trie.indices) break
          val unit = trie[position]
          if ((unit and (Int.MIN_VALUE or 255)) != label) break
          position = position xor offset(unit)
          index++
          if (unit and 256 != 0 && position in trie.indices) {
            matchedLength = index - start
            replacement = trie[position] and Int.MAX_VALUE
          }
        }
      }
      if (matchedLength > 0) {
        require(replacement in replacements.indices)
        var end = replacement
        while (end < replacements.size && replacements[end] != 0.toByte()) end++
        output.write(replacements, replacement, end - replacement)
        start += matchedLength
      } else {
        output.write(source[start].toInt())
        start++
      }
    }
    val normalized = output.toString(Charsets.UTF_8.name()).trim(' ').replace(Regex(" +"), " ")
    return if (normalized.isEmpty()) "" else "\u2581" + normalized.replace(' ', '\u2581')
  }

  private fun offset(unit: Int): Int = (unit ushr 10) shl ((unit and 512) ushr 6)
}

private class SentencePieceProto(private val data: ByteArray) {
  private var position = 0
  val finished get() = position == data.size

  fun varint(): Int {
    var value = 0L
    for (shift in 0..63 step 7) {
      require(position < data.size) { "Truncated SentencePiece model" }
      val byte = data[position++].toInt() and 255
      value = value or ((byte and 127).toLong() shl shift)
      if (byte and 128 == 0) return value.toInt()
    }
    throw IllegalArgumentException("Invalid SentencePiece varint")
  }

  fun bytes(): ByteArray {
    val length = varint()
    require(length >= 0 && length <= data.size - position) { "Invalid SentencePiece field size" }
    return data.copyOfRange(position, position + length).also { position += length }
  }

  fun fixed32(): Int {
    require(data.size - position >= 4)
    return ByteBuffer.wrap(data, position, 4).order(ByteOrder.LITTLE_ENDIAN).int.also { position += 4 }
  }

  fun skip(tag: Int) {
    when (tag and 7) {
      0 -> varint()
      1 -> position += 8
      2 -> bytes()
      5 -> position += 4
      else -> throw IllegalArgumentException("Unsupported SentencePiece field")
    }
    require(position <= data.size) { "Truncated SentencePiece model" }
  }
}
