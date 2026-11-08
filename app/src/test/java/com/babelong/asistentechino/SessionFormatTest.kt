package com.babelong.asistentechino

import com.babelong.asistentechino.data.SessionRepository
import com.babelong.asistentechino.data.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFormatTest {

    @Test
    fun `la etiqueta de reloj se formatea y se parsea`() {
        assertEquals("01:05", TranscriptLine(65_000, "").clockLabel())
        assertEquals(65_000L, TranscriptLine.parseClock("01:05"))
        // Los minutos no se acotan: 75:03 = 1 h 15 min 3 s
        assertEquals(4_503_000L, TranscriptLine.parseClock("75:03"))
        assertEquals("75:03", TranscriptLine(4_503_000, "").clockLabel())
    }

    @Test
    fun `parsea un fichero de sesión completo`() {
        val text = """
            # AsistenteChino v1
            # inicio=2026-11-06 18:30:12
            # idioma=zh-CN
            00:12	老师很好
            01:05	我们开始上课
            # fin=2026-11-06 19:30:00
        """.trimIndent()

        val parsed = SessionRepository.parse(text)

        assertEquals("zh-CN", parsed.language)
        assertEquals("2026-11-06 18:30:12", parsed.startedAt)
        assertEquals(2, parsed.lines.size)
        assertEquals("老师很好", parsed.lines[0].text)
        assertEquals(12_000L, parsed.lines[0].offsetMs)
        assertEquals("我们开始上课", parsed.lines[1].text)
        assertEquals(65_000L, parsed.lines[1].offsetMs)
    }

    @Test
    fun `un fichero sin cabecera usa valores por defecto`() {
        val parsed = SessionRepository.parse("00:02\t你好")

        assertEquals("zh-CN", parsed.language)
        assertNull(parsed.startedAt)
        assertEquals(1, parsed.lines.size)
        assertEquals("你好", parsed.lines[0].text)
    }

    @Test
    fun `las líneas vacías y los comentarios se ignoran`() {
        val parsed = SessionRepository.parse("\n# comentario\n\n")

        assertEquals(0, parsed.lines.size)
    }

    @Test
    fun `el título opcional se parsea`() {
        val parsed = SessionRepository.parse(
            "# AsistenteChino v1\n# titulo=Lección 5 · 食物\n# idioma=zh-CN\n00:02\t你好",
        )
        assertEquals("Lección 5 · 食物", parsed.title)

        val sinTitulo = SessionRepository.parse("00:02\t你好")
        assertNull(sinTitulo.title)
    }

    @Test
    fun `las líneas marcadas con estrella se parsean como importantes`() {
        val parsed = SessionRepository.parse("00:12\t★老师很好\n00:30\t我们开始上课")
        assertTrue(parsed.lines[0].important)
        assertEquals("老师很好", parsed.lines[0].text)
        assertEquals(false, parsed.lines[1].important)
    }

    @Test
    fun `applyImportantMark marca solo la línea pedida`() {
        val original = """
            # AsistenteChino v1
            # inicio=2026-11-07 18:30:12
            # idioma=zh-CN
            00:12	老师很好
            01:05	我们开始上课
            # fin=2026-11-07 19:30:00
        """.trimIndent() + "\n"

        val marcado = SessionRepository.applyImportantMark(
            original,
            offsetMs = 12_000,
            text = "老师很好",
            important = true,
        )

        // Cabecera y pie intactos
        assertTrue(marcado.contains("# inicio=2026-11-07 18:30:12"))
        assertTrue(marcado.contains("# fin=2026-11-07 19:30:00"))
        assertTrue(marcado.contains("00:12\t★老师很好"))
        assertTrue(marcado.contains("01:05\t我们开始上课"))

        // Y el round-trip se parsea bien
        val parsed = SessionRepository.parse(marcado)
        assertTrue(parsed.lines[0].important)
        assertEquals("老师很好", parsed.lines[0].text)
        assertEquals(false, parsed.lines[1].important)
    }

    @Test
    fun `applyImportantMark desmarca y es idempotente`() {
        val original = "# idioma=zh-CN\n00:12\t★老师很好\n"
        val desmarcado = SessionRepository.applyImportantMark(
            original, 12_000, "老师很好", important = false,
        )
        assertTrue(desmarcado.contains("00:12\t老师很好"))

        val desmarcadoOtraVez = SessionRepository.applyImportantMark(
            desmarcado, 12_000, "老师很好", important = false,
        )
        assertEquals(desmarcado, desmarcadoOtraVez)
    }

    @Test
    fun `applyImportantMark no toca otras líneas ni el formato del fichero`() {
        val original = "00:12\t你好\n01:00\t再见\n"
        val resultado = SessionRepository.applyImportantMark(
            original, 60_000, "再见", important = true,
        )
        assertEquals("00:12\t你好\n01:00\t★再见\n", resultado)
    }

    @Test
    fun `applyImportantMark cuadra con offsets de precisión de milisegundo`() {
        // En vivo el offset en memoria tiene ms (13.847) pero el fichero dice 00:13
        val original = "# idioma=zh-CN\n00:13\t大家好\n"
        val marcado = SessionRepository.applyImportantMark(
            original, offsetMs = 13_847, text = "大家好", important = true,
        )
        assertTrue(marcado.contains("00:13\t★大家好"))
    }
}
