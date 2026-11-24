package com.google.android.accessibility.talkback.status

import android.location.Address
import android.location.Location
import android.location.LocationManager
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrentLocationReaderTest {
  @Test fun readsAvailableAddressPartsWithoutInventingMissingStreet() {
    val address = Address(Locale.forLanguageTag("vi")).apply {
      subLocality = "Phường A"
      locality = "Thành phố B"
      adminArea = "Thành phố B"
    }
    assertEquals("Phường A, Thành phố B", CurrentLocationReader.addressText(address))
  }

  @Test fun missingAddressNeverBecomesCoordinates() {
    val address = Address(Locale.ROOT).apply { latitude = 10.0; longitude = 106.0 }
    assertEquals("", CurrentLocationReader.addressText(address))
    assertEquals("", CurrentLocationReader.addressText(null))
  }

  @Test fun includesHouseNumberAndDeduplicatesVietnamesePrefixes() {
    val address = Address(Locale.forLanguageTag("vi")).apply {
      subThoroughfare = "123"
      thoroughfare = "Nguyễn Trãi"
      subLocality = "Phường Thượng Đình"
      subAdminArea = "Quận Thanh Xuân"
      locality = "Hà Nội"
      adminArea = "Thành phố Hà Nội"
    }
    assertEquals(
      "123 Nguyễn Trãi, Phường Thượng Đình, Quận Thanh Xuân, Thành phố Hà Nội",
      CurrentLocationReader.addressText(address),
    )
  }

  @Test fun ignoresPlusCodesInFallbackAddressLine() {
    val address = Address(Locale.forLanguageTag("vi")).apply {
      setAddressLine(0, "2R8J+4X, Dịch Vọng Hậu, Cầu Giấy, Hà Nội, 100000")
    }
    assertEquals("Dịch Vọng Hậu, Cầu Giấy, Hà Nội", CurrentLocationReader.addressText(address))
  }

  @Test fun parsesNominatimVietnameseAddressWithLandmark() {
    val json = """
      {
        "name": "Bệnh viện Bạch Mai",
        "address": {
          "house_number": "78",
          "road": "Giải Phóng",
          "quarter": "Phường Phương Mai",
          "city_district": "Quận Đống Đa",
          "city": "Thành phố Hà Nội"
        }
      }
    """.trimIndent()
    val candidate = LocationAddressResolver.parseNominatimJson(json)
    assertNotNull(candidate)
    assertTrue(candidate!!.hasHouseNumber)
    assertTrue(candidate.hasStreet)
    assertEquals(
      "Bệnh viện Bạch Mai, 78 Giải Phóng, Phường Phương Mai, Quận Đống Đa, Thành phố Hà Nội",
      candidate.text,
    )
  }

  @Test fun parsesPhotonReverseJson() {
    val json = """
      {
        "features": [
          {
            "properties": {
              "housenumber": "15",
              "street": "Lê Duẩn",
              "district": "Phường Bến Nghé",
              "county": "Quận 1",
              "city": "Thành phố Hồ Chí Minh"
            }
          }
        ]
      }
    """.trimIndent()
    val candidate = LocationAddressResolver.parsePhotonJson(json)
    assertNotNull(candidate)
    assertTrue(candidate!!.hasHouseNumber)
    assertTrue(candidate.hasStreet)
    assertEquals(
      "15 Lê Duẩn, Phường Bến Nghé, Quận 1, Thành phố Hồ Chí Minh",
      candidate.text,
    )
  }

  @Test fun picksMoreCompleteCandidateAcrossSources() {
    val coarseAndroid = LocationAddressResolver.AddressCandidate(
      text = "Phường Dịch Vọng, Quận Cầu Giấy, Hà Nội",
      score = 40,
      hasHouseNumber = false,
      hasStreet = false,
      source = LocationAddressResolver.SOURCE_ANDROID_GEOCODER,
    )
    val detailedNominatim = LocationAddressResolver.AddressCandidate(
      text = "24 Xuân Thủy, Phường Dịch Vọng, Quận Cầu Giấy, Hà Nội",
      score = 105,
      hasHouseNumber = true,
      hasStreet = true,
      source = LocationAddressResolver.SOURCE_NOMINATIM,
    )
    val chosen = LocationAddressResolver.pickBestCandidate(listOf(coarseAndroid, detailedNominatim))
    assertEquals(detailedNominatim, chosen)
  }

  @Test fun betterLocationPrefersAccurateGpsOverNoisyNetworkFix() {
    val networkFix = Location(LocationManager.NETWORK_PROVIDER).apply {
      accuracy = 250f
      elapsedRealtimeNanos = 10_000_000_000L
    }
    val gpsFix = Location(LocationManager.GPS_PROVIDER).apply {
      accuracy = 8f
      elapsedRealtimeNanos = 12_000_000_000L
    }
    val laterNoisyNetwork = Location(LocationManager.NETWORK_PROVIDER).apply {
      accuracy = 120f
      elapsedRealtimeNanos = 14_000_000_000L
    }

    assertTrue(CurrentLocationReader.isBetterLocation(gpsFix, networkFix))
    assertFalse(CurrentLocationReader.isBetterLocation(laterNoisyNetwork, gpsFix))
  }

  @Test fun refinementDelayLocksImmediatelyOnlyOnPreciseNonNetworkFix() {
    val tightGps = Location(LocationManager.GPS_PROVIDER).apply { accuracy = 7f }
    val tightNetwork = Location(LocationManager.NETWORK_PROVIDER).apply { accuracy = 9f }
    val moderateFused = Location("fused").apply { accuracy = 35f }
    val coarseNetwork = Location(LocationManager.NETWORK_PROVIDER).apply { accuracy = 180f }

    assertEquals(0L, CurrentLocationReader.refinementDelayMs(tightGps, fineGranted = true))
    assertEquals(
      CurrentLocationReader.FAST_REFINE_DELAY_MS,
      CurrentLocationReader.refinementDelayMs(tightNetwork, fineGranted = true),
    )
    assertEquals(
      CurrentLocationReader.MODERATE_REFINE_DELAY_MS,
      CurrentLocationReader.refinementDelayMs(moderateFused, fineGranted = true),
    )
    assertNull(CurrentLocationReader.refinementDelayMs(coarseNetwork, fineGranted = true))
  }
}
