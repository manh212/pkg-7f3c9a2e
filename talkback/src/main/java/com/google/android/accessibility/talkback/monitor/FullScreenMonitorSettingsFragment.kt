/*
 * Copyright 2026 SandFly contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.android.accessibility.talkback.monitor

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.material.preference.AccessibilitySuiteListPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.NotificationCodeFragment
import com.google.android.accessibility.talkback.notifications.NotificationReader
import com.google.android.accessibility.talkback.speech.VoiceProfileNames
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import java.util.concurrent.Executors

/** Settings > Notifications > Full-screen monitoring. Every chosen app is on by default. */
class FullScreenMonitorSettingsFragment :
  NotificationCodeFragment(R.string.full_screen_monitor_title) {

  private var installed: List<Pair<String, String>>? = null

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    screen.action(
      getString(R.string.full_screen_monitor_manual_title),
      getString(
        if (prefs.getBoolean(FullScreenMonitor.KEY_MANUAL, false))
          R.string.full_screen_monitor_enabled
        else R.string.full_screen_monitor_disabled
      ),
    ) {
      FullScreenMonitor.toggle(context)
      rebuild()
    }
    val profiles = VoiceProfileNames.names(context, prefs)
    val voicePreference = AccessibilitySuiteListPreference(context).apply {
      key = FullScreenMonitor.KEY_VOICE_PROFILE
      setTitle(R.string.full_screen_monitor_voice_profile)
      entries = (listOf(getString(R.string.voice_profile_default)) + profiles.values).toTypedArray()
      entryValues = (listOf("") + profiles.keys).toTypedArray()
      summary = "%s"
      isIconSpaceReserved = false
    }
    screen.addPreference(voicePreference)
    voicePreference.value = FullScreenMonitor.voiceProfile(prefs)
    val streamPreference = AccessibilitySuiteListPreference(context).apply {
      key = FullScreenMonitor.KEY_AUDIO_STREAM
      setTitle(R.string.full_screen_monitor_audio_stream)
      entries = arrayOf(
        getString(R.string.translation_bilingual_stream_default),
        *resources.getStringArray(R.array.timer_stream_entries),
      )
      entryValues = arrayOf(
        "",
        FailoverTextToSpeech.STREAM_VALUE_ACCESSIBILITY,
        FailoverTextToSpeech.STREAM_VALUE_MEDIA,
        FailoverTextToSpeech.STREAM_VALUE_RING,
      )
      summary = "%s"
      isIconSpaceReserved = false
    }
    screen.addPreference(streamPreference)
    streamPreference.value = FullScreenMonitor.audioStream(prefs)
    screen.information(getString(R.string.full_screen_monitor_note))
    val category = screen.category(R.string.full_screen_monitor_apps)
    val apps = installed
    if (apps == null) {
      category.information(getString(R.string.notification_apps_loading))
      loadApps(context)
      return
    }
    val selected = prefs.getStringSet(FullScreenMonitor.KEY_APPS, emptySet()).orEmpty()
    for ((pkg, label) in apps) {
      category.switch(label, pkg in selected) { checked ->
        val updated = selected.toMutableSet()
        if (checked) updated.add(pkg) else updated.remove(pkg)
        prefs.edit().putStringSet(FullScreenMonitor.KEY_APPS, updated).apply()
        rebuild()
      }
    }
  }

  private fun loadApps(context: Context) {
    val app = context.applicationContext
    val saved = prefs.getStringSet(FullScreenMonitor.KEY_APPS, emptySet()).orEmpty().toList()
    EXECUTOR.execute {
      val launchable = app.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
      ).map { it.activityInfo.packageName }
      val list =
        (saved + launchable).distinct().filter { it != app.packageName }
          .map { it to NotificationReader.appName(app, it) }
          .sortedBy { it.second.lowercase() }
      activity?.runOnUiThread {
        if (isAdded) {
          installed = list
          rebuild()
        }
      }
    }
  }

  private companion object {
    val EXECUTOR = Executors.newSingleThreadExecutor()
  }
}
