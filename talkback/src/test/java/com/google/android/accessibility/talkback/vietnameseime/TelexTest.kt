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
  fun quickTelexExpandsInitialsWhenAVowelFollows() {
    assertEquals("chào", type("ccaof", quickTelex = true))
    assertEquals("trời", type("ttowif", quickTelex = true))
    assertEquals("gia", type("gga", quickTelex = true))
    assertEquals("không", type("kkoong", quickTelex = true))
    assertEquals("nga", type("nna", quickTelex = true))
    assertEquals("pha", type("ppa", quickTelex = true))
    assertEquals("qua", type("qqa", quickTelex = true))
    assertEquals("nha", type("hha", quickTelex = true))
    assertEquals("ghi", type("gggi", quickTelex = true))
    assertEquals("tha", type("ttta", quickTelex = true))
    assertEquals("nghi", type("nni", quickTelex = true))
    assertEquals("nghê", type("nnee", quickTelex = true))
    assertEquals("CHÀO", type("CCAOF", quickTelex = true))
    assertEquals("TT", type("TT", quickTelex = true))
    assertEquals("ggg", type("ggg", quickTelex = true))
    assertEquals("lcca", type("lcca", quickTelex = true))
    assertEquals("ccào", type("ccaof"))
  }

  @Test
  fun completesBothHornsWhenTypedSeparatelyOrBeforeTheEnding() {
    assertEquals("người", type("nguwowif"))
    assertEquals("người", type("nguwoif"))
    assertEquals("NGƯỜI", type("NGUWOWIF"))
    assertEquals("trường", type("truwowngf"))
    assertEquals("giường", type("giuwowngf"))
    assertEquals("hươu", type("huwou"))
    assertEquals("huơ", type("huow"))
    assertEquals("quờ", type("quowf"))
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
  fun vowelKeyAfterConsonantAppendsInsteadOfReshaping() {
    assertEquals("tele", type("tele"))
    assertEquals("telex", type("telexx"))
    assertEquals("tẽle", type("telex"))
    assertEquals("masage", type("massage"))
    assertEquals("Masage", type("Massage"))
    assertEquals("máa", type("masa"))
    assertEquals("cáa", type("casa"))
    assertEquals("casa", type("cassa"))
    assertEquals("papa", type("papa"))
    assertEquals("tete", type("tete"))
    assertEquals("banana", type("banana"))
    assertEquals("bâna", type("baana"))
    assertEquals("sóo", type("soso"))
    assertEquals("soso", type("sosso"))
  }

  @Test
  fun toneKeyLetterAfterSameLetterAppends() {
    assertEquals("massage", type("masssage"))
    assertEquals("Massage", type("Masssage"))
    assertEquals("MASSAGE", type("MASSSAGE"))
    assertEquals("boss", type("bosss"))
    assertEquals("class", type("classs"))
    assertEquals("buff", type("bufff"))
    assertEquals("fixx", type("fixxx"))
    assertEquals("tiss", type("tisss"))
    assertEquals("ass", type("asss"))
    assertEquals("telex", type("telexx"))
    assertEquals("tis", type("tiss"))
  }

  @Test
  fun backspaceKeepsTheToneOnTheRemainingSyllable() {
    assertEquals("hòa", Telex.deleteLast("hoàn"))
    assertEquals("hò", Telex.deleteLast("hòa"))
    assertEquals("", Telex.deleteLast("ả"))
  }

  private fun type(keys: String, quickTelex: Boolean = false): String =
    keys.fold("") { word, key -> Telex.apply(word, key, quickTelex) }
}
