package com.google.android.accessibility.talkback.status

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resolves coordinates into the most complete human-readable address by combining
 * Android's built-in [Geocoder] with OpenStreetMap Nominatim and Komoot Photon fallbacks.
 */
internal class LocationAddressResolver(
  private val context: Context,
  private val worker: ExecutorService,
  private val httpClient: OkHttpClient = defaultHttpClient(),
) {

  data class AddressCandidate(
    val text: String,
    val score: Int,
    val hasHouseNumber: Boolean,
    val hasStreet: Boolean,
    val source: String,
  )

  fun resolve(latitude: Double, longitude: Double, locale: Locale = Locale.getDefault()): String {
    val geocoderRef = AtomicReference<AddressCandidate?>(null)
    val nominatimRef = AtomicReference<AddressCandidate?>(null)
    val latch = CountDownLatch(2)

    worker.execute {
      try {
        geocoderRef.set(resolveWithAndroidGeocoder(latitude, longitude, locale))
      } catch (_: Throwable) {
      } finally {
        latch.countDown()
      }
    }

    worker.execute {
      try {
        nominatimRef.set(resolveWithNominatim(latitude, longitude, locale))
      } catch (_: Throwable) {
      } finally {
        latch.countDown()
      }
    }

    runCatching { latch.await(PARALLEL_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }

    val candidates = mutableListOf<AddressCandidate>()
    geocoderRef.get()?.let(candidates::add)
    nominatimRef.get()?.let(candidates::add)

    val needsThirdSource = candidates.none { it.hasStreet && it.hasHouseNumber } &&
      candidates.none { it.score >= STRONG_CANDIDATE_SCORE }
    if (needsThirdSource) {
      runCatching { resolveWithPhoton(latitude, longitude) }.getOrNull()?.let(candidates::add)
    }

    return pickBestCandidate(candidates)?.text.orEmpty()
  }

  private fun resolveWithAndroidGeocoder(
    latitude: Double,
    longitude: Double,
    locale: Locale,
  ): AddressCandidate? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, locale)
    val addresses: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
      val resultRef = AtomicReference<List<Address>>(emptyList())
      val latch = CountDownLatch(1)
      try {
        geocoder.getFromLocation(
          latitude,
          longitude,
          MAX_GEOCODER_RESULTS,
          object : Geocoder.GeocodeListener {
            override fun onGeocode(list: MutableList<Address>) {
              resultRef.set(list)
              latch.countDown()
            }

            override fun onError(errorMessage: String?) {
              latch.countDown()
            }
          },
        )
        latch.await(SINGLE_SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      } catch (_: Throwable) {
      }
      resultRef.get()
    } else {
      @Suppress("DEPRECATION")
      runCatching { geocoder.getFromLocation(latitude, longitude, MAX_GEOCODER_RESULTS).orEmpty() }
        .getOrDefault(emptyList())
    }

    return addresses.mapNotNull(::candidateFromAndroidAddress).maxByOrNull { it.score }
  }

  private fun resolveWithNominatim(
    latitude: Double,
    longitude: Double,
    locale: Locale,
  ): AddressCandidate? {
    val lang = URLEncoder.encode(locale.toLanguageTag().ifBlank { "vi" }, "UTF-8")
    val url = String.format(
      Locale.US,
      "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.7f&lon=%.7f&zoom=18&addressdetails=1&accept-language=%s",
      latitude,
      longitude,
      lang,
    )
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "application/json")
      .build()
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return null
      val body = response.body()?.string() ?: return null
      return parseNominatimJson(body)
    }
  }

  private fun resolveWithPhoton(latitude: Double, longitude: Double): AddressCandidate? {
    val url = String.format(
      Locale.US,
      "https://photon.komoot.io/reverse?lat=%.7f&lon=%.7f&limit=3",
      latitude,
      longitude,
    )
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "application/json")
      .build()
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return null
      val body = response.body()?.string() ?: return null
      return parsePhotonJson(body)
    }
  }

  companion object {
    internal const val SOURCE_ANDROID_GEOCODER = "android_geocoder"
    internal const val SOURCE_NOMINATIM = "nominatim"
    internal const val SOURCE_PHOTON = "photon"

    private const val MAX_GEOCODER_RESULTS = 5
    private const val PARALLEL_LOOKUP_TIMEOUT_MS = 4_200L
    private const val SINGLE_SOURCE_TIMEOUT_MS = 3_800L
    private const val STRONG_CANDIDATE_SCORE = 85
    private const val USER_AGENT = "MeridianScreenReader/1.0 (Android)"

    private val PLUS_CODE_REGEX = Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}$", RegexOption.IGNORE_CASE)
    private val PURE_NUMBER_REGEX = Regex("^[0-9/\\-A-Za-z]{1,8}$")
    private val ADMIN_PREFIX_REGEX = Regex(
      "^(thành phố|tp\\.?|tỉnh|quận|huyện|thị xã|tx\\.?|phường|xã|thị trấn|tt\\.?|đường|phố|ngõ|hẻm|khu phố|ấp|thôn)\\s+",
      RegexOption.IGNORE_CASE,
    )

    private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(3_000, TimeUnit.MILLISECONDS)
      .readTimeout(3_500, TimeUnit.MILLISECONDS)
      .callTimeout(4_000, TimeUnit.MILLISECONDS)
      .build()

    internal fun addressText(address: Address?): String {
      return candidateFromAndroidAddress(address)?.text.orEmpty()
    }

    internal fun candidateFromAndroidAddress(address: Address?): AddressCandidate? {
      if (address == null) return null
      val houseNumber = cleanPart(address.subThoroughfare)
      val street = cleanPart(address.thoroughfare)
      val ward = cleanPart(address.subLocality)
      val locality = cleanPart(address.locality)
      val district = cleanPart(address.subAdminArea)
      val province = cleanPart(address.adminArea)

      val rawLandmark = cleanPart(address.premises) ?: cleanPart(address.featureName)
      val landmark = rawLandmark?.takeIf { candidate ->
        !isPlusCode(candidate) &&
          !PURE_NUMBER_REGEX.matches(candidate) &&
          !samePlace(candidate, houseNumber) &&
          !samePlace(candidate, street) &&
          !samePlace(candidate, ward) &&
          !samePlace(candidate, locality) &&
          !samePlace(candidate, district) &&
          !samePlace(candidate, province)
      }

      val structured = buildCandidate(
        landmark = landmark,
        houseNumber = houseNumber,
        street = street,
        ward = ward,
        district = district,
        locality = locality,
        province = province,
        source = SOURCE_ANDROID_GEOCODER,
      )
      if (structured != null) return structured

      val fallbackLine = cleanAddressLine(address.getAddressLine(0))
      if (fallbackLine.isEmpty()) return null
      return AddressCandidate(
        text = fallbackLine,
        score = 20,
        hasHouseNumber = false,
        hasStreet = false,
        source = SOURCE_ANDROID_GEOCODER,
      )
    }

    internal fun parseNominatimJson(rawJson: String): AddressCandidate? {
      val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
      if (root.has("error")) return null
      val addr = root.optJSONObject("address") ?: return null

      val houseNumber = cleanPart(addr.optString("house_number"))
      val street = firstNonBlank(
        addr.optString("road"),
        addr.optString("pedestrian"),
        addr.optString("footway"),
        addr.optString("residential"),
        addr.optString("path"),
      )
      val ward = firstNonBlank(
        addr.optString("quarter"),
        addr.optString("suburb"),
        addr.optString("neighbourhood"),
        addr.optString("village"),
        addr.optString("hamlet"),
      )
      val district = firstNonBlank(
        addr.optString("city_district"),
        addr.optString("district"),
        addr.optString("county"),
        addr.optString("town"),
      )
      val city = firstNonBlank(
        addr.optString("city"),
        addr.optString("municipality"),
      )
      val province = firstNonBlank(
        addr.optString("state"),
        addr.optString("province"),
      )
      val rawName = firstNonBlank(
        root.optString("name"),
        addr.optString("amenity"),
        addr.optString("building"),
        addr.optString("shop"),
        addr.optString("office"),
      )
      val landmark = rawName?.takeIf { candidate ->
        !isPlusCode(candidate) &&
          !PURE_NUMBER_REGEX.matches(candidate) &&
          !samePlace(candidate, houseNumber) &&
          !samePlace(candidate, street) &&
          !samePlace(candidate, ward) &&
          !samePlace(candidate, district) &&
          !samePlace(candidate, city) &&
          !samePlace(candidate, province)
      }

      return buildCandidate(
        landmark = landmark,
        houseNumber = houseNumber,
        street = street,
        ward = ward,
        district = district,
        locality = city,
        province = province,
        source = SOURCE_NOMINATIM,
      )
    }

    internal fun parsePhotonJson(rawJson: String): AddressCandidate? {
      val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
      val features = root.optJSONArray("features") ?: return null
      val candidates = mutableListOf<AddressCandidate>()
      for (i in 0 until features.length()) {
        val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
        val houseNumber = cleanPart(props.optString("housenumber"))
        val street = cleanPart(props.optString("street"))
        val ward = firstNonBlank(props.optString("district"), props.optString("locality"))
        val district = cleanPart(props.optString("county"))
        val city = cleanPart(props.optString("city"))
        val province = cleanPart(props.optString("state"))
        val rawName = cleanPart(props.optString("name"))
        val landmark = rawName?.takeIf { candidate ->
          !isPlusCode(candidate) &&
            !PURE_NUMBER_REGEX.matches(candidate) &&
            !samePlace(candidate, houseNumber) &&
            !samePlace(candidate, street) &&
            !samePlace(candidate, ward) &&
            !samePlace(candidate, district) &&
            !samePlace(candidate, city) &&
            !samePlace(candidate, province)
        }
        buildCandidate(
          landmark = landmark,
          houseNumber = houseNumber,
          street = street,
          ward = ward,
          district = district,
          locality = city,
          province = province,
          source = SOURCE_PHOTON,
        )?.let(candidates::add)
      }
      return candidates.maxByOrNull { it.score }
    }

    internal fun pickBestCandidate(candidates: List<AddressCandidate>): AddressCandidate? {
      return candidates
        .filter { it.text.isNotBlank() }
        .maxWithOrNull(
          compareBy<AddressCandidate> { it.score }
            .thenBy { if (it.hasHouseNumber && it.hasStreet) 1 else 0 }
            .thenBy { if (it.source == SOURCE_ANDROID_GEOCODER) 1 else 0 }
            .thenBy { it.text.length },
        )
    }

    private fun buildCandidate(
      landmark: String?,
      houseNumber: String?,
      street: String?,
      ward: String?,
      district: String?,
      locality: String?,
      province: String?,
      source: String,
    ): AddressCandidate? {
      val streetLine = when {
        houseNumber != null && street != null -> {
          if (street.startsWith(houseNumber, ignoreCase = true)) street else "$houseNumber $street"
        }
        street != null -> street
        else -> null
      }

      val ordered = listOfNotNull(landmark, streetLine, ward, district, locality, province)
      val deduped = deduplicateAdminParts(ordered)
      if (deduped.isEmpty()) return null

      var score = 0
      val hasHouse = houseNumber != null && street != null
      val hasStreet = street != null
      if (hasHouse) score += 35
      if (hasStreet) score += 35
      if (ward != null) score += 15
      if (district != null) score += 10
      if (locality != null || province != null) score += 10
      if (landmark != null) score += 8
      if (source == SOURCE_ANDROID_GEOCODER) score += 5

      return AddressCandidate(
        text = deduped.joinToString(", "),
        score = score,
        hasHouseNumber = hasHouse,
        hasStreet = hasStreet,
        source = source,
      )
    }

    private fun deduplicateAdminParts(parts: List<String>): List<String> {
      val result = mutableListOf<String>()
      for (part in parts) {
        val existingIndex = result.indexOfFirst { samePlace(it, part) }
        if (existingIndex == -1) {
          result.add(part)
        } else if (part.length > result[existingIndex].length) {
          result[existingIndex] = part
        }
      }
      return result
    }

    private fun cleanPart(raw: String?): String? {
      val trimmed = raw?.trim().orEmpty()
      if (trimmed.isEmpty() || trimmed.equals("unnamed road", ignoreCase = true)) return null
      if (isPlusCode(trimmed)) return null
      return trimmed
    }

    private fun cleanAddressLine(raw: String?): String {
      if (raw.isNullOrBlank()) return ""
      val segments = raw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !isPlusCode(it) && !it.matches(Regex("^\\d{5,6}$")) }
      return deduplicateAdminParts(segments).joinToString(", ")
    }

    private fun firstNonBlank(vararg values: String?): String? {
      for (value in values) {
        val cleaned = cleanPart(value)
        if (cleaned != null) return cleaned
      }
      return null
    }

    private fun isPlusCode(text: String): Boolean {
      val firstToken = text.trim().substringBefore(' ')
      return PLUS_CODE_REGEX.matches(firstToken)
    }

    private fun samePlace(a: String?, b: String?): Boolean {
      if (a.isNullOrBlank() || b.isNullOrBlank()) return false
      val normA = normalizePlace(a)
      val normB = normalizePlace(b)
      return normA.isNotEmpty() && normA == normB
    }

    private fun normalizePlace(text: String): String {
      return text.trim()
        .replace(ADMIN_PREFIX_REGEX, "")
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
    }
  }
}
