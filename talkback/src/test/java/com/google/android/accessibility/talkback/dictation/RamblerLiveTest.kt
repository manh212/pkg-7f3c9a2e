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

import android.os.Looper
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RamblerLiveTest {
  /**
   * A WebSocket server for one connection that answers like the Live API: setupComplete after the
   * setup, the text of a phrase after the first audio, and the last phrase after the end of the
   * audio. Keeps the headers and messages it got.
   */
  private class FakeLiveApi(
    private val answerAtEnd: Boolean = true,
    /**
     * The inputTranscription texts, in order. All but the last are sent after the first audio;
     * the last is sent after the end of the audio, when [answerAtEnd]. Defaults to the two
     * distinct phrases of the original test.
     */
    private val transcriptions: List<String> = listOf("Hello there.", " How are you? "),
  ) : AutoCloseable {
    private val server = ServerSocket(0)
    val headers = mutableMapOf<String, String>()
    val messages = CopyOnWriteArrayList<JSONObject>()
    val url = "ws://127.0.0.1:${server.localPort}/ws"

    init {
      Thread {
          runCatching {
            server.accept().use { socket ->
              val input = DataInputStream(socket.getInputStream())
              val output = socket.getOutputStream()
              handshake(input, output)
              var sentFirst = false
              while (true) {
                val message = JSONObject(readFrame(input) ?: break)
                messages.add(message)
                when {
                  message.has("setup") -> send(output, """{"setupComplete":{}}""", binary = true)
                  message.optJSONObject("realtimeInput")?.has("audio") == true && !sentFirst -> {
                    sentFirst = true
                    transcriptions.dropLast(1).forEach { text ->
                      send(
                        output,
                        """{"serverContent":{"inputTranscription":{"text":${JSONObject.quote(text)}}}}""",
                      )
                    }
                    send(output, """{"serverContent":{"interimInputTranscription":{"text":"how are"}}}""")
                  }
                  message.optJSONObject("realtimeInput")?.has("audioStreamEnd") == true && answerAtEnd ->
                    send(
                      output,
                      """{"serverContent":{"inputTranscription":{"text":${JSONObject.quote(transcriptions.last())}}}}""",
                    )
                }
              }
            }
          }
        }
        .apply {
          isDaemon = true
          start()
        }
    }

    private fun handshake(input: DataInputStream, output: OutputStream) {
      val lines = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
      lines.drop(1).forEach { line ->
        headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
      }
      val accept =
        Base64.getEncoder()
          .encodeToString(
            MessageDigest.getInstance("SHA-1")
              .digest((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
          )
      output.write(
        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n")
          .toByteArray()
      )
      output.flush()
    }

    private fun readLine(input: DataInputStream): String {
      val line = StringBuilder()
      while (true) {
        val c = input.read()
        if (c < 0 || c == '\n'.code) break
        if (c != '\r'.code) line.append(c.toChar())
      }
      return line.toString()
    }

    /** Reads a client frame's text, or null when the client closes. */
    private fun readFrame(input: DataInputStream): String? {
      val first = input.read()
      if (first < 0 || first and 0x0f == 8) return null
      val second = input.read()
      var length = (second and 0x7f).toLong()
      if (length == 126L) length = input.readUnsignedShort().toLong()
      else if (length == 127L) length = input.readLong()
      val mask = ByteArray(4).also { input.readFully(it) }
      val data = ByteArray(length.toInt()).also { input.readFully(it) }
      for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
      return String(data, Charsets.UTF_8)
    }

    private fun send(output: OutputStream, text: String, binary: Boolean = false) {
      val data = text.toByteArray()
      output.write(if (binary) 0x82 else 0x81)
      // RFC 6455 §5.2: a single length byte only holds payloads up to 125 bytes. Longer
      // payloads need the 126 marker plus a 2-byte big-endian length (127 plus 8 bytes
      // above 65535). Writing the size as one byte made every payload over 125 bytes
      // look like a masked server frame, which the client rejects as a protocol error
      // and which kills the whole session instead of just losing one message.
      when {
        data.size < 126 -> output.write(data.size)
        data.size < 65536 -> {
          output.write(126)
          output.write(data.size shr 8)
          output.write(data.size)
        }
        else -> {
          output.write(127)
          val size = data.size.toLong()
          for (shift in 56 downTo 0 step 8) output.write((size shr shift).toInt())
        }
      }
      output.write(data)
      output.flush()
    }

    override fun close() = server.close()
  }

  /** Runs the main thread's tasks until [done] or five seconds pass. */
  private fun waitFor(done: () -> Boolean) {
    repeat(100) {
      if (done()) return
      Thread.sleep(50)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }
  }

  @Test
  fun phrasesFromTheSessionAreJoinedAfterTheEndOfTheAudio() {
    FakeLiveApi().use { api ->
      val live = RamblerLive("key", api.url)
      live.start()
      val pcm = ByteArray(3200) { it.toByte() }
      live.send(pcm)
      Thread.sleep(300)
      live.send(pcm)
      waitFor { api.messages.size >= 3 }
      var result: String? = "not yet"
      live.finish { result = it }
      waitFor { result != "not yet" }

      assertEquals("Hello there. How are you?", result)
      assertEquals("key", api.headers["x-goog-api-key"])
      val setup = api.messages[0].getJSONObject("setup")
      assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
      assertEquals("SMART", setup.getJSONObject("inputAudioTranscription").getString("mode"))
      val audio = api.messages[1].getJSONObject("realtimeInput").getJSONObject("audio")
      assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
      assertTrue(audio.getString("data").isNotEmpty())
      assertTrue(api.messages.last().getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))
    }
  }

  @Test
  fun aLastPhraseThatNeverComesMeansTheRecordingIsSentInstead() {
    FakeLiveApi(answerAtEnd = false).use { api ->
      val live = RamblerLive("key", api.url)
      live.start()
      live.send(ByteArray(3200))
      // The phrase must have been heard before the end of the audio, or there is none to wait for.
      waitFor { live.isPhrasePending }
      var result: String? = "not yet"
      live.finish { result = it }
      waitFor { result != "not yet" }
      assertNull(result)
    }
  }

  @Test
  fun aSessionThatCantConnectFails() {
    val live = RamblerLive("key", "ws://127.0.0.1:1/ws")
    live.start()
    Thread.sleep(500)
    var result: String? = "not yet"
    live.finish { result = it }
    waitFor { result != "not yet" }
    assertNull(result)
  }

  /** Runs one session whose server sends [transcriptions] in order and returns the text. */
  private fun transcribe(transcriptions: List<String>): String? {
    FakeLiveApi(transcriptions = transcriptions).use { api ->
      val live = RamblerLive("key", api.url)
      live.start()
      val pcm = ByteArray(3200) { it.toByte() }
      live.send(pcm)
      Thread.sleep(300)
      live.send(pcm)
      waitFor { api.messages.size >= 3 }
      var result: String? = "not yet"
      live.finish { result = it }
      waitFor { result != "not yet" }
      return result
    }
  }

  @Test
  fun cumulativeTranscriptionsAreNotRepeated() {
    // The reporter's session 3 (Messenger, build 2569): the server sent the running text again
    // with each phrase, and the joined text repeated it.
    assertEquals(
      "Công việc của em dạo này thế nào? Vẫn làm ở chỗ cũ chứ hay là chuyển chỗ mới rồi? " +
        "Mà mức lương giờ có cao hơn trước không hay là vẫn như vậy?",
      transcribe(
        listOf(
          "Công việc của em dạo này thế nào?",
          "Công việc của em dạo này thế nào? Vẫn làm ở chỗ cũ chứ hay là chuyển chỗ mới rồi?",
          "Công việc của em dạo này thế nào? Vẫn làm ở chỗ cũ chứ hay là chuyển chỗ mới rồi?",
          "Mà mức lương giờ có cao hơn trước không hay là vẫn như vậy?",
        )
      ),
    )
  }

  @Test
  fun cumulativeExtensionReplacesTheShorterText() {
    // The reporter's session 1: the second message was the first phrase plus the next one.
    assertEquals(
      "Đang nhắn tin cho em thì buồn ngủ quá nên không nhắn nữa. " +
        "Mà dạo này có còn dậy sớm như trước nữa không em?",
      transcribe(
        listOf(
          "Đang nhắn tin cho em thì buồn ngủ quá nên không nhắn nữa.",
          "Đang nhắn tin cho em thì buồn ngủ quá nên không nhắn nữa. " +
            "Mà dạo này có còn dậy sớm như trước nữa không em?",
        )
      ),
    )
  }

  @Test
  fun exactDuplicateTranscriptionIsSkipped() {
    assertEquals(
      "Hello there. How are you?",
      transcribe(listOf("Hello there.", "Hello there.", " How are you? ")),
    )
  }

  @Test
  fun subsetTranscriptionIsSkipped() {
    assertEquals(
      "Hello there. How are you?",
      transcribe(listOf("Hello there. How are you?", "Hello there.")),
    )
  }

  @Test
  fun transcriptionContainingTheAccumulatedTextReplacesIt() {
    assertEquals(
      "Well, How are you? doing fine",
      transcribe(listOf("How are you?", "Well, How are you? doing fine")),
    )
  }

  @Test
  fun sameLettersWithoutAWordBoundaryAreNotMerged() {
    // "catch" starts with the letters of "cat" but is a different word.
    assertEquals("cat catch the ball", transcribe(listOf("cat", "catch the ball")))
  }
}
