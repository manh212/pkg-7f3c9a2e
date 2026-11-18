package com.google.android.accessibility.talkback.taskassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineTaskTest {
  @Test fun dragMovesANamedItemInADirection() {
    assertEquals(OfflineTaskCommand.Drag("Wi-Fi", "len"), OfflineTaskCommand.parse("Kéo Wi-Fi lên"))
    assertEquals(OfflineTaskCommand.Drag("Lan", "phai"), OfflineTaskCommand.parse("Kéo Lan sang phải"))
    assertEquals(OfflineTaskCommand.Drag("Lan", "trai"), OfflineTaskCommand.parse("Kéo Lan qua trái"))
    assertEquals(OfflineTaskCommand.Sequence(listOf(OfflineTaskCommand.Drag("Wi-Fi", "len"),
      OfflineTaskCommand.Click("Lưu"))),
      OfflineTaskCommand.parse("Kéo Wi-Fi lên rồi nhấn Lưu"))
  }

  @Test fun dragTargetsTheVisibleItemByLabel() {
    val decision = screen(node("wifi", "Wi-Fi", "click"))
      .decide(OfflineScreenStep.Drag("Wi-Fi", "len")) as OfflineTaskScreen.Decision.Act
    assertEquals("wifi", decision.step.node)
    assertEquals(TaskOperation.DRAG, decision.step.operation)
    assertEquals("len", decision.step.text)
    assertEquals(OfflineTaskScreen.Decision.Wait,
      screen(node("other", "Bluetooth", "click")).decide(OfflineScreenStep.Drag("Wi-Fi", "len")))
  }

  @Test fun openingAnAppFallsBackToTheLauncherCatalog() {
    val zalo = TaskApp("com.zalo", "Zalo")
    val request = OfflineScreenStep.ClickLabel("Zalo")
    val decision = screenWithApps(listOf(zalo)).decide(request) as OfflineTaskScreen.Decision.Act
    assertEquals(TaskOperation.OPEN_APP, decision.step.operation)
    assertEquals("com.zalo", decision.step.packageName)
    assertEquals("", decision.step.node)
    assertEquals(OfflineTaskScreen.Decision.Wait, screenWithApps(emptyList()).decide(request))
    assertEquals(OfflineTaskScreen.Decision.Ambiguous,
      screenWithApps(listOf(zalo, TaskApp("com.zalo2", "Zalo"))).decide(request))
    val visible = screenWithApps(listOf(zalo), node("icon", "Zalo", "click"))
      .decide(request) as OfflineTaskScreen.Decision.Act
    assertEquals(TaskOperation.CLICK, visible.step.operation)
    assertEquals("icon", visible.step.node)
  }

  @Test fun textThenSendThenHomeIsASequenceOfScreenActions() {
    assertEquals(OfflineTaskCommand.Sequence(listOf(
      OfflineTaskCommand.Input("Mình đến muộn 15 phút"),
      OfflineTaskCommand.Click("Gửi"),
      OfflineTaskCommand.Click("Trang chủ"),
    )), OfflineTaskCommand.parse("Nhập Mình đến muộn 15 phút rồi nhấn vào nút Gửi sau đó nhấn Trang chủ"))
    assertEquals(OfflineTaskCommand.Sequence(listOf(OfflineTaskCommand.Click("Điện thoại"),
      OfflineTaskCommand.Click("Tin nhắn"))),
      OfflineTaskCommand.parse("Nhấn vào Điện thoại rồi nhấn vào Tin nhắn"))
  }

  @Test fun contentKeepsItsAccentsPunctuationAndQuotedCommandWords() {
    assertEquals(OfflineTaskCommand.Input("Mình xong rồi về!"),
      OfflineTaskCommand.parse("Nhập Mình xong rồi về!"))
    assertEquals(OfflineTaskCommand.Sequence(listOf(OfflineTaskCommand.Input("Xin chào, Lan!"),
      OfflineTaskCommand.Click("Gửi"))),
      OfflineTaskCommand.parse("Nhập Xin chào, Lan! rồi nhấn nút Gửi"))
    assertEquals(OfflineTaskCommand.Sequence(listOf(OfflineTaskCommand.Input("Mình xong rồi nhấn nút Gửi"),
      OfflineTaskCommand.Click("Gửi"))),
      OfflineTaskCommand.parse("Nhập \"Mình xong rồi nhấn nút Gửi\" rồi nhấn Gửi"))
  }

  @Test fun phoneCommandsStayOutsideTheScreenInterpreter() {
    assertNull(OfflineTaskCommand.parse("Gọi cho mẹ"))
    assertNull(OfflineTaskCommand.parse("Gọi đến số 0901234567"))
    assertNull(OfflineTaskCommand.parse("Đặt báo thức lúc 6 giờ 30 sáng"))
    assertNull(OfflineTaskCommand.parse("Tăng âm lượng 20 phần trăm"))
    assertNull(OfflineTaskCommand.parse("Về màn hình chính"))
    assertNull(OfflineTaskCommand.parse("Sao chép"))
    assertNull(OfflineTaskCommand.parse("Dán"))
    assertNull(OfflineTaskCommand.parse("Nhấn Gửi rồi làm chuyện chưa biết"))
  }

  @Test fun openingAnAppMeansClickingItsVisibleIcon() {
    assertEquals(OfflineTaskCommand.Click("Zalo"), OfflineTaskCommand.parse("Mở Zalo"))
    assertEquals(OfflineTaskCommand.Sequence(listOf(OfflineTaskCommand.Click("YouTube"),
      OfflineTaskCommand.Search("nhạc Trịnh"), OfflineTaskCommand.Item(1, true))),
      OfflineTaskCommand.parse("Mở YouTube, tìm nhạc Trịnh rồi mở video đầu tiên"))
  }

  @Test fun controlsCarryTheirScreenTargets() {
    assertEquals(OfflineTaskCommand.LongClick("Lan"), OfflineTaskCommand.parse("Nhấn giữ Lan"))
    assertEquals(OfflineTaskCommand.Input("Lan", "Tìm kiếm"),
      OfflineTaskCommand.parse("Nhập vào ô Tìm kiếm nội dung Lan"))
    assertEquals(OfflineTaskCommand.Input("mình đến muộn", append = true),
      OfflineTaskCommand.parse("Thêm mình đến muộn"))
    assertEquals(OfflineTaskCommand.Item(2, true), OfflineTaskCommand.parse("Mở video thứ hai"))
    assertEquals(OfflineTaskCommand.Item(3), OfflineTaskCommand.parse("Chọn mục thứ ba"))
    assertEquals(OfflineTaskCommand.Toggle("Phụ đề", true), OfflineTaskCommand.parse("Bật Phụ đề"))
  }

  @Test fun typingSelectsTheNamedOrFocusedFieldAndChecksTheFullText() {
    val first = node("one", "Tên", "set_text").copy(text = "Lan", focused = true)
    val second = node("two", "Tin nhắn", "set_text").copy(text = "Xin chào")
    val current = screen(first, second)
    val decision = current.decide(OfflineScreenStep.Input("Lan!", "Tin nhắn", true)) as OfflineTaskScreen.Decision.Act
    assertEquals("two", decision.step.node)
    assertEquals("Xin chào Lan!", decision.step.text)
    assertEquals("one", (current.decide(OfflineScreenStep.Input("Mai", "", false)) as OfflineTaskScreen.Decision.Act).step.node)
    assertEquals(OfflineTaskScreen.Decision.Ready, screen(second.copy(text = "Xin chào Lan!"))
      .decide(OfflineScreenStep.VerifyInput("Xin chào Lan!", "Tin nhắn")))
    assertEquals(OfflineTaskScreen.Decision.Wait, screen(second)
      .decide(OfflineScreenStep.VerifyInput("Xin chào Lan!", "Tin nhắn")))
    assertEquals(OfflineTaskScreen.Decision.Ambiguous, screen(first.copy(focused = false), second)
      .decide(OfflineScreenStep.Input("Mai", "", false)))
  }

  @Test fun switchesAreCheckedBeforeAndAfterClicking() {
    val switch = node("switch", "Phụ đề", "click").copy(checked = false)
    assertEquals(TaskOperation.CLICK, (screen(switch).decide(OfflineScreenStep.Toggle("Phụ đề", true))
      as OfflineTaskScreen.Decision.Act).step.operation)
    assertEquals(OfflineTaskScreen.Decision.Wait, screen(switch)
      .decide(OfflineScreenStep.VerifyToggle("Phụ đề", true)))
    assertEquals(OfflineTaskScreen.Decision.Ready, screen(switch.copy(checked = true))
      .decide(OfflineScreenStep.Toggle("Phụ đề", true)))
  }

  @Test fun ordinalVideoSelectionUsesTheCurrentResultsTree() {
    val list = node("list", "").copy(collection = true)
    val filter = node("filter", "Bộ lọc", "click")
    val second = node("second", "Bài B 20 lượt xem", "click").copy(parent = "list", row = 2)
    val first = node("first", "Bài A 50 lượt xem", "click").copy(parent = "list", row = 1)
    val ad = node("ad", "Quảng cáo 1:20", "click").copy(parent = "list", row = 0)
    val step = OfflineScreenStep.Item(1, true)
    assertEquals(OfflineTaskScreen.Decision.Wait, screen(list, second, first).decide(step))
    val decision = screen(list, second, first, ad, filter).decide(step) as OfflineTaskScreen.Decision.Act
    assertEquals("first", decision.step.node)
    assertEquals("second", (screen(list, second, first, filter)
      .decide(OfflineScreenStep.Item(2, true)) as OfflineTaskScreen.Decision.Act).step.node)
  }

  @Test fun sendRequiresAUniqueVisibleTargetAndCanBeRequestedExplicitly() {
    val request = OfflineScreenStep.ClickLabel("Gửi")
    assertEquals(OfflineTaskScreen.Decision.Ambiguous,
      screen(node("one", "Gửi", "click"), node("two", "Gửi", "click")).decide(request))
    assertEquals(OfflineTaskScreen.Decision.Wait, screen(node("message", "Tin nhắn", "set_text")).decide(request))
    val decision = screen(node("send", "Gửi", "click")).decide(request) as OfflineTaskScreen.Decision.Act
    assertEquals("send", decision.step.node)
    assertEquals(TaskOperation.CLICK, decision.step.operation)
  }

  private fun screen(vararg nodes: TaskNode) =
    OfflineTaskScreen(TaskScreen(1, "example.app", "", "", nodes.toList()))

  private fun screenWithApps(apps: List<TaskApp>, vararg nodes: TaskNode) =
    OfflineTaskScreen(TaskScreen(1, "example.app", "", "", nodes.toList()), apps)

  private fun node(id: String, label: String, action: String = "") =
    TaskNode(id, label, "", "", setOf(action).filter { it.isNotBlank() }.toSet())
}
