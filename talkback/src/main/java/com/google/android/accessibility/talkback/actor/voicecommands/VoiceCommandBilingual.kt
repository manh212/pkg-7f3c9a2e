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

package com.google.android.accessibility.talkback.actor.voicecommands

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The keyword strings of the voice commands in every language they are written in.
 *
 * A keyword string resource holds a comma-separated list of words or phrases of one language.
 * Reading it through the app's own configuration alone would tie the spoken keywords to the
 * language the app happens to run in, so the lists used for matching are the union of the app's
 * own text and the Vietnamese and English texts: a user may always speak the keywords of either
 * language, whatever the device language is. What the app answers stays in its own language;
 * only the words it listens for are bilingual.
 */
object VoiceCommandBilingual {

  private val LOCALES = listOf(Locale("vi"), Locale.ENGLISH)

  /** Every variant of the comma-separated keyword string [resId], the app's own first. */
  @JvmStatic
  fun list(context: Context, resId: Int): List<String> {
    val raw = mutableListOf(context.getString(resId))
    for (locale in LOCALES) {
      raw.add(localizedString(context, resId, locale))
    }
    return combine(raw)
  }

  /** The single variants of the [rawValues]: split on commas, trimmed, duplicates removed. */
  fun combine(rawValues: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    for (raw in rawValues) {
      for (part in raw.split(',')) {
        val item = part.trim()
        if (item.isNotEmpty()) {
          seen.add(item)
        }
      }
    }
    return seen.toList()
  }

  /* The string [resId] as written for [locale]; a locale without its own text gets the default. */
  private fun localizedString(context: Context, resId: Int, locale: Locale): String {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    return context.createConfigurationContext(configuration).getString(resId)
  }
}
