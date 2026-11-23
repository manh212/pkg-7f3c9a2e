package com.google.android.accessibility.talkback.vietnameseime

import android.content.Context
import android.content.res.Configuration
import com.google.android.accessibility.utils.output.SpeechCleanupUtils
import java.util.Locale

internal class VietnameseKeyNames(context: Context) {
  private val vietnameseContext = context.createConfigurationContext(
    Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("vi-VN")) },
  )

  fun spokenName(label: String): String {
    val key = label.singleOrNull() ?: return label
    val letter = LETTER_NAMES[key.lowercaseChar()] ?: DIGIT_NAMES[key] ?: SYMBOL_NAMES[key]
    if (letter != null) return if (key.isUpperCase()) "$letter hoa" else letter
    return SpeechCleanupUtils.characterToName(vietnameseContext, key) ?: label
  }

  private companion object {
    val LETTER_NAMES = mapOf(
      'a' to "a", 'b' to "bê", 'c' to "xê", 'd' to "dê", 'e' to "e", 'f' to "ép",
      'g' to "gờ", 'h' to "hát", 'i' to "i", 'j' to "gi", 'k' to "ca", 'l' to "lờ",
      'm' to "mờ", 'n' to "nờ", 'o' to "o", 'p' to "pê", 'q' to "quy", 'r' to "rờ",
      's' to "sờ", 't' to "tê", 'u' to "u", 'v' to "vê", 'w' to "vê kép", 'x' to "ích",
      'y' to "i dài", 'z' to "dét", 'đ' to "đê",
    )
    val DIGIT_NAMES = mapOf(
      '0' to "không", '1' to "một", '2' to "hai", '3' to "ba", '4' to "bốn",
      '5' to "năm", '6' to "sáu", '7' to "bảy", '8' to "tám", '9' to "chín",
    )
    val SYMBOL_NAMES = mapOf(
      '+' to "cộng",
      '₫' to "đồng",
      '€' to "ơ rô",
      '£' to "bảng anh",
      '¥' to "yên",
      '¢' to "xen",
      '₹' to "ru pi",
      '₩' to "won",
      '₽' to "rúp",
      '₿' to "bít coi",
      '×' to "nhân",
      '÷' to "chia",
      '±' to "cộng trừ",
      '°' to "độ",
      '≠' to "khác",
      '≈' to "xấp xỉ",
      '≤' to "nhỏ hơn hoặc bằng",
      '≥' to "lớn hơn hoặc bằng",
      '…' to "ba chấm",
      '•' to "dấu chấm tròn",
      '–' to "gạch nối ngắn",
      '—' to "gạch nối dài",
      '“' to "mở ngoặc kép",
      '”' to "đóng ngoặc kép",
      '‘' to "mở ngoặc đơn",
      '’' to "đóng ngoặc đơn",
      '←' to "mũi tên trái",
      '→' to "mũi tên phải",
      '↑' to "mũi tên lên",
      '↓' to "mũi tên xuống",
      '✓' to "dấu tích",
      '✗' to "dấu gạch chéo",
      '©' to "bản quyền",
      '®' to "nhãn hiệu đã đăng ký",
      '™' to "thương hiệu",
      '§' to "ký hiệu mục",
      '∞' to "vô cực",
      '√' to "căn bậc hai",
      'π' to "pi",
      '∆' to "đen ta",
      '∑' to "tổng xích ma",
      '∏' to "tích pi",
      '∫' to "tích phân",
      '‰' to "phần nghìn",
      '¶' to "ký hiệu đoạn",
      '†' to "dấu chữ thập",
      '‡' to "dấu chữ thập kép",
      '№' to "số hiệu",
      '«' to "mở ngoặc nhọn kép",
      '»' to "đóng ngoặc nhọn kép",
      '‹' to "mở ngoặc nhọn đơn",
      '›' to "đóng ngoặc nhọn đơn",
      '„' to "mở ngoặc kép dưới",
      '‚' to "mở ngoặc đơn dưới",
      '¡' to "chấm than ngược",
      '¿' to "chấm hỏi ngược",
      '¦' to "gạch đứng đứt",
      '¬' to "dấu phủ định",
      '¯' to "gạch ngang trên",
      '¨' to "dấu hai chấm trên",
      '´' to "dấu sắc",
      '¸' to "dấu móc dưới",
      '·' to "dấu chấm giữa",
      '⁰' to "mũ không",
      '¹' to "mũ một",
      '²' to "mũ hai",
      '³' to "mũ ba",
      '⁴' to "mũ bốn",
      '⁵' to "mũ năm",
      '⁶' to "mũ sáu",
      '⁷' to "mũ bảy",
      '⁸' to "mũ tám",
      '⁹' to "mũ chín",
      '¼' to "một phần tư",
      '½' to "một phần hai",
      '¾' to "ba phần tư",
      '⅓' to "một phần ba",
      '⅔' to "hai phần ba",
      '★' to "ngôi sao đặc",
      '☆' to "ngôi sao rỗng",
      '♠' to "chất bích",
      '♣' to "chất tép",
      '♥' to "chất cơ",
      '♦' to "chất rô",
      '♪' to "nốt móc đơn",
      '♫' to "nốt nhạc kép",
    )
  }
}
