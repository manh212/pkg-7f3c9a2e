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
  fun quickTelexExpandsInitialsBeforeWBracketAndShapedVowels() {
    // cc -> ch
    assertEquals("chư", type("ccw", quickTelex = true))
    assertEquals("chưa", type("ccwa", quickTelex = true))
    assertEquals("chứa", type("ccwas", quickTelex = true))
    assertEquals("chương", type("ccwong", quickTelex = true))
    assertEquals("CHƯA", type("CCWA", quickTelex = true))
    assertEquals("Chưa", type("Ccwa", quickTelex = true))
    assertEquals("chuw", type("ccww", quickTelex = true))
    assertEquals("chơ", type("cc[", quickTelex = true))
    assertEquals("chưa", type("cc]a", quickTelex = true))

    // All other quick initials with w -> ư and following vowels/codas/tones
    assertEquals("trưa", type("ttwa", quickTelex = true))
    assertEquals("trường", type("ttwongf", quickTelex = true))
    assertEquals("trời", type("tt[if", quickTelex = true))
    assertEquals("thưa", type("tttwa", quickTelex = true))
    assertEquals("thương", type("tttwong", quickTelex = true))
    assertEquals("thơ", type("ttt[", quickTelex = true))
    assertEquals("giữ", type("ggwx", quickTelex = true))
    assertEquals("giường", type("ggwongf", quickTelex = true))
    assertEquals("khứ", type("kkws", quickTelex = true))
    assertEquals("khưa", type("kkwa", quickTelex = true))
    assertEquals("người", type("nnwoif", quickTelex = true))
    assertEquals("ngựa", type("nnwaj", quickTelex = true))
    assertEquals("NGƯỜI", type("NNWOIF", quickTelex = true))
    assertEquals("nghê", type("nnê", quickTelex = true))
    assertEquals("phương", type("ppwong", quickTelex = true))
    assertEquals("nhưng", type("hhwng", quickTelex = true))
    assertEquals("nhựa", type("hhwaj", quickTelex = true))
    assertEquals("quơ", type("qq[", quickTelex = true))

    // Guardrails: qq + w must not produce invalid "quư"; mid-word cc + w must not expand.
    assertEquals("qqư", type("qqw", quickTelex = true))
    assertEquals("lccưa", type("lccwa", quickTelex = true))
    assertEquals("ccưa", type("ccwa", quickTelex = false))
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
  fun delayedCircumflexShapesVietnameseSyllablesAndStillProtectsNonVietnameseCodas() {
    assertEquals("tân", type("tana"))
    assertEquals("tana", type("tanaa"))
    assertEquals("tông", type("tongo"))
    assertEquals("tongo", type("tongoo"))
    assertEquals("tên", type("tene"))
    assertEquals("tấn", type("tansa"))
    assertEquals("tống", type("tongso"))
    assertEquals("tiếng", type("tiengse"))
    assertEquals("quốc", type("quocso"))
    assertEquals("tôi", type("toio"))
    assertEquals("tây", type("taya"))
    assertEquals("tâu", type("taua"))
    assertEquals("kênh", type("kenhe"))
    assertEquals("số", type("soso"))
    assertEquals("soso", type("sosso"))
    assertEquals("tele", type("tele"))
    assertEquals("telex", type("telexx"))
    assertEquals("tẽle", type("telex"))
    assertEquals("masage", type("massage"))
    assertEquals("Masage", type("Massage"))
    assertEquals("casa", type("cassa"))
    assertEquals("bana", type("baana"))
  }

  @Test
  fun shapeKeyReshapesALoneTonedVowel() {
    assertEquals("ồ", type("ofo"))
    assertEquals("ầ", type("afa"))
    assertEquals("ề", type("efe"))
    assertEquals("ố", type("oso"))
    assertEquals("ổ", type("oro"))
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
  fun dKeyAfterVowelAppendsInsteadOfToggling() {
    assertEquals("dad", type("dad"))
    assertEquals("dada", type("dada"))
    assertEquals("did", type("did"))
    assertEquals("dud", type("dud"))
    assertEquals("dead", type("dead"))
    assertEquals("đ", type("dd"))
    assertEquals("dd", type("ddd"))
    assertEquals("đi", type("ddi"))
    assertEquals("DAD", type("DAD"))
    assertEquals("Đ", type("DD"))
  }

  @Test
  fun backspaceKeepsTheToneOnTheRemainingSyllable() {
    assertEquals("hòa", Telex.deleteLast("hoàn"))
    assertEquals("hò", Telex.deleteLast("hòa"))
    assertEquals("", Telex.deleteLast("ả"))
  }

  @Test
  fun standaloneWAndConsonantWProduceUWithHornLikeDesktopTelex() {
    assertEquals("ư", type("w"))
    assertEquals("Ư", type("W"))
    assertEquals("w", type("ww"))
    assertEquals("W", type("WW"))
    assertEquals("tư", type("tw"))
    assertEquals("tứ", type("tws"))
    assertEquals("ngư", type("ngw"))
    assertEquals("người", type("ngwoif"))
    assertEquals("trư", type("trw"))
    assertEquals("như", type("nhw"))
    assertEquals("chư", type("chw"))
    assertEquals("đư", type("ddw"))
    assertEquals("Tư", type("Tw"))
  }

  @Test
  fun bracketKeysProduceHornedVowelsAndEscapeOnRepeat() {
    assertEquals("ơ", type("["))
    assertEquals("ư", type("]"))
    assertEquals("Ơ", type("{"))
    assertEquals("Ư", type("}"))
    assertEquals("[", type("[["))
    assertEquals("]", type("]]"))
    assertEquals("tớ", type("t[s"))
    assertEquals("tứ", type("t]s"))
  }

  private fun type(keys: String, quickTelex: Boolean = false): String =
    keys.fold("") { word, key -> Telex.apply(word, key, quickTelex) }
}
