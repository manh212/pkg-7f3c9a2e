package com.google.android.accessibility.talkback.taskassistant

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import com.google.android.accessibility.talkback.dictation.ZipformerModels
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.io.RandomAccessFile
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskAssistantEntryTest {
  @Test fun offlineSpeechReturnsToTheEditableFormWithoutStartingATask() {
    checkVoiceReview(offline = true)
  }

  @Test fun onlineSpeechReturnsToTheEditableFormWithoutStartingATask() {
    checkVoiceReview(offline = false)
  }

  private fun checkVoiceReview(offline: Boolean) {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    service.setTheme(R.style.TalkBackSettings)
    TaskAiCatalog.setLocal(SharedPreferencesUtils.getSharedPreferences(service), true)
    val assistant = TaskAssistant::class.java.getDeclaredConstructor(TalkBackService::class.java)
      .apply { isAccessible = true }.newInstance(service)
    fun set(name: String, value: Any) {
      TaskAssistant::class.java.getDeclaredField(name).apply { isAccessible = true }.set(assistant, value)
    }
    fun field(name: String): Any? = TaskAssistant::class.java.getDeclaredField(name)
      .apply { isAccessible = true }.get(assistant)
    try {
      set("voicePending", true)
      set("voiceOffline", offline)
      set("voiceGeneration", 7L)
      TaskAssistant::class.java.getDeclaredMethod("voiceResult", java.lang.Long.TYPE, String::class.java)
        .apply { isAccessible = true }.invoke(assistant, 7L, "Mở YouTube")
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
      val dialog = field("dialog") as AlertDialog
      assertTrue(dialog.isShowing)
      val editor = views(dialog.window!!.decorView).filterIsInstance<KeyboardSafeEditText>().single()
      assertEquals("Mở YouTube", editor.text.toString())
      editor.setText("Mở Zalo")
      assertEquals("Mở Zalo", editor.text.toString())
      assertEquals(service.getString(R.string.task_start), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
      assertNull(field("engine"))
      assertFalse((field("offline") as OfflineTaskRunner).active)
    } finally {
      TaskAssistant::class.java.getDeclaredMethod("close").apply { isAccessible = true }.invoke(assistant)
    }
  }

  @Test fun speechDownloadShowsProgressFailureAndInstalledState() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setTheme(R.style.TalkBackSettings)
    TaskAiCatalog.setLocal(SharedPreferencesUtils.getSharedPreferences(activity), true)
    val models = ZipformerModels.get(activity)
    models.directory.deleteRecursively()
    val setState = ZipformerModels::class.java.getDeclaredMethod("setState", ZipformerModels.State::class.java)
      .apply { isAccessible = true }
    val picker = TaskAiPicker(activity)
    activity.setContentView(picker)
    fun status(): String = (TaskAiPicker::class.java.getDeclaredField("speechStatus")
      .apply { isAccessible = true }.get(picker) as TextView).text.toString()
    fun update(state: ZipformerModels.State) {
      setState.invoke(models, state)
      shadowOf(Looper.getMainLooper()).idle()
    }
    try {
      update(ZipformerModels.State.Idle)
      assertEquals(activity.getString(R.string.task_offline_voice_not_downloaded), status())
      update(ZipformerModels.State.Downloading(ZipformerModels.TOTAL_BYTES / 2))
      assertTrue(status().contains("50%"))
      update(ZipformerModels.State.Failed(ModelFiles.Failure.HASH_MISMATCH))
      assertTrue(status().contains("chưa hoàn tất"))
      models.directory.mkdirs()
      ZipformerModels.Part.entries.forEach { part ->
        RandomAccessFile(models.file(part), "rw").use { it.setLength(part.sizeBytes) }
      }
      update(ZipformerModels.State.Idle)
      assertEquals(activity.getString(R.string.task_offline_voice_ready), status())
      val button = views(picker).filterIsInstance<TextView>()
        .single { it.text.toString() == activity.getString(R.string.task_offline_voice_model) }
      assertFalse(button.isEnabled)
    } finally {
      activity.setContentView(View(activity))
      models.directory.deleteRecursively()
      setState.invoke(models, ZipformerModels.State.Idle)
      activity.finish()
    }
  }

  private fun views(view: View): List<View> = buildList {
    add(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) addAll(views(view.getChildAt(index)))
  }
}
