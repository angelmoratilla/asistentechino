package com.babelong.asistentechino

import com.babelong.asistentechino.pinyin.PinyinConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinConverterTest {

    @Test
    fun `convierte una frase sencilla con marcas tonales`() {
        assertEquals("nǐ hǎo shì jiè", PinyinConverter.toPinyin("你好世界"))
    }

    @Test
    fun `convierte una frase completa`() {
        assertEquals("wǒ shì zhōng guó rén", PinyinConverter.toPinyin("我是中国人"))
    }

    @Test
    fun `respeta el texto que no es chino`() {
        assertEquals("zhōng wén ABC 123", PinyinConverter.toPinyin("中文 ABC 123"))
        assertEquals("ABC", PinyinConverter.toPinyin("ABC"))
        assertEquals("123", PinyinConverter.toPinyin("123"))
    }

    @Test
    fun `maneja texto vacío y cadenas sin hanzi`() {
        assertEquals("", PinyinConverter.toPinyin(""))
        assertEquals("，。！", PinyinConverter.toPinyin("，。！"))
    }

    @Test
    fun `separa los caracteres chinos con espacios`() {
        assertEquals("wǒ ài nǐ", PinyinConverter.toPinyin("我爱你"))
    }
}
