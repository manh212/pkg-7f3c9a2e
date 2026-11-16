/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.oai.geminilivetranslate.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalPcmDeadlineQueueTest {

  private val sampleRate = 16_000
  private val chunkBytes = 16_000

  private fun chunk(marker: Byte) = ByteArray(chunkBytes) { marker }

  @Test
  fun enqueue_beyondCap_dropsOldestChunks() {
    val queue = OriginalPcmDeadlineQueue(sampleRate)
    for (i in 1..25) {
      queue.enqueue(chunk(i.toByte()), 1f, 0L)
    }
    val stats = queue.stats()
    assertEquals(20, stats.queuedChunks)
    assertEquals(320_000L, stats.queuedBytes)
    assertEquals(5L, stats.droppedChunks)
  }

  @Test
  fun pollReady_afterDrops_returnsOldestSurvivingChunk() {
    val queue = OriginalPcmDeadlineQueue(sampleRate)
    for (i in 1..25) {
      queue.enqueue(chunk(i.toByte()), 1f, 0L)
    }
    val ready = queue.pollReady(3_000L)
    assertNotNull(ready)
    assertEquals(6.toByte(), ready!!.data[0])
    assertEquals(2_500L, ready.dueAtMs)
  }

  @Test
  fun enqueue_singleOversizedChunk_isKept() {
    val queue = OriginalPcmDeadlineQueue(sampleRate)
    queue.enqueue(ByteArray(400_000), 1f, 0L)
    val stats = queue.stats()
    assertEquals(1, stats.queuedChunks)
    assertEquals(0L, stats.droppedChunks)
  }

  @Test
  fun emptyQueue_statsAreZero() {
    val queue = OriginalPcmDeadlineQueue(sampleRate)
    val stats = queue.stats()
    assertEquals(0, stats.queuedChunks)
    assertEquals(0L, stats.queuedBytes)
    assertEquals(0L, stats.droppedChunks)
    assertNull(queue.pollReady(0L))
  }
}
