package com.google.android.accessibility.talkback.status

import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrentLocationActivityTest {
  private val location = "123 Nguyễn Trãi, Hà Nội"

  private fun resultState() = Bundle().apply {
    putBoolean("showing_location_result", true)
    putString("location_result_text", location)
  }

  private fun views(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) {
      for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
  }

  @Test
  fun resultStaysOpenSupportsCopyAndClosesOnlyFromItsButton() {
    val controller = Robolectric.buildActivity(CurrentLocationActivity::class.java)
      .create(resultState()).start().resume()
    val activity = controller.get()
    val children = views(activity.window.decorView).toList()
    assertTrue(children.filterIsInstance<TextView>().any { it.text.toString() == location })
    activity.onBackPressedDispatcher.onBackPressed()
    assertFalse(activity.isFinishing)
    val buttons = children.filterIsInstance<Button>()
    buttons.first { it.text == activity.getString(android.R.string.copy) }.performClick()
    val clipboard = activity.getSystemService(ClipboardManager::class.java)!!
    assertEquals(location, clipboard.primaryClip!!.getItemAt(0).text.toString())
    buttons.first { it.text == activity.getString(R.string.location_close) }.performClick()
    assertTrue(activity.isFinishing)
    controller.pause().stop().destroy()
  }

  @Test
  fun resultSurvivesSavingAndRestoringTheActivity() {
    val first = Robolectric.buildActivity(CurrentLocationActivity::class.java)
      .create(resultState()).start().resume()
    val state = Bundle()
    first.saveInstanceState(state).pause().stop().destroy()
    val restored = Robolectric.buildActivity(CurrentLocationActivity::class.java)
      .create(state).start().resume()
    val activity = restored.get()
    assertTrue(views(activity.window.decorView).filterIsInstance<TextView>()
      .any { it.text.toString() == location })
    assertFalse(activity.isFinishing)
    restored.pause().stop().destroy()
  }
}
