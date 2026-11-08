package com.google.android.accessibility.talkback.subtitles

import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKeyPreference
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** Settings only. Start/stop remains in the existing subtitle gesture or context menu. */
class SubtitleSettingsFragment : TalkbackBaseFragment(R.xml.subtitle_preferences) {
  override fun getTitle(): CharSequence = getString(R.string.subtitle_settings_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    GeminiApiKeyPreference.setUp(this)
    for (key in listOf(SubtitleSettings.ENGINE, SubtitleSettings.REGION,
        SubtitleSettings.SPEECH, SubtitleSettings.INTERVAL,
        SubtitleSettings.GEMINI_VOICE, SubtitleSettings.GEMINI_CHANNEL)) {
      findPreference<ListPreference>(key)?.apply {
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, value ->
          val engine = if (key == SubtitleSettings.ENGINE) value.toString() else null
          val voice = if (key == SubtitleSettings.GEMINI_VOICE) value.toString() else null
          refresh(engine, voice)
          true
        }
      }
    }
    val defaults = mapOf(
      SubtitleSettings.LEFT to 0, SubtitleSettings.TOP to 60,
      SubtitleSettings.RIGHT to 100, SubtitleSettings.BOTTOM to 100
    )
    for ((key, default) in defaults) {
      findPreference<SeekBarPreference>(key)?.apply {
        min = 0
        max = 100
        showSeekBarValue = true
        seekBarIncrement = 1
        setDefaultValue(default)
        setOnPreferenceChangeListener { _, value ->
          val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
          fun edge(name: String) =
            if (name == key) value as Int else prefs.getInt(name, defaults.getValue(name))
          val valid = edge(SubtitleSettings.LEFT) < edge(SubtitleSettings.RIGHT) &&
            edge(SubtitleSettings.TOP) < edge(SubtitleSettings.BOTTOM)
          if (!valid) Toast.makeText(requireContext(), R.string.subtitle_invalid_region,
            Toast.LENGTH_SHORT).show()
          valid
        }
      }
    }
    refresh()
  }

  override fun onResume() {
    super.onResume()
    refresh()
    GeminiApiKeyPreference.refresh(this)
  }

  private fun refresh(engineSelection: String? = null, voiceSelection: String? = null) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    val gemini = (engineSelection ?: SubtitleSettings.engine(prefs)) == SubtitleSettings.LIVE_ENGINE
    val voice = voiceSelection ?: SubtitleSettings.geminiVoice(prefs)
    for (key in listOf(SubtitleSettings.REGION, SubtitleSettings.INTERVAL, SubtitleSettings.SPEECH)) {
      findPreference<Preference>(key)?.isVisible = !gemini
    }
    val custom = !gemini && SubtitleSettings.region(prefs) == "custom"
    for (key in listOf(SubtitleSettings.LEFT, SubtitleSettings.TOP,
        SubtitleSettings.RIGHT, SubtitleSettings.BOTTOM)) {
      findPreference<Preference>(key)?.isVisible = custom
    }
    findPreference<Preference>("pref_subtitle_ocr_settings")?.isVisible =
      !gemini && (engineSelection ?: SubtitleSettings.engine(prefs)) == "tesseract"
    findPreference<Preference>(SubtitleSettings.GEMINI_VOICE)?.isVisible = gemini
    findPreference<Preference>(SubtitleSettings.GEMINI_CHANNEL)?.isVisible =
      gemini && voice == SubtitleSettings.VOICE_GEMINI
    findPreference<Preference>(com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey.PREF_KEY)?.isVisible = gemini
    findPreference<Preference>("pref_subtitle_gemini_privacy")?.isVisible = gemini
  }
}
