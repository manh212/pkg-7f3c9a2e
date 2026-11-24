package com.google.android.accessibility.talkback.status

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest as FrameworkLocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest as GmsLocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.math.roundToInt

class CurrentLocationReader(private val context: Context, private val announce: Consumer<String>) {
  private val handler = Handler(Looper.getMainLooper())
  private val manager = context.getSystemService(LocationManager::class.java)
  private val worker = Executors.newFixedThreadPool(3) { task ->
    Thread(task, "current-address").apply { isDaemon = true }
  }
  private val resolver = LocationAddressResolver(context, worker)

  private var frameworkListener: LocationListener? = null
  private var fusedClient: FusedLocationProviderClient? = null
  private var fusedCallback: LocationCallback? = null

  private var generation = 0
  private var startedElapsedMs = 0L
  private var hasFinePermission = false
  private var resolvedLocation = false
  private var bestLocation: Location? = null
  private var lastKnownFallback: Location? = null

  private val locationTimeout = Runnable {
    val request = generation
    if (resolvedLocation) return@Runnable
    val chosen = bestLocation ?: lastKnownFallback
    if (chosen != null) {
      lockAndGeocode(request, chosen)
    } else {
      finish(request, context.getString(R.string.location_unavailable))
    }
  }

  private val refineTimeout = Runnable {
    val request = generation
    if (resolvedLocation) return@Runnable
    bestLocation?.let { lockAndGeocode(request, it) }
  }

  private val geocodeTimeout = Runnable {
    finish(generation, context.getString(R.string.location_address_unavailable))
  }

  @SuppressLint("MissingPermission")
  fun read(): Boolean {
    cancel()
    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!fineGranted && !coarseGranted) {
      announce.accept(context.getString(R.string.location_permission_needed))
      return false
    }

    val manager = manager ?: return unavailable(R.string.location_unavailable)
    if (!isLocationSystemEnabled(manager)) {
      return unavailable(R.string.location_disabled)
    }

    val request = generation
    hasFinePermission = fineGranted
    startedElapsedMs = SystemClock.elapsedRealtime()
    resolvedLocation = false
    bestLocation = null
    lastKnownFallback = findRecentLastKnown(manager, fineGranted, startedElapsedMs)

    var registered = false
    if (startFusedPlayServicesUpdates(request, fineGranted)) {
      registered = true
    }
    if (startFrameworkUpdates(manager, request, fineGranted)) {
      registered = true
    }

    if (!registered) {
      val fallback = lastKnownFallback
      if (fallback != null) {
        lockAndGeocode(request, fallback)
        return true
      }
      cancel()
      return unavailable(R.string.location_unavailable)
    }

