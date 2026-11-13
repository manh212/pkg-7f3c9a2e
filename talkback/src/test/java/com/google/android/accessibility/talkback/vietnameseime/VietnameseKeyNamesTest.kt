package com.google.android.accessibility.talkback.vietnameseime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VietnameseKeyNamesTest {
  private val names = VietnameseKeyNames(RuntimeEnvironment.getApplication())

  @Test
  fun letterNamesArePureVietnamese() {
    assertEquals("a", names.spokenName("a"))
    assertEquals("bê", names.spokenName("b"))
    assertEquals("xê", names.spokenName("c"))
    assertEquals("dê", names.spokenName("d"))
    assertEquals("đê", names.spokenName("đ"))
    assertEquals("e", names.spokenName("e"))
    assertEquals("ép", names.spokenName("f"))
    assertEquals("gờ", names.spokenName("g"))
    assertEquals("hát", names.spokenName("h"))
    assertEquals("i", names.spokenName("i"))
    assertEquals("gi", names.spokenName("j"))
    assertEquals("ca", names.spokenName("k"))
    assertEquals("lờ", names.spokenName("l"))
    assertEquals("mờ", names.spokenName("m"))
    assertEquals("nờ", names.spokenName("n"))
    assertEquals("o", names.spokenName("o"))
    assertEquals("pê", names.spokenName("p"))
    assertEquals("quy", names.spokenName("q"))
    assertEquals("rờ", names.spokenName("r"))
    assertEquals("sờ", names.spokenName("s"))
    assertEquals("tê", names.spokenName("t"))
    assertEquals("u", names.spokenName("u"))
    assertEquals("vê", names.spokenName("v"))
    assertEquals("vê kép", names.spokenName("w"))
    assertEquals("ích", names.spokenName("x"))
    assertEquals("i dài", names.spokenName("y"))
    assertEquals("dét", names.spokenName("z"))
  }

  @Test
  fun digitsAreVietnameseNumberWords() {
    assertEquals("không", names.spokenName("0"))
    assertEquals("một", names.spokenName("1"))
    assertEquals("hai", names.spokenName("2"))
    assertEquals("ba", names.spokenName("3"))
    assertEquals("bốn", names.spokenName("4"))
    assertEquals("năm", names.spokenName("5"))
    assertEquals("sáu", names.spokenName("6"))
    assertEquals("bảy", names.spokenName("7"))
    assertEquals("tám", names.spokenName("8"))
    assertEquals("chín", names.spokenName("9"))
  }

  @Test
  fun plusSignAndUppercase() {
    assertEquals("cộng", names.spokenName("+"))
    assertEquals("nờ hoa", names.spokenName("N"))
    assertEquals("i dài hoa", names.spokenName("Y"))
  }

  @Test
  fun symbolsResolveToVietnameseNames() {
    assertEquals("Dấu phẩy", names.spokenName(","))
    assertEquals("Dấu chấm", names.spokenName("."))
  }

  @Test
  fun multiCharacterLabelsPassThrough() {
    assertEquals("Dấu cách", names.spokenName("Dấu cách"))
    assertEquals("qwerty", names.spokenName("qwerty"))
  }
}
