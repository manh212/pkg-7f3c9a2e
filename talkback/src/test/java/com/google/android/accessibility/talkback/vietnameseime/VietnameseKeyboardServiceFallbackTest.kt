package com.google.android.accessibility.talkback.vietnameseime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.google.android.accessibility.talkback.TalkBackService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VietnameseKeyboardServiceFallbackTest {
  private val appPackage = "io.github.aaron_gh.meridian"
  private val meridianComponent = "$appPackage/${TalkBackService::class.java.name}"

  @Test
  fun fallbackWhenServiceDownAndMeridianDisabled() {
    assertTrue(
      VietnameseKeyboardService.shouldUseFallbackSpeech(
        serviceUp = false, meridianEnabled = false))
  }

  @Test
  fun silentWhenServiceDownButMeridianEnabled() {
    assertFalse(
      VietnameseKeyboardService.shouldUseFallbackSpeech(
        serviceUp = false, meridianEnabled = true))
  }

  @Test
  fun noFallbackWhenServiceUpAndMeridianDisabled() {
    assertFalse(
      VietnameseKeyboardService.shouldUseFallbackSpeech(
        serviceUp = true, meridianEnabled = false))
  }

  @Test
  fun noFallbackWhenServiceUpAndMeridianEnabled() {
    assertFalse(
      VietnameseKeyboardService.shouldUseFallbackSpeech(
        serviceUp = true, meridianEnabled = true))
  }

  @Test
  fun enabledServicesStringFindsExactComponent() {
    assertTrue(
      VietnameseKeyboardService.isMeridianInEnabledServices(meridianComponent, appPackage))
    assertTrue(
      VietnameseKeyboardService.isMeridianInEnabledServices(
        "com.example.other/.OtherService:$meridianComponent:com.example.third/.ThirdService",
        appPackage))
  }

  @Test
  fun enabledServicesStringIgnoresOtherPackagesAndServices() {
    assertFalse(
      VietnameseKeyboardService.isMeridianInEnabledServices(
        "com.example.other/${TalkBackService::class.java.name}", appPackage))
    assertFalse(
      VietnameseKeyboardService.isMeridianInEnabledServices(
        "$appPackage/com.example.OtherService", appPackage))
    assertFalse(
      VietnameseKeyboardService.isMeridianInEnabledServices(
        "com.example.other/.OtherService", appPackage))
  }

  @Test
  fun enabledServicesStringNullOrEmptyIsNotEnabled() {
    assertFalse(VietnameseKeyboardService.isMeridianInEnabledServices(null, appPackage))
    assertFalse(VietnameseKeyboardService.isMeridianInEnabledServices("", appPackage))
  }

  @Test
  fun serviceListFindsExactComponent() {
    val services = listOf(
      serviceEntry("com.example.other", "com.example.OtherService"),
      serviceEntry(appPackage, TalkBackService::class.java.name),
    )
    assertTrue(VietnameseKeyboardService.isMeridianInServiceList(services, appPackage))
  }

  @Test
  fun serviceListIgnoresOtherPackagesAndServices() {
    val services = listOf(
      serviceEntry("com.example.other", TalkBackService::class.java.name),
      serviceEntry(appPackage, "com.example.OtherService"),
      AccessibilityServiceInfo(),
    )
    assertFalse(VietnameseKeyboardService.isMeridianInServiceList(services, appPackage))
  }

  @Test
  fun serviceListEmptyIsNotEnabled() {
    assertFalse(VietnameseKeyboardService.isMeridianInServiceList(emptyList(), appPackage))
  }

  private fun serviceEntry(packageName: String, className: String): AccessibilityServiceInfo {
    val service = ServiceInfo()
    service.packageName = packageName
    service.name = className
    val resolve = ResolveInfo()
    resolve.serviceInfo = service
    val info = AccessibilityServiceInfo()
    AccessibilityServiceInfo::class.java.getDeclaredField("mResolveInfo").apply {
      isAccessible = true
      set(info, resolve)
    }
    return info
  }
}
