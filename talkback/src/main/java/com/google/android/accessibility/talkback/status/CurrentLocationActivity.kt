package com.google.android.accessibility.talkback.status

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import java.util.function.Consumer

class CurrentLocationActivity : AppCompatActivity() {
  private var reader: CurrentLocationReader? = null
  private var started = false
  private var showingResult = false
  private var requestedFineUpgrade = false
  private var awaitingSettingsResolution = false
  private var latestLocationText: String = ""

  private lateinit var locationTextView: TextView
  private lateinit var copyButton: Button
  private lateinit var readAgainButton: Button
  private lateinit var closeButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTitle(R.string.shortcut_read_current_location)
    val density = resources.displayMetrics.density
    val padding = (24 * density).toInt()
    val gap = (12 * density).toInt()

    locationTextView = TextView(this).apply {
      setText(R.string.location_working)
      textSize = 18f
      setTextIsSelectable(true)
      isFocusable = true
      setPadding(0, 0, 0, gap)
    }

    readAgainButton = Button(this).apply {
      setText(R.string.location_read_again)
      visibility = View.GONE
      setOnClickListener { speakLocation(latestLocationText) }
    }

    copyButton = Button(this).apply {
      setText(android.R.string.copy)
      visibility = View.GONE
      setOnClickListener {
        if (latestLocationText.isNotEmpty()) {
          val clipboard = getSystemService(ClipboardManager::class.java)
          clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.shortcut_read_current_location), latestLocationText)
          )
          Toast.makeText(this@CurrentLocationActivity, R.string.location_copied, Toast.LENGTH_SHORT).show()
        }
      }
    }

    closeButton = Button(this).apply {
      setText(android.R.string.cancel)
      setOnClickListener { finish() }
    }

    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
      addView(locationTextView)
      addView(readAgainButton)
      addView(copyButton)
      addView(closeButton)
    }

    val scroll = ScrollView(this).apply {
      isFillViewport = true
      addView(content)
    }

    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      view.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom + padding)
      insets
    }
    setContentView(scroll)
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (!showingResult) finish()
      }
    })
    if (savedInstanceState?.getBoolean(STATE_SHOWING_RESULT) == true) {
      complete(savedInstanceState.getString(STATE_LOCATION_TEXT).orEmpty())
    } else {
      ensurePermissionsAndStart()
    }
  }

  private fun hasFinePermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  private fun hasCoarsePermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  private fun ensurePermissionsAndStart() {
    when {
      hasFinePermission() -> checkHighAccuracySettingsThenStart()
      hasCoarsePermission() && !requestedFineUpgrade -> {
        requestedFineUpgrade = true
        requestPermissions(
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
          REQUEST_LOCATION_PERMISSION,
        )
      }
      hasCoarsePermission() -> checkHighAccuracySettingsThenStart()
      else -> {
        requestedFineUpgrade = true
        requestPermissions(
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
          REQUEST_LOCATION_PERMISSION,
        )
      }
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_LOCATION_PERMISSION) {
      if (hasFinePermission() || hasCoarsePermission()) {
        checkHighAccuracySettingsThenStart()
      } else {
        complete(getString(R.string.location_permission_needed))
      }
    }
  }

  private fun checkHighAccuracySettingsThenStart() {
    val fineGranted = hasFinePermission()
    val gmsAvailable = runCatching {
      GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
    if (!gmsAvailable) {
      startReader()
      return
    }

    val settingsRequest = LocationSettingsRequest.Builder()
      .addLocationRequest(CurrentLocationReader.buildHighAccuracyGmsRequest(fineGranted))
      .setAlwaysShow(true)
      .build()

    LocationServices.getSettingsClient(this)
      .checkLocationSettings(settingsRequest)
      .addOnSuccessListener(this) { startReader() }
      .addOnFailureListener(this) { exception ->
        if (exception is ResolvableApiException && !awaitingSettingsResolution) {
          try {
            awaitingSettingsResolution = true
            exception.startResolutionForResult(this, REQUEST_LOCATION_SETTINGS)
          } catch (_: IntentSender.SendIntentException) {
            awaitingSettingsResolution = false
            startReader()
          }
        } else {
          startReader()
        }
      }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_LOCATION_SETTINGS) {
      awaitingSettingsResolution = false
      startReader()
    }
  }

  private fun startReader() {
    if (started || isFinishing || isDestroyed) return
    started = true
    reader = CurrentLocationReader(this, Consumer(::complete)).also { it.read() }
  }

  private fun complete(text: String) {
    if (isFinishing || isDestroyed) return
    showingResult = true
    latestLocationText = text
    locationTextView.text = text
    readAgainButton.visibility = View.VISIBLE
    copyButton.visibility = View.VISIBLE
    closeButton.setText(R.string.location_close)
    locationTextView.post {
      locationTextView.requestFocus()
      locationTextView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
      speakLocation(text)
    }
  }

  private fun speakLocation(text: String) {
    if (text.isEmpty()) return
    val app = applicationContext
    Handler(Looper.getMainLooper()).post {
      val service = TalkBackService.getInstance()
      if (service != null) {
        service.speechController.speak(
          text,
          EVENT_ID_UNTRACKED,
          SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_FLUSH),
        )
      } else {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean(STATE_SHOWING_RESULT, showingResult)
    outState.putString(STATE_LOCATION_TEXT, latestLocationText)
    super.onSaveInstanceState(outState)
  }

  override fun onStop() {
    if (!awaitingSettingsResolution && !showingResult) {
      reader?.cancel()
      if (started && !isFinishing) finish()
    }
    super.onStop()
  }

  override fun onDestroy() {
    reader?.shutdown()
    super.onDestroy()
  }

  companion object {
    private const val STATE_SHOWING_RESULT = "showing_location_result"
    private const val STATE_LOCATION_TEXT = "location_result_text"
    private const val REQUEST_LOCATION_PERMISSION = 1
    private const val REQUEST_LOCATION_SETTINGS = 2

    @JvmStatic fun open(context: Context): Boolean {
      context.startActivity(Intent(context, CurrentLocationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return true
    }
  }
}
