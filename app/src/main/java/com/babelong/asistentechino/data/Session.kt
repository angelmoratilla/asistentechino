package com.babelong.asistentechino.data

import java.io.File

/** Una línea confirmada de la transcripción, con su instante dentro de la clase. */
data class TranscriptLine(
    val offsetMs: Long,
    val text: String,
    /** Marcada como importante por el usuario (se guarda con prefijo ★ en el .txt). */
    val important: Boolean = false,
) {
    /** Etiqueta de reloj m:ss; los minutos no se acotan (75:03 = 1h 15min 3s). */
    fun clockLabel(): String {
        val totalSec = offsetMs / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    companion object {
        /** Prefijo que marca una línea como importante en el fichero de sesión. */
        const val IMPORTANT_MARKER = "★"

        /** "01:05" → 65_000 ms; admite minutos por encima de 60 ("75:03"). */
        fun parseClock(label: String): Long {
            var seconds = 0L
            for (part in label.trim().split(":")) {
                seconds = seconds * 60 + (part.toLongOrNull() ?: 0L)
            }
            return seconds * 1000
        }
    }
}

/** Una clase transcrita y guardada. */
data class Session(
    /** Identificador = nombre de fichero sin extensión, p. ej. "2026-11-06_1830". */
    val id: String,
    val startedAtMs: Long,
    val language: String,
    val lines: List<TranscriptLine>,
    /** Título opcional puesto a mano. */
    val title: String? = null,
    /** Fichero de audio de la clase (m4a) si se grabó. */
    val audioFile: File? = null,
) {
    val durationMs: Long get() = lines.lastOrNull()?.offsetMs ?: 0L
    val charCount: Int get() = lines.sumOf { it.text.length }
    val preview: String get() = lines.firstOrNull()?.text.orEmpty()
}
