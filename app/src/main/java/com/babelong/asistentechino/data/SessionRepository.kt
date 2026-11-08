package com.babelong.asistentechino.data

import android.content.Context
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistencia de sesiones como ficheros .txt en
 * <almacenamiento-externo-app>/clases/. Sin base de datos: así el export y el
 * "compartir" son triviales y el usuario puede leer los ficheros directamente.
 *
 * Formato de un fichero de sesión:
 * ```
 * # AsistenteChino v1
 * # inicio=2026-11-06 18:30:12
 * # idioma=zh-CN
 * 00:12	老师很好
 * 01:05	我们开始上课
 * # fin=2026-11-06 19:30:00
 * ```
 */
class SessionRepository(context: Context) {

    private val dir: File =
        File(context.getExternalFilesDir(null), "clases").apply { mkdirs() }

    /** Crea el fichero de una sesión nueva (con marca horaria única) y escribe su cabecera. */
    fun newSession(language: String): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        var candidate = File(dir, "$stamp.txt")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stamp}_$i.txt")
            i++
        }
        candidate.writeText(
            buildString {
                appendLine("# AsistenteChino v1")
                appendLine("# inicio=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("# idioma=$language")
            },
        )
        return candidate
    }

    /** Añade una línea al fichero de la sesión en curso (autoguardado). */
    fun appendLine(file: File, line: TranscriptLine) {
        file.appendText("${line.clockLabel()}\t${line.text}\n")
    }

    /** Cierra la sesión escribiendo la hora de fin. */
    fun finalize(file: File) {
        file.appendText("# fin=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
    }

    /** Todas las sesiones guardadas, la más reciente primero. */
    fun listSessions(): List<Session> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { load(it) }
            .orEmpty()

    fun load(id: String): Session = load(File(dir, "$id.txt"))

    fun fileOf(id: String): File = File(dir, "$id.txt")

    /** Audio de la clase grabado junto a la transcripción (m4a), si existe. */
    fun audioFileOf(id: String): File = File(dir, "$id.m4a")

    fun delete(id: String) {
        File(dir, "$id.txt").delete()
        audioFileOf(id).delete()
    }

    /**
     * Borra el AUDIO de las sesiones más antiguas de [keepDays] días
     * (la transcripción .txt se conserva siempre). 0 = no borrar nunca.
     */
    fun cleanOldAudio(keepDays: Int) {
        if (keepDays <= 0) return
        val cutoff = System.currentTimeMillis() - keepDays.toLong() * 24 * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { audioFileOf(it.nameWithoutExtension).delete() }
    }

    /** Cambia (o añade) el título de una sesión en su cabecera. */
    fun setTitle(id: String, title: String) {
        val file = File(dir, "$id.txt")
        if (!file.exists()) return
        val clean = title.trim().take(80)
        val lines = file.readLines().toMutableList()
        val idx = lines.indexOfFirst { it.startsWith("# titulo=") }
        if (clean.isEmpty()) {
            if (idx >= 0) lines.removeAt(idx)
        } else if (idx >= 0) {
            lines[idx] = "# titulo=$clean"
        } else {
            lines.add(1, "# titulo=$clean")
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }

    /** Texto completo listo para copiar/exportar; las importantes llevan prefijo ★. */
    fun fullText(session: Session): String =
        session.lines.joinToString("\n") {
            (if (it.important) "${TranscriptLine.IMPORTANT_MARKER} " else "") +
                "${it.clockLabel()}  ${it.text}"
        }

    /**
     * Marca/desmarca una línea por su offset y texto, reescribiendo el fichero
     * con cabecera y pie intactos (los ficheros son pequeños: reescribir es
     * trivial y evita ficheros de marcadores paralelos).
     */
    fun setImportant(file: File, offsetMs: Long, text: String, important: Boolean) {
        if (!file.exists()) return
        file.writeText(applyImportantMark(file.readText(), offsetMs, text, important))
    }

    private fun load(file: File): Session {
        val parsed = parse(file.readText())
        val startedAt = parsed.startedAt?.let {
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(it)?.time
            } catch (_: ParseException) {
                null
            }
        } ?: file.lastModified()
        return Session(
            id = file.nameWithoutExtension,
            startedAtMs = startedAt,
            language = parsed.language,
            title = parsed.title,
            lines = parsed.lines,
            audioFile = audioFileOf(file.nameWithoutExtension).takeIf { it.exists() },
        )
    }

    data class ParsedSession(
        val startedAt: String?,
        val language: String,
        val title: String?,
        val lines: List<TranscriptLine>,
    )

    companion object {
        /**
         * Marca/desmarca en el texto del fichero la línea cuyo (offset, texto)
         * coincide, sin tocar cabeceras ni pie. Función pura, testeable.
         */
        fun applyImportantMark(
            fileText: String,
            offsetMs: Long,
            text: String,
            important: Boolean,
        ): String {
            val endsWithNewline = fileText.endsWith("\n")
            // OJO: lineSequence() emite una cadena vacía tras el \n final; se
            // recorta para no duplicar saltos de línea al reescribir.
            val out = fileText.trimEnd('\n').lineSequence().map { raw ->
                val line = raw.trimEnd()
                if (line.isEmpty() || line.startsWith("#")) return@map raw
                val tab = line.indexOf('\t')
                if (tab <= 0) return@map raw
                val clock = line.substring(0, tab)
                var body = line.substring(tab + 1)
                val wasImportant = body.startsWith(TranscriptLine.IMPORTANT_MARKER)
                if (wasImportant) body = body.removePrefix(TranscriptLine.IMPORTANT_MARKER)
                // El reloj del fichero es de SEGUNDOS; el offset en memoria puede
                // tener milisegundos (p. ej. 13.847 ms ⇔ "00:13" = 13.000 ms).
                // Se compara a precisión de segundo o nunca cuadraría en vivo.
                if (TranscriptLine.parseClock(clock) == offsetMs / 1000 * 1000 && body == text) {
                    val marker = if (important) TranscriptLine.IMPORTANT_MARKER else ""
                    "${clock}\t$marker$body"
                } else {
                    raw
                }
            }.joinToString("\n")
            return if (endsWithNewline) "$out\n" else out
        }

        /** Parser puro (testeable en JVM sin Android). */
        fun parse(text: String): ParsedSession {
            var startedAt: String? = null
            var language = "zh-CN"
            var title: String? = null
            val lines = mutableListOf<TranscriptLine>()
            text.lineSequence().forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.startsWith("# inicio=") -> startedAt = line.removePrefix("# inicio=")
                    line.startsWith("# idioma=") -> language = line.removePrefix("# idioma=")
                    line.startsWith("# titulo=") -> title = line.removePrefix("# titulo=").trim().ifEmpty { null }
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> {
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            var body = line.substring(tab + 1).trim()
                            if (body.isNotEmpty()) {
                                var important = false
                                if (body.startsWith(TranscriptLine.IMPORTANT_MARKER)) {
                                    important = true
                                    body = body.removePrefix(TranscriptLine.IMPORTANT_MARKER).trim()
                                }
                                lines += TranscriptLine(
                                    TranscriptLine.parseClock(line.substring(0, tab)),
                                    body,
                                    important,
                                )
                            }
                        } else if (line.isNotBlank()) {
                            lines += TranscriptLine(0L, line.trim())
                        }
                    }
                }
            }
            return ParsedSession(startedAt, language, title, lines)
        }
    }
}