    handler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS)
    return true
  }

  private fun isLocationSystemEnabled(manager: LocationManager): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      if (runCatching { manager.isLocationEnabled }.getOrDefault(false)) return true
    }
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
      .any { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
  }

  @SuppressLint("MissingPermission")
  private fun startFusedPlayServicesUpdates(request: Int, fineGranted: Boolean): Boolean {
    val gmsAvailable = runCatching {
      GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
    if (!gmsAvailable) return false

    return runCatching {
      val client = LocationServices.getFusedLocationProviderClient(context)
      val locationRequest = buildHighAccuracyGmsRequest(fineGranted)
      val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          for (location in result.locations) {
            onCandidateLocation(request, location)
          }
        }
      }
      client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
      fusedClient = client
      fusedCallback = callback
      true
    }.getOrDefault(false)
  }

  @SuppressLint("MissingPermission")
  private fun startFrameworkUpdates(
    manager: LocationManager,
    request: Int,
    fineGranted: Boolean,
  ): Boolean {
    val providers = buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(LocationManager.FUSED_PROVIDER)
      }
      if (fineGranted) {
        add(LocationManager.GPS_PROVIDER)
      }
      add(LocationManager.NETWORK_PROVIDER)
    }.filter { provider ->
      runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    if (providers.isEmpty()) return false

    val callback = object : LocationListener {
      override fun onLocationChanged(location: Location) {
        onCandidateLocation(request, location)
      }

      @Deprecated("Deprecated in Java")
      override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
      override fun onProviderEnabled(provider: String) {}
      override fun onProviderDisabled(provider: String) {}
    }
    frameworkListener = callback

    var registered = false
    for (provider in providers) {
      val ok = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val quality = if (fineGranted) {
            FrameworkLocationRequest.QUALITY_HIGH_ACCURACY
          } else {
            FrameworkLocationRequest.QUALITY_BALANCED_POWER_ACCURACY
          }
          val req = FrameworkLocationRequest.Builder(UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setQuality(quality)
            .build()
          manager.requestLocationUpdates(provider, req, context.mainExecutor, callback)
        } else {
          manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, callback, Looper.getMainLooper())
        }
        true
      }.getOrDefault(false)
      if (ok) registered = true
    }

    if (!registered) {
      frameworkListener = null
    }
    return registered
  }

  @SuppressLint("MissingPermission")
  private fun findRecentLastKnown(
    manager: LocationManager,
    fineGranted: Boolean,
    nowElapsedMs: Long,
  ): Location? {
    val providers = buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
      if (fineGranted) add(LocationManager.GPS_PROVIDER)
      add(LocationManager.NETWORK_PROVIDER)
    }
    var best: Location? = null
    for (provider in providers) {
      val loc = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
      val ageMs = nowElapsedMs - (loc.elapsedRealtimeNanos / 1_000_000L)
      if (ageMs < -2_000L || ageMs > MAX_LAST_KNOWN_AGE_MS) continue
      if (isBetterLocation(loc, best)) {
        best = loc
      }
    }
    return best
  }

  private fun onCandidateLocation(request: Int, location: Location) {
    if (request != generation || resolvedLocation) return
    val fixElapsedMs = location.elapsedRealtimeNanos / 1_000_000L
    if (fixElapsedMs < startedElapsedMs - MAX_LIVE_STALENESS_MS) return

    if (!isBetterLocation(location, bestLocation)) return
    bestLocation = location

    val delayMs = refinementDelayMs(location, hasFinePermission)
    if (delayMs == 0L) {
      lockAndGeocode(request, location)
    } else if (delayMs != null) {
      handler.removeCallbacks(refineTimeout)
      handler.postDelayed(refineTimeout, delayMs)
    }
  }

  private fun lockAndGeocode(request: Int, location: Location) {
    if (request != generation || resolvedLocation) return
    resolvedLocation = true
    handler.removeCallbacks(locationTimeout)
    handler.removeCallbacks(refineTimeout)
    stopUpdates()
    handler.postDelayed(geocodeTimeout, GEOCODE_TIMEOUT_MS)

    val accuracyMeters = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
    val fineGranted = hasFinePermission
    worker.execute {
      val rawAddress = runCatching {
        resolver.resolve(location.latitude, location.longitude, Locale.getDefault())
      }.getOrDefault("")
      val spoken = formatSpokenLocation(rawAddress, accuracyMeters, fineGranted)
      handler.post { finish(request, spoken) }
    }
  }

  private fun formatSpokenLocation(
    address: String,
    accuracyMeters: Float,
    fineGranted: Boolean,
  ): String {
    if (address.isBlank()) {
      return context.getString(R.string.location_address_unavailable)
    }
    if (!fineGranted) {
      return context.getString(R.string.location_approximate_prefix, address)
    }
    if (accuracyMeters.isFinite() && accuracyMeters > ACCURACY_WARNING_THRESHOLD_M) {
      return context.getString(
        R.string.location_estimated_accuracy_prefix,
        accuracyMeters.roundToInt(),
        address,
      )
    }
    return address
  }

  private fun finish(request: Int, text: String) {
    if (request != generation) return
    cancel()
    announce.accept(text)
  }

  private fun unavailable(message: Int): Boolean {
    announce.accept(context.getString(message))
    return false
  }

  private fun stopUpdates() {
    frameworkListener?.let { runCatching { manager?.removeUpdates(it) } }
    frameworkListener = null
    val client = fusedClient
    val callback = fusedCallback
    if (client != null && callback != null) {
      runCatching { client.removeLocationUpdates(callback) }
    }
    fusedClient = null
    fusedCallback = null
  }

  fun cancel() {
    generation++
    handler.removeCallbacks(locationTimeout)
    handler.removeCallbacks(refineTimeout)
    handler.removeCallbacks(geocodeTimeout)
    stopUpdates()
  }

  fun shutdown() {
    cancel()
    worker.shutdownNow()
  }

  companion object {
    internal const val LOCATION_TIMEOUT_MS = 10_000L
    internal const val GEOCODE_TIMEOUT_MS = 6_000L
    private const val UPDATE_INTERVAL_MS = 500L
    private const val MIN_UPDATE_INTERVAL_MS = 250L
    private const val MAX_LIVE_STALENESS_MS = 3_000L
    private const val MAX_LAST_KNOWN_AGE_MS = 45_000L

    internal const val INSTANT_LOCK_ACCURACY_M = 10f
    internal const val FAST_REFINE_ACCURACY_M = 20f
    internal const val MODERATE_REFINE_ACCURACY_M = 50f
    internal const val ACCURACY_WARNING_THRESHOLD_M = 50f

    internal const val FAST_REFINE_DELAY_MS = 1_500L
    internal const val MODERATE_REFINE_DELAY_MS = 4_000L

    internal fun buildHighAccuracyGmsRequest(fineGranted: Boolean): GmsLocationRequest {
      val priority = if (fineGranted) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
      val granularity = if (fineGranted) Granularity.GRANULARITY_FINE else Granularity.GRANULARITY_PERMISSION_LEVEL
      return GmsLocationRequest.Builder(priority, UPDATE_INTERVAL_MS)
        .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
        .setMaxUpdateDelayMillis(0L)
        .setGranularity(granularity)
        .setWaitForAccurateLocation(fineGranted)
        .build()
    }

    internal fun addressText(address: Address?): String = LocationAddressResolver.addressText(address)

    internal fun isBetterLocation(candidate: Location, currentBest: Location?): Boolean {
      if (currentBest == null) return true
      val candHasAcc = candidate.hasAccuracy() && candidate.accuracy > 0f
      val bestHasAcc = currentBest.hasAccuracy() && currentBest.accuracy > 0f
      if (!candHasAcc && bestHasAcc) return false
      if (candHasAcc && !bestHasAcc) return true

      if (candHasAcc && bestHasAcc) {
        val accuracyDelta = candidate.accuracy - currentBest.accuracy
        if (accuracyDelta < -5f) return true
        if (accuracyDelta > 5f) return false
      }

      val candRank = providerRank(candidate.provider)
      val bestRank = providerRank(currentBest.provider)
      if (candRank != bestRank) return candRank > bestRank

      return candidate.elapsedRealtimeNanos >= currentBest.elapsedRealtimeNanos
    }

    internal fun refinementDelayMs(location: Location, fineGranted: Boolean): Long? {
      if (!fineGranted) {
        return FAST_REFINE_DELAY_MS
      }
      if (!location.hasAccuracy() || location.accuracy <= 0f) return null
      val accuracy = location.accuracy
      val isNetworkOnly = location.provider == LocationManager.NETWORK_PROVIDER
      return when {
        accuracy <= INSTANT_LOCK_ACCURACY_M && !isNetworkOnly -> 0L
        accuracy <= FAST_REFINE_ACCURACY_M -> FAST_REFINE_DELAY_MS
        accuracy <= MODERATE_REFINE_ACCURACY_M -> MODERATE_REFINE_DELAY_MS
        else -> null
      }
    }

    private fun providerRank(provider: String?): Int = when (provider) {
      LocationManager.GPS_PROVIDER -> 3
      "fused" -> 2
      LocationManager.NETWORK_PROVIDER -> 1
      else -> 0
    }
  }
}
