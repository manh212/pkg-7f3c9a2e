package com.google.android.accessibility.talkback.subtitles

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKeyPreference
import com.google.android.accessibility.talkback.imagecaption.OcrModelDownload
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
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
        if (key == SubtitleSettings.ENGINE && value == "mlkit") {
          value = SubtitleSettings.OFFLINE_ENGINE
        }
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, value ->
          val engine = if (key == SubtitleSettings.ENGINE) value.toString() else null
          val voice = if (key == SubtitleSettings.GEMINI_VOICE) value.toString() else null
          if (engine != null || voice != null) refresh(engine, voice)
          // REGION and other values are persisted after this callback.
          view?.post { refresh() }
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

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val download =
      findPreference<Preference>(getString(R.string.pref_ocr_data_download_key)) ?: return
    download.setOnPreferenceClickListener {
      OcrModelDownload.start(requireContext())
      true
    }
    OcrModelDownload.status.observe(viewLifecycleOwner) { status ->
      refreshDownload(download, status)
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
    GeminiApiKeyPreference.refresh(this)
  }

  private fun refreshDownload(download: Preference, status: OcrModelDownload.Status) {
    val ready = TesseractOcr.isDataDownloaded(requireContext())
    download.isEnabled = status.progress == null && !ready
    download.summary =
      when {
        status.progress != null ->
          getString(R.string.ocr_offline_download_progress, status.progress)
        ready -> getString(R.string.summary_ocr_data_downloaded)
        status.failed -> getString(R.string.summary_ocr_data_download_failed)
        else -> getString(R.string.summary_ocr_data_not_downloaded)
      }
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
    findPreference<Preference>(getString(R.string.pref_ocr_data_download_key))?.isVisible =
      !gemini
    findPreference<Preference>(SubtitleSettings.GEMINI_VOICE)?.isVisible = gemini
    findPreference<Preference>(SubtitleSettings.GEMINI_CHANNEL)?.isVisible =
      gemini && voice == SubtitleSettings.VOICE_GEMINI
    findPreference<Preference>(com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey.PREF_KEY)?.isVisible = gemini
  }
}
