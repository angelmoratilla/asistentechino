package com.babelong.asistentechino.pinyin

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination

/**
 * Convierte texto chino (hanzi) a pinyin con marcas tonales, totalmente en el
 * dispositivo (sin conexión) gracias a pinyin4j.
 *
 * Los caracteres que no son hanzi se copian tal cual. Para caracteres
 * polifónicos (多音字) se usa la lectura más común.
 */
object PinyinConverter {

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    /** "你好世界" → "nǐ hǎo shì jiè" */
    fun toPinyin(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (isHanzi(ch)) {
                val py = pinyinOf(ch)
                if (py != null) {
                    if (sb.isNotEmpty() && !sb.endsWith(' ')) sb.append(' ')
                    sb.append(py)
                    continue
                }
            }
            // No hanzi (o hanzi sin diccionario): se copia tal cual.
            sb.append(ch)
        }
        return fixThirdTone(sb.toString()).replace(WHITESPACE, " ").trim()
    }

    /**
     * pinyin4j genera los tonos terceros con breve (ă ĕ ĭ ŏ ŭ) en lugar del
     * carón correcto del pinyin (ǎ ě ǐ ǒ ǔ). Se normaliza aquí.
     */
    private fun fixThirdTone(text: String): String {
        var result = text
        for ((wrong, right) in THIRD_TONE_FIXES) {
            result = result.replace(wrong, right)
        }
        return result
    }

    private fun pinyinOf(ch: Char): String? = try {
        PinyinHelper.toHanyuPinyinStringArray(ch, format)?.firstOrNull()
    } catch (_: BadHanyuPinyinOutputFormatCombination) {
        null
    }

    private fun isHanzi(c: Char): Boolean = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        -> true
        else -> false
    }

    /**
     * Tono de una sílaba pinyin según su marca diacrítica (1-4), o 5 si es
     * átona/no marcada. Para colorear el pinyin por tonos.
     */
    fun toneOf(syllable: String): Int {
        for (ch in syllable) {
            TONE_OF_MARKED[ch]?.let { return it }
        }
        return 5
    }

    private val TONE_OF_MARKED = mapOf(
        'ā' to 1, 'á' to 2, 'ǎ' to 3, 'à' to 4,
        'ē' to 1, 'é' to 2, 'ě' to 3, 'è' to 4,
        'ī' to 1, 'í' to 2, 'ǐ' to 3, 'ì' to 4,
        'ō' to 1, 'ó' to 2, 'ǒ' to 3, 'ò' to 4,
        'ū' to 1, 'ú' to 2, 'ǔ' to 3, 'ù' to 4,
        'ǖ' to 1, 'ǘ' to 2, 'ǚ' to 3, 'ǜ' to 4,
    )

    private val WHITESPACE = Regex(" {2,}")

    private val THIRD_TONE_FIXES = arrayOf(
        '\u0103' to '\u01CE', // ă → ǎ (a)
        '\u0115' to '\u011B', // ĕ → ě (e)
        '\u012D' to '\u01D0', // ĭ → ǐ (i)
        '\u014F' to '\u01D2', // ŏ → ǒ (o)
        '\u016D' to '\u01D4', // ŭ → ǔ (u)
    )
}
