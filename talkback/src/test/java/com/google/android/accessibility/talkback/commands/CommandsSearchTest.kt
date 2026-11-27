package com.google.android.accessibility.talkback.commands

import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.Spinner
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.extended.EdgeGesture
import com.google.android.accessibility.talkback.preference.base.GestureListPreference
import com.google.android.accessibility.talkback.preference.base.SearchMatcher
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.text.Normalizer
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
@LooperMode(LooperMode.Mode.PAUSED)
class CommandsSearchTest {
  private lateinit var controller: ActivityController<CommandsSearchActivity>
  private lateinit var value: String

  @Before
  fun setUp() {
    controller = Robolectric.buildActivity(CommandsSearchActivity::class.java)
    val activity = controller.get()
    activity.setTheme(R.style.TalkBackSettings)
    FormFactorUtils.initialize(activity)
    value = activity.getString(R.string.shortcut_value_take_screenshot)
    SharedPreferencesUtils.getSharedPreferences(activity).edit().clear()
      .putString(activity.getString(R.string.pref_shortcut_up_key), value).commit()
    controller.setup()
    activity.supportFragmentManager.beginTransaction()
      .replace(android.R.id.content, CommandsFragment(), "commands").commitNow()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @After
  fun tearDown() {
    controller.pause().stop().destroy()
  }

  @Test
  fun topHeaderOrderIsSearchMoreOptionsAndGroupFilterSpinner() {
    val root = fragment().requireView() as LinearLayout
    assertTrue(root.getChildAt(0) is SearchView)
    assertTrue(root.getChildAt(1) is Button)
    val spinner = root.getChildAt(2) as Spinner
    assertTrue(spinner.count > 1)
    assertTrue(spinner.getItemAtPosition(0).toString().startsWith("Tất cả"))
  }

  @Test
  fun typingFiltersImmediatelyAndComposingVietnameseKeepsResults() {
    val fragment = fragment()
    val search = search()
    val label = controller.get().getString(R.string.shortcut_take_screenshot)
    search.setIconified(false)
    search.setQuery(SearchMatcher.normalize(label).split(" ").reversed().joinToString(" "), false)
    assertNotNull(fragment.findPreference<Preference>("pref_cmd_row_$value"))
    val composing = Normalizer.normalize(label, Normalizer.Form.NFD)
    search.setQuery(composing, false)
    assertNotNull(fragment.findPreference<Preference>("pref_cmd_row_$value"))
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertNotNull(fragment.findPreference<Preference>("pref_cmd_row_$value"))
    assertEquals(composing, search.query.toString())
    search.setQuery("khongco-ketqua-123456", false)
    assertNotNull(fragment.findPreference<Preference>("pref_commands_no_results"))
    search.setQuery(SearchMatcher.normalize(label), false)
    assertNull(fragment.findPreference<Preference>("pref_commands_no_results"))
    assertNotNull(fragment.findPreference<Preference>("pref_cmd_row_$value"))
    search.setQuery("", false)
    assertNotNull(fragment.findPreference<Preference>("pref_commands_results"))
    assertNotNull(fragment.findPreference<Preference>("pref_cmd_row_$value"))
  }

  @Test
  fun queryAndAssignedFilterSurviveActivityRecreation() {
    val query = SearchMatcher.normalize(controller.get().getString(R.string.shortcut_take_screenshot))
    search().setQuery(query, false)
    fragment().setShowAssignedOnlyForTest(true)
    assertNotNull(fragment().findPreference<Preference>("pref_cmd_row_$value"))
    controller.recreate()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertEquals(query, search().query.toString())
    assertEquals(true, fragment().showAssignedOnly)
    assertNotNull(fragment().findPreference<Preference>("pref_cmd_row_$value"))
  }

  @Test
  fun recentAppCommandsAreSearchableWithVietnameseAccentsOrPlainText() {
    for (query in listOf("ứng dụng gần đây", "ung dung gan day")) {
      search().setQuery(query, false)
      for (command in listOf("EDGE_GESTURE_PREVIOUS_RECENT", "EDGE_GESTURE_NEXT_RECENT")) {
        assertTrue(isCommandVisible(command))
      }
      assertFalse(isCommandVisible(controller.get().getString(R.string.shortcut_value_previous_window)))
      assertFalse(isCommandVisible(controller.get().getString(R.string.shortcut_value_next_window)))
    }
    fragment().selectGroupFilterForTest(R.string.shortcut_title_system_actions)
    assertTrue(isCommandVisible("EDGE_GESTURE_PREVIOUS_RECENT"))
    assertTrue(isCommandVisible("EDGE_GESTURE_NEXT_RECENT"))
  }

  @Test
  fun recentAppCommandsHaveOneCatalogEntryAndRemainAssignableAsGeneralGestures() {
    val context = controller.get()
    val assignable = GestureListPreference.getAssignableGestureActions(context)
    for (categories in listOf(Commands.categories(context), Commands.categoriesWithoutApps(context))) {
      for ((command, label) in listOf(
        "EDGE_GESTURE_PREVIOUS_RECENT" to R.string.edge_gesture_previous_recent,
        "EDGE_GESTURE_NEXT_RECENT" to R.string.edge_gesture_next_recent,
      )) {
        val matches = categories.flatMap { it.commands }.filter { it.value == command }
        assertEquals(1, matches.size)
        assertEquals(context.getString(label), matches.single().label)
        assertEquals(context.getString(label), assignable[command])
        assertEquals(context.getString(label), Commands.find(context, command)?.label)
        assertEquals(R.string.shortcut_title_system_actions,
          categories.single { category -> category.commands.any { it.value == command } }.titleRes)
      }
    }
    assertNotNull(Commands.find(context, context.getString(R.string.shortcut_value_previous_window)))
    assertNotNull(Commands.find(context, context.getString(R.string.shortcut_value_next_window)))
  }

  @Test
  fun assignedRecentAppCommandAppearsWhenFilteringExistingEdgeBindings() {
    val context = controller.get()
    val previous = "EDGE_GESTURE_PREVIOUS_RECENT"
    val next = "EDGE_GESTURE_NEXT_RECENT"
    UnifiedCommandBindings.assignEdgeGesture(
      SharedPreferencesUtils.getSharedPreferences(context), EdgeGesture.values().first(), previous)
    controller.recreate()
    shadowOf(Looper.getMainLooper()).idle()
    search().setQuery("ung dung gan day", false)
    fragment().setShowAssignedOnlyForTest(true)
    assertTrue(isCommandVisible(previous))
    assertFalse(isCommandVisible(next))
    fragment().setShowAssignedOnlyForTest(false)
    assertTrue(isCommandVisible(next))
  }

  @Test
  fun commandsMissingFromSpecializedListsAreVisibleInCommonSearch() {
    for ((query, commands) in listOf(
      "doc cau" to listOf("EDGE_GESTURE_PREVIOUS_SENTENCE", "EDGE_GESTURE_NEXT_SENTENCE"),
      "noi them vao bo nho tam" to listOf(controller.get().getString(R.string.shortcut_value_add_to_clipboard)),
      "doc gio hien tai" to listOf(controller.get().getString(R.string.shortcut_value_read_current_time)),
    )) {
      search().setQuery(query, false)
      for (command in commands) {
        assertTrue("Command is not visible for query $query: $command", isCommandVisible(command))
      }
    }
  }

  private fun isCommandVisible(command: String): Boolean =
    fragment().findPreference<Preference>("pref_cmd_row_$command")?.isVisible == true

  private fun fragment(): CommandsFragment =
    controller.get().supportFragmentManager.findFragmentByTag("commands") as CommandsFragment

  private fun search(): SearchView = (fragment().requireView() as LinearLayout).getChildAt(0) as SearchView
}

class CommandsSearchActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    setTheme(R.style.TalkBackSettings)
    FormFactorUtils.initialize(this)
    super.onCreate(savedInstanceState)
  }
}
