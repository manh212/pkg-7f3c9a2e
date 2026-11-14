package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.material.preference.AccessibilitySeekBarPreference
import com.google.android.accessibility.talkback.R

class KeyboardSpacingSeekBarPreference(context: Context, attrs: AttributeSet) :
  AccessibilitySeekBarPreference(context, attrs) {

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    val slider = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar ?: return
    ViewCompat.setAccessibilityDelegate(slider, object : AccessibilityDelegateCompat() {
      override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.stateDescription = context.getString(R.string.vietnamese_ime_spacing_dp, slider.progress + min)
      }
    })
  }
}
