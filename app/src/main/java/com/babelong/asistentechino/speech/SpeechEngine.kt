package com.babelong.asistentechino.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Estado del motor de reconocimiento. */
sealed interface EngineState {
    data object Idle : EngineState
    data object Listening : EngineState

    /** Error fatal: el motor se ha detenido y no reintenta solo (p. ej. sin permiso). */
    data class Error(val code: Int, val message: String) : EngineState
}

/** Un fragmento de transcripción ya confirmado, con su instante (reloj del sistema). */
data class FinalSegment(val text: String, val timestampMs: Long)

/** Parámetros de una sesión de reconocimiento. */
data class RecognitionConfig(
    /** Etiqueta BCP-47: "zh-CN" (simplificado) o "zh-TW" (tradicional). */
    val languageTag: String = "zh-CN",
    /** Silencia el pitido que el servicio de Google emite al empezar a escuchar. */
    val muteBeep: Boolean = true,
)

/**
 * Abstracción del motor de reconocimiento de voz.
 *
 * v1 usa [GoogleSpeechEngine] (SpeechRecognizer nativo de Android, gratuito).
 * El diseño está preparado para añadir en v2 otros motores — p. ej. Vosk (offline)
 * o una API en la nube — implementando esta interfaz y registrándolos en
 * [SpeechEngineFactory], sin tocar UI ni ViewModel.
 */
interface SpeechEngine {
    /** Hipótesis parcial en curso: texto que aún puede cambiar. */
    val partial: StateFlow<String>

    /** Fragmentos finales confirmados, en orden de llegada. */
    val finals: Flow<FinalSegment>

    /** Estado del motor. */
    val state: StateFlow<EngineState>

    /** Número de reinicios internos (por resultado final o error) de la sesión actual. */
    val restartCount: StateFlow<Int>

    /** Empieza a escuchar. No hace nada si ya está escuchando. */
    fun start(config: RecognitionConfig)

    /** Deja de escuchar y libera el micrófono. Idempotente. */
    fun stop()
}
