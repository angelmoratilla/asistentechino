package com.babelong.asistentechino.speech

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Fábrica de motores de reconocimiento.
 *
 * v1 solo incluye el motor nativo de Google; el diseño deja preparado el punto
 * de extensión para v2. Basta con:
 *  1. Implementar [SpeechEngine] en una clase nueva (p. ej. `VoskEngine` 100 %
 *     offline, o `CloudSpeechEngine` contra Google Cloud / Azure).
 *  2. Añadir su valor a [EngineId] y su rama al `when` de [create].
 * La UI y el ViewModel no necesitan ningún cambio.
 */
object SpeechEngineFactory {

    enum class EngineId(val label: String, val prefValue: String) {
        /** Reconocedor nativo de Android vía servicios de Google: gratis, en línea. */
        GOOGLE_ANDROID("Google (en línea, gratis)", "online"),

        /** Reconocedor en el dispositivo (API 31+): sin red, sin pitidos, modelo offline. */
        GOOGLE_ON_DEVICE("En el dispositivo (sin red)", "ondevice"),

        /** Fase 1: sherpa-onnx 100 % offline, la app dueña del micrófono. */
        OFFLINE_SHERPA("Offline sherpa-onnx (chino)", "offline"),
    }

    fun fromPrefValue(prefValue: String): EngineId =
        EngineId.entries.firstOrNull { it.prefValue == prefValue } ?: EngineId.GOOGLE_ANDROID

    fun create(id: EngineId, context: Context, scope: CoroutineScope): SpeechEngine = when (id) {
        EngineId.GOOGLE_ANDROID -> GoogleSpeechEngine(context, scope, onDevice = false)
        EngineId.GOOGLE_ON_DEVICE -> GoogleSpeechEngine(context, scope, onDevice = true)
        EngineId.OFFLINE_SHERPA -> OfflineSpeechEngine(context, scope)
    }
}
