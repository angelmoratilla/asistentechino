package com.babelong.asistentechino.ui

/**
 * Parte una línea de transcripción larga en fragmentos legibles, cortando
 * preferentemente por signos de puntuación chinos/occidentales cerca del
 * límite. Es solo presentación: el fichero guardado no cambia.
 */
object TextSplitter {

    private val BREAKERS = setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':')

    fun split(text: String, maxLen: Int = 34): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= maxLen) {
                out += text.substring(start)
                break
            }
            // Último separador en la segunda mitad del rango permitido.
            var cut = -1
            for (i in start + maxLen downTo start + maxLen / 2) {
                if (i <= text.lastIndex && text[i] in BREAKERS) {
                    cut = i
                    break
                }
            }
            if (cut == -1) cut = start + maxLen - 1
            out += text.substring(start, cut + 1)
            start = cut + 1
        }
        return out.filter { it.isNotBlank() }
    }
}
