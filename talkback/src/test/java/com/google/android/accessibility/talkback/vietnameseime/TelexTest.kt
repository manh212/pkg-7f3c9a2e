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
    assertEquals("NGUYỄN", type("NGUYEENX"))
  }

  @Test
  fun canReplaceRemoveAndEscapeToneOrShape() {
    assertEquals("tì", type("tisf"))
    assertEquals("ti", type("tisz"))
    assertEquals("tis", type("tiss"))
    assertEquals("aa", type("aaa"))
    assertEquals("dd", type("ddd"))
    assertEquals("uow", type("uoww"))
    assertEquals("hòa", type("hoaf"))
    assertEquals("khỏe", type("khoer"))
    assertEquals("thủy", type("thuyr"))
  }

  private fun type(keys: String): String = keys.fold("", Telex::apply)
}
