package com.google.android.accessibility.talkback.vietnameseime

import org.junit.Assert.assertEquals
import org.junit.Test

class TelexTest {
  @Test
  fun typesVietnameseWordsAndToneAfterFinalConsonant() {
    assertEquals("tiếng", type("tieengs"))
    assertEquals("người", type("nguowif"))
    assertEquals("đường", type("dduongwf"))
    assertEquals("quốc", type("quoocs"))
    assertEquals("quờ", type("quowf"))
    assertEquals("giữ", type("giuwx"))
    assertEquals("huỳnh", type("huynhf"))
    assertEquals("trăng", type("trangw"))
    assertEquals("huơ", type("huow"))
    assertEquals("thuở", type("thuowr"))
    assertEquals("hương", type("huowng"))
    assertEquals("thuý", type("thuys"))
    assertEquals("tuý", type("tuys"))
    assertEquals("NGUYỄN", type("NGUYEENX"))
  }

  @Test
  fun canReplaceRemoveAndEscapeToneOrShape() {
    assertEquals("tì", type("tisf"))
    assertEquals("ti", type("tisz"))
    assertEquals("tis", type("tiss"))
    assertEquals("aa", type("aaa"))
    assertEquals("dd", type("ddd"))
    assertEquals("ươ", type("uoww"))
    assertEquals("uơ", type("uow"))
    assertEquals("quw", type("quw"))
    assertEquals("quyw", type("quyw"))
    assertEquals("hòa", type("hoaf"))
    assertEquals("khỏe", type("khoer"))
    assertEquals("thuỷ", type("thuyr"))
  }

  @Test
  fun wModifiesTheMainVowelOfAVowelCluster() {
    assertEquals("ưa", type("uaw"))
    assertEquals("mưa", type("muaw"))
    assertEquals("hoăn", type("hoanw"))
    assertEquals("huăn", type("huanw"))
    assertEquals("ăo", type("aow"))
    assertEquals("ău", type("auw"))
    assertEquals("oă", type("oaw"))
    assertEquals("iă", type("iaw"))
    assertEquals("eow", type("eow"))
    assertEquals("oew", type("oew"))
    assertEquals("uew", type("uew"))
  }

  @Test
  fun backspaceKeepsTheToneOnTheRemainingSyllable() {
    assertEquals("hòa", Telex.deleteLast("hoàn"))
    assertEquals("hò", Telex.deleteLast("hòa"))
    assertEquals("", Telex.deleteLast("ả"))
  }

  private fun type(keys: String): String = keys.fold("", Telex::apply)
}
