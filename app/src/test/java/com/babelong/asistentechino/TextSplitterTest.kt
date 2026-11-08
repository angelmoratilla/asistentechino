package com.babelong.asistentechino

import com.babelong.asistentechino.pinyin.PinyinConverter
import com.babelong.asistentechino.ui.TextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSplitterTest {

    @Test
    fun `las líneas cortas no se parten`() {
        assertEquals(listOf("你好世界"), TextSplitter.split("你好世界"))
    }

    @Test
    fun `una línea larga se parte en fragmentos de tamaño acotado`() {
        val long = "这是一个非常非常非常非常非常长的句子用来测试分割功能是否正常工作每一段都不应该太长让人阅读困难"
        val parts = TextSplitter.split(long, maxLen = 34)
        assertEquals(long, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 34 })
        assertTrue(parts.size > 1)
    }

    @Test
    fun `prefiere cortar por signos de puntuación chinos`() {
        val text = "今天我们学习语法，然后做练习，最后写一段作文，明天交给我检查修改"
        val parts = TextSplitter.split(text, maxLen = 20)
        assertEquals(text, parts.joinToString(""))
        // Los cortes naturales caen tras la coma/punto, no a mitad de palabra.
        assertTrue(parts.dropLast(1).all { it.last() in "，。！？；、：" || it.length == 20 })
    }
}

class PinyinToneTest {

    @Test
    fun `detecta el tono por la marca diacrítica`() {
        assertEquals(1, PinyinConverter.toneOf("zhōng"))
        assertEquals(2, PinyinConverter.toneOf("hái"))
        assertEquals(3, PinyinConverter.toneOf("nǐ"))
        assertEquals(4, PinyinConverter.toneOf("shì"))
        assertEquals(5, PinyinConverter.toneOf("ma"))
        assertEquals(5, PinyinConverter.toneOf("ABC"))
    }
}
