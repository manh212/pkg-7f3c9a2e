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

package com.google.android.accessibility.talkback.timer

import android.content.Context
import android.content.SharedPreferences
import android.content.res.XmlResourceParser
import androidx.annotation.XmlRes
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.ContextMenuFragment
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The menu section is a landing page whose rows open their own sub-screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private lateinit var controller: ActivityController<FragmentActivity>
  private lateinit var activity: FragmentActivity
  private lateinit var prefs: SharedPreferences

  @Before
  fun setUp() {
    controller = Robolectric.buildActivity(FragmentActivity::class.java)
    activity = controller.get()
    activity.setTheme(R.style.TalkBackSettings)
    FormFactorUtils.initialize(activity)
    prefs = SharedPreferencesUtils.getSharedPreferences(activity)
    prefs.edit().clear().commit()
    controller.setup()
  }

  @After
  fun tearDown() {
    controller.pause().stop().destroy()
  }

  private fun open(fragment: TalkbackBaseFragment) {
    activity.supportFragmentManager.beginTransaction()
      .replace(android.R.id.content, fragment).commitNow()
  }

  @Test
  fun menuScreen_hasFourSubScreensInOrder() {
    val rows = rowsOf(R.xml.settings_group_menu_controls)
    assertEquals(
      listOf(
        "pref_category_group_menu_controls_key",
        "pref_category_manage_context_menu",
        "pref_category_manage_selector_menu",
        "pref_custom_menus_screen",
        "pref_quick_settings_items_screen",
      ),
      rows.map { it.first },
    )
    assertEquals(
      listOf(
        null,
        "ContextMenuFragment",
        "SelectorMenuFragment",
        "CustomMenusFragment",
        "QuickSettingsItemsFragment",
      ),
      rows.map { it.second?.substringAfterLast('.') },
    )
  }

  @Test
  fun menuScreen_secondRowIsNamedNavigationGroups() {
    val parser = context.resources.getXml(R.xml.settings_group_menu_controls)
    var title: String? = null
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG
          && parser.getAttributeValue(NAMESPACE, "fragment")?.endsWith("SelectorMenuFragment") == true) {
        val resId = parser.getAttributeResourceValue(NAMESPACE, "title", 0)
        title = if (resId != 0) context.getString(resId) else parser.getAttributeValue(NAMESPACE, "title")
      }
    }
    assertEquals(context.getString(R.string.selector_groups_title), title)
  }

  @Test
  fun granularityDetailRow_leftTheMenuLandingAndIsAnInvisibleMarkerInTheContextMenuXml() {
    assertFalse(
      rowsOf(R.xml.settings_group_menu_controls)
        .any { it.first == DETAIL_KEY })
    val parser = context.resources.getXml(R.xml.context_menu_preferences)
    var found = false
    var visible = true
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG && keyOf(parser) == DETAIL_KEY) {
        found = true
        visible = parser.getAttributeBooleanValue(APP_NAMESPACE, "isPreferenceVisible", true)
      }
    }
    assertTrue(found)
    assertFalse(visible)
  }

  @Test
  fun granularityDetailRow_sitsUnderTheGranularityToggleAndDimsWhenOff() {
    val fragment = ContextMenuFragment()
    open(fragment)
    val group = granularityGroup(fragment)
    val detailKey = activity.getString(R.string.pref_show_context_menu_granularity_detail_setting_key)
    val detail = requireNotNull(group.findPreference<Preference>(detailKey))
    val toggleIndex = (0 until group.preferenceCount)
      .indexOfFirst { group.getPreference(it).title.toString() == granularityTitle() }
    val detailIndex = (0 until group.preferenceCount)
      .indexOfFirst { group.getPreference(it) == detail }
    assertEquals(toggleIndex + 1, detailIndex)
    assertFalse(detail.isEnabled)
  }

  @Test
  fun granularityDetailRow_isEnabledWhenGranularityIsOn() {
    prefs.edit().putBoolean(
      activity.getString(R.string.pref_show_context_menu_granularity_setting_key), true).commit()
    val fragment = ContextMenuFragment()
    open(fragment)
    val detail = requireNotNull(
      fragment.findPreference<Preference>(
        activity.getString(R.string.pref_show_context_menu_granularity_detail_setting_key)))
    assertTrue(detail.isEnabled)
  }

  private fun granularityTitle(): String = activity.getString(R.string.title_granularity)

  private fun granularityGroup(fragment: ContextMenuFragment): PreferenceGroup {
    val screen = fragment.preferenceScreen
    return (0 until screen.preferenceCount)
      .map { screen.getPreference(it) }
      .filterIsInstance<PreferenceGroup>()
      .first { it.title.toString() == granularityTitle() }
  }

  private fun rowsOf(@XmlRes resId: Int): List<Pair<String?, String?>> {
    val parser = context.resources.getXml(resId)
    val rows = mutableListOf<Pair<String?, String?>>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = keyOf(parser)
        if (key != null) {
          rows.add(key to parser.getAttributeValue(NAMESPACE, "fragment"))
        }
      }
    }
    return rows
  }

  private fun keyOf(parser: XmlResourceParser): String? {
    val resId = parser.getAttributeResourceValue(NAMESPACE, "key", 0)
    return if (resId != 0) context.getString(resId) else parser.getAttributeValue(NAMESPACE, "key")
  }

  private companion object {
    private const val NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    private const val DETAIL_KEY = "pref_granularity_menu_detail_setting_key"
  }
}
