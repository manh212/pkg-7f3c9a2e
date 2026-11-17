package com.google.android.accessibility.talkback.taskassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineTaskTest {
  @Test fun messageAppComesFromTheCommandAndTheBodyKeepsItsPunctuation() {
    assertEquals(
      OfflineTaskCommand.Draft(OfflineTaskCommand.MessageApp.ZALO, "Lan", "Mình đến muộn 15 phút!"),
      OfflineTaskCommand.parse("Soạn tin nhắn Zalo cho Lan nội dung Mình đến muộn 15 phút!"),
    )
    assertEquals(
      OfflineTaskCommand.Draft(OfflineTaskCommand.MessageApp.ZALO, "Lan", ""),
      OfflineTaskCommand.parse("Soạn tin nhắn cho Lan trên Zalo"),
    )
    assertEquals(
      OfflineTaskCommand.Draft(OfflineTaskCommand.MessageApp.ZALO, "Lan", ""),
      OfflineTaskCommand.parse("Soạn tin nhắn cho Zalo cho Lan"),
    )
    assertEquals(
      OfflineTaskCommand.Draft(OfflineTaskCommand.MessageApp.SMS, "0901234567", "Mình tới."),
      OfflineTaskCommand.parse("Viết tin nhắn SMS cho 0901234567, Mình tới."),
    )
    assertEquals(
      OfflineTaskCommand.AskMessageApp,
      OfflineTaskCommand.parse("Soạn tin nhắn cho Lan"),
    )
  }

  @Test fun spokenNumberAndNamedContactRemainDistinct() {
    assertEquals(OfflineTaskCommand.Call("mẹ", null), OfflineTaskCommand.parse("Gọi cho mẹ"))
    assertEquals(
      OfflineTaskCommand.Call("không chín ba một hai ba bốn năm sáu bảy", "0931234567"),
      OfflineTaskCommand.parse("Gọi đến số không chín ba một hai ba bốn năm sáu bảy"),
    )
  }

  @Test fun youtubeCommandKeepsQueryAndSelectsAnOrdinal() {
    assertEquals(
      OfflineTaskCommand.YouTube(true, "Ngày chưa giông bão", true),
      OfflineTaskCommand.parse("Mở YouTube, tìm Ngày chưa giông bão rồi mở video đầu tiên"),
    )
    assertEquals(OfflineTaskCommand.FirstVideo, OfflineTaskCommand.parse("Nhấn vào kết quả đầu tiên"))
    assertEquals(
      OfflineTaskCommand.FirstVideo,
      OfflineTaskCommand.parse("Nhấn vào kết quả tìm kiếm đầu tiên"),
    )
  }

  @Test fun nameMatchesUseTheSearchFieldAndRejectDuplicateRows() {
    val editor = node("search", "Tìm kiếm", "set_text").copy(text = "Lan")
    val first = node("first", "Lan", "click").copy(top = 300)
    val duplicate = node("second", "Lan", "click").copy(top = 400)
    val differentAccent = node("third", "Lân", "click")
    val step = OfflineScreenStep.ZaloRecipient("Lan")
    assertEquals(OfflineTaskScreen.Decision.Wait, zalo(editor.copy(text = "Mai"), first).decide(step))
    assertEquals(OfflineTaskScreen.Decision.Ambiguous, zalo(editor, first, duplicate).decide(step))
    val decision = zalo(editor, first, differentAccent).decide(step) as OfflineTaskScreen.Decision.Act
    assertEquals("first", decision.step.node)
  }

  @Test fun draftRequiresTheCorrectConversationAndPreservesExistingText() {
    val title = node("header", "Lan", text = "Lan")
    val editor = node("composer", "Nhập tin nhắn", "set_text").copy(top = 625, hint = "Nhập tin nhắn")
    val step = OfflineScreenStep.ZaloDraft("Lan", "Mình tới!")
    assertEquals(OfflineTaskScreen.Decision.Wait, zalo(title.copy(label = "Mai", text = "Mai"), editor).decide(step))
    assertEquals(OfflineTaskScreen.Decision.ExistingDraft, zalo(title, editor.copy(text = "Có sẵn")).decide(step))
    val decision = zalo(title, editor).decide(step) as OfflineTaskScreen.Decision.Act
    assertEquals(TaskOperation.SET_TEXT, decision.step.operation)
    assertEquals("composer", decision.step.node)
    assertEquals("header", decision.guard)
    assertEquals("Mình tới!", decision.step.text)
    assertEquals(
      OfflineTaskScreen.Decision.Ready,
      zalo(title, editor.copy(text = decision.step.text)).decide(OfflineScreenStep.VerifyZaloDraft("Lan", "Mình tới!")),
    )
  }

  @Test fun firstVideoRequiresResultsAndSelectsTheFirstVideoRowByIndex() {
    val list = node("list", "").copy(collection = true)
    val filter = node("filter", "Bộ lọc", "click")
    val later = node("later", "Bài B 20 lượt xem", "click").copy(parent = "list", row = 2)
    val first = node("first", "Bài A 50 lượt xem", "click").copy(parent = "list", row = 1)
    val ad = node("ad", "Quảng cáo 1:20", "click").copy(parent = "list", row = 0)
    val step = OfflineScreenStep.FirstVideo(null)
    assertEquals(OfflineTaskScreen.Decision.Wait, youtube(list, later, first).decide(step))
    val decision = youtube(list, later, first, ad, filter).decide(step) as OfflineTaskScreen.Decision.Act
    assertEquals("first", decision.step.node)
  }

  @Test fun directClickWaitsOnAmbiguityAndDoesNotSendMessages() {
    val find = OfflineScreenStep.ClickLabel("Tìm bạn")
    assertEquals(OfflineTaskScreen.Decision.Ambiguous, zalo(
      node("one", "Tìm bạn", "click"), node("two", "Tìm bạn", "click"),
    ).decide(find))
    assertEquals(OfflineTaskScreen.Decision.Unavailable, zalo(node("send", "Gửi tin nhắn", "click"))
      .decide(OfflineScreenStep.ClickLabel("Gửi tin nhắn")))
  }

  private fun zalo(vararg nodes: TaskNode) =
    OfflineTaskScreen(TaskScreen(1, OfflineTaskScreen.ZALO, "", "", nodes.toList()))

  private fun youtube(vararg nodes: TaskNode) =
    OfflineTaskScreen(TaskScreen(1, OfflineTaskScreen.YOUTUBE, "", "", nodes.toList()))

  private fun node(id: String, label: String, action: String = "", text: String = "") =
    TaskNode(id, label, "", "", setOf(action).filter { it.isNotBlank() }.toSet(), text = text)
}
