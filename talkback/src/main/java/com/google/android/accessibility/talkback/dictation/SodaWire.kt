package com.google.android.accessibility.talkback.dictation

import java.io.ByteArrayOutputStream
import java.io.IOException

internal object SodaWire {
  data class Field(val number: Int, val value: Long = 0, val bytes: ByteArray? = null)

  fun message(vararg fields: Field): ByteArray {
    val output = ByteArrayOutputStream()
    fun writeVarint(number: Long) {
      var remaining = number
      while (remaining and -128L != 0L) {
        output.write((remaining.toInt() and 127) or 128)
        remaining = remaining ushr 7
      }
      output.write(remaining.toInt())
    }
    fields.forEach { field ->
      require(field.number in 1..536870911)
      writeVarint((field.number.toLong() shl 3) or if (field.bytes == null) 0 else 2)
      field.bytes?.let {
        writeVarint(it.size.toLong())
        output.write(it)
      } ?: writeVarint(field.value)
    }
    return output.toByteArray()
  }

  fun text(number: Int, value: String) = Field(number, bytes = value.toByteArray(Charsets.UTF_8))
  fun nested(number: Int, vararg fields: Field) = Field(number, bytes = message(*fields))

  fun fields(data: ByteArray): List<Field> {
    if (data.size > 1_048_576) throw IOException("Oversized SODA message")
    var position = 0
    fun readVarint(): Long {
      var value = 0L
      for (shift in 0..63 step 7) {
        if (position >= data.size) throw IOException("Truncated SODA varint")
        val next = data[position++].toInt() and 255
        if (shift == 63 && next > 1) throw IOException("Invalid SODA varint")
        value = value or ((next and 127).toLong() shl shift)
        if (next and 128 == 0) return value
      }
      throw IOException("Invalid SODA varint")
    }
    fun take(length: Long): ByteArray {
      if (length < 0 || length > data.size - position) throw IOException("Truncated SODA field")
      val end = position + length.toInt()
      return data.copyOfRange(position, end).also { position = end }
    }
    val result = ArrayList<Field>()
    while (position < data.size) {
      val tag = readVarint()
      val number = tag ushr 3
      if (number !in 1..536870911) throw IOException("Invalid SODA tag")
      result += when ((tag and 7).toInt()) {
        0 -> Field(number.toInt(), readVarint())
        1 -> Field(number.toInt(), bytes = take(8))
        2 -> Field(number.toInt(), bytes = take(readVarint()))
        5 -> Field(number.toInt(), bytes = take(4))
        else -> throw IOException("Unsupported SODA wire type")
      }
    }
    return result
  }

  private fun audioFormat() = arrayOf(Field(2, 1), Field(3, 16000), Field(4, 1))

  fun coreConfig(modelPath: String, cachePath: String, deviceModel: String = ""): ByteArray = message(
    nested(1, nested(1, *audioFormat())),
    nested(3, Field(10, 0), text(13, modelPath)),
    text(7, cachePath),
    nested(10, text(2, "Android"), text(3, deviceModel), text(4, ""), Field(6, 6)),
  )

  fun captureConfig(): ByteArray = message(
    Field(2, 0),
    nested(4, nested(1, Field(1, 0), Field(2, 2), nested(4, *audioFormat()), Field(8, 0))),
    Field(12, 0),
  )

  fun requireSuccess(data: ByteArray?) {
    if (data == null) throw IOException("Missing SODA status")
    val status = fields(data)
    val code = status.firstOrNull { it.number == 1 }?.value ?: 0L
    if (code != 0L) {
      val detail = status.firstOrNull { it.number == 2 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
      throw IOException("SODA status $code: ${detail.take(240)}")
    }
  }

  data class Result(val text: String, val final: Boolean)

  fun result(data: ByteArray): Result? {
    val event = fields(data)
    val recognition = event.firstOrNull { it.number == 2 }?.bytes ?: return null
    val details = fields(recognition)
    val final = details.firstOrNull { it.number == 1 }?.bytes
    val partial = details.firstOrNull { it.number == 2 || it.number == 4 }?.bytes
    val hypothesis = fields(final ?: partial ?: return null)
    val text = hypothesis.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8)
      ?: return null
    return Result(text.trim(), final != null)
  }
}
