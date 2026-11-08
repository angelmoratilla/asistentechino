package com.babelong.asistentechino.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Motor basado en el SpeechRecognizer nativo de Android (servicio de Google).
 *
 * El servicio nativo se detiene tras cada resultado o tras unos segundos de
 * silencio, así que esta clase lo reinicia automáticamente (con reintento y
 * backoff) para conseguir escucha continua durante toda la clase.
 *
 * Todas las llamadas deben hacerse desde el hilo principal.
 */
class GoogleSpeechEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    /**
     * true = reconocimiento EN EL DISPOSITIVO (createOnDeviceSpeechRecognizer,
     * API 31+): sin pitidos, sin conexión, pero requiere el modelo offline de
     * Google descargado y algo menos de precisión.
     */
    private val onDevice: Boolean = false,
) : SpeechEngine {

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _restartCount = MutableStateFlow(0)
    override val restartCount: StateFlow<Int> = _restartCount.asStateFlow()

    private val _finals = MutableSharedFlow<FinalSegment>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val finals: Flow<FinalSegment> = _finals

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val prefs by lazy { context.getSharedPreferences("ajustes", Context.MODE_PRIVATE) }

    private var recognizer: SpeechRecognizer? = null
    private var config = RecognitionConfig()
    private var isRunning = false
    private var consecutiveFailures = 0

    /**
     * Generación del reconocedor activo. Cada createAndStart() la incrementa y
     * el listener asociado ignora cualquier callback de generaciones antiguas:
     * así los eventos "fantasma" de un reconocedor destruido (p. ej. al cambiar
     * de idioma) no contaminan la sesión nueva.
     */
    private var generation = 0

    /** Flujos de audio que hemos silenciado nosotros (se restauran al parar). */
    private val mutedStreams = mutableListOf<Int>()

    /**
     * Vigilante: si el servicio de reconocimiento no da señal de vida
     * (onReadyForSpeech) en este plazo tras startListening, se considera
     * atascado y se recrea el reconocedor. Sin esto, un servicio colgado
     * dejaría la app escuchando "en silencio" para siempre.
     */
    private val watchdogRunnable = Runnable {
        if (!isRunning) return@Runnable
        Log.w(TAG, "watchdog: sin señal de vida del servicio en ${WATCHDOG_TIMEOUT_MS}ms; recreando")
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            failFatal(
                ERROR_NO_RESPONSE,
                "El servicio de reconocimiento no responde. Reinténtalo; si persiste, " +
                    "reinicia el móvil o actualiza la app «Speech Services de Google».",
            )
            return@Runnable
        }
        scheduleRestart(recreate = true, delayMs = backoffMs())
    }

    private fun armWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
    }

    private fun makeListener(gen: Int): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (gen != generation || !isRunning) return
            handler.removeCallbacks(watchdogRunnable)
            consecutiveFailures = 0
            _state.value = EngineState.Listening
            Log.d(TAG, "onReadyForSpeech gen=$gen")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            Log.d(TAG, "onError code=$error running=$isRunning gen=$gen")
            if (gen != generation || !isRunning) return
            // Sin permiso no hay nada que reintentar: error fatal.
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                failFatal(error, "Falta el permiso de micrófono. Concédelo en Ajustes y vuelve a intentarlo.")
                return
            }
            consecutiveFailures++
            val serverish = isServerError(error)
            val maxFailures = if (serverish) MAX_SERVER_FAILURES else MAX_CONSECUTIVE_FAILURES
            // Demasiados fallos seguidos sin llegar a escuchar nada: parar y avisar.
            if (consecutiveFailures >= maxFailures) {
                failFatal(
                    error,
                    "El reconocimiento se detuvo tras varios intentos fallidos: ${errorMessage(error)}",
                )
                return
            }
            // Errores de servidor/conexión: recrear el reconocedor y esperar más
            // (un reintento inmediato suele volver a fallar y puede agotar el servicio).
            val recreate = serverish ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT
            val delay = if (serverish) serverBackoffMs() else backoffMs()
            scheduleRestart(recreate, delay)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty().trim()
            Log.d(TAG, "onResults len=${text.length} '$text'")
            if (gen != generation || !isRunning) return
            if (text.isNotEmpty()) {
                _finals.tryEmit(FinalSegment(text, System.currentTimeMillis()))
            }
            _partial.value = ""
            scheduleRestart(recreate = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (gen != generation || !isRunning) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            // El servicio de Google intercala parciales VACÍOS entre hipótesis
            // (comprobado en Xiaomi 15 Ultra / zh-CN). Si los aplicáramos, el
            // texto en pantalla parpadearía hasta vaciarse; conservamos el
            // último parcial no vacío hasta que llegue uno nuevo o el final.
            if (text.isNotEmpty()) _partial.value = text
            Log.d(TAG, "onPartial len=${text.length} '${text.take(30)}'")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun start(config: RecognitionConfig) {
        if (isRunning) return
        this.config = config
        if (onDevice) {
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                _state.value = EngineState.Error(
                    ERROR_NO_SERVICE,
                    "El reconocimiento en el dispositivo no está disponible en este móvil " +
                        "(requiere Android 12+ y el modelo offline de Google).",
                )
                return
            }
        } else if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = EngineState.Error(
                ERROR_NO_SERVICE,
                "Este dispositivo no tiene servicio de reconocimiento de voz (normalmente lo aporta la app de Google).",
            )
            return
        }
        isRunning = true
        consecutiveFailures = 0
        _restartCount.value = 0
        _state.value = EngineState.Idle
        muteForChimes()
        createAndStart()
    }

    override fun stop() {
        if (!isRunning && recognizer == null) return
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        restoreVolume()
        recognizer?.destroy()
        recognizer = null
        _partial.value = ""
        _state.value = EngineState.Idle
    }

    private fun createAndStart() {
        if (!isRunning) return
        try {
            recognizer?.destroy()
            val gen = ++generation
            val sr = if (onDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = sr
            sr.setRecognitionListener(makeListener(gen))
            sr.startListening(buildIntent())
            muteForChimes()
            armWatchdog()
            Log.d(TAG, "startListening lang=${config.languageTag} gen=$gen onDevice=$onDevice")
        } catch (e: SecurityException) {
            failFatal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                "Falta el permiso de micrófono: ${e.message ?: "concédelo en Ajustes"}",
            )
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun scheduleRestart(recreate: Boolean, delayMs: Long = 250L) {
        if (!isRunning) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            _restartCount.value += 1
            if (recreate || recognizer == null) {
                createAndStart()
            } else {
                recognizer?.startListening(buildIntent())
                muteForChimes()
                armWatchdog()
            }
        }, delayMs)
    }

    private fun backoffMs(): Long = minOf(300L * consecutiveFailures, 2000L)

    /** Backoff más largo para errores de servidor/conexión. */
    private fun serverBackoffMs(): Long = minOf(2000L * consecutiveFailures, 8000L)

    /** Errores que indican problema de servidor/conexión (conviene esperar y reconectar). */
    private fun isServerError(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> true
        else -> false
    }

    private fun failFatal(code: Int, message: String) {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        restoreVolume()
        recognizer?.destroy()
        recognizer = null
        _partial.value = ""
        _state.value = EngineState.Error(code, message)
    }

    /**
     * Silencia el "pitido" del servicio de Google mientras la sesión está activa.
     *
     * El chime se emite en cada startListening (o sea, en cada reinicio) y según
     * el dispositivo va por STREAM_MUSIC o por STREAM_SYSTEM; además puede
     * sonar unos instantes antes/después de onReadyForSpeech. Por eso lo más
     * fiable es silenciar ambos flujos durante toda la sesión y restaurarlos
     * en stop(). Efecto secundario asumido: tu música también queda en
     * silencio mientras la app escucha.
     */
    private fun muteForChimes() {
        // El modo en el dispositivo no emite pitidos: no hace falta silenciar
        // (y así la música del usuario no se ve afectada).
        if (!config.muteBeep || onDevice) return
        val am = audioManager ?: return
        for (stream in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            } catch (_: SecurityException) {
                // Algunas capas (MIUI…) restringen tocar ciertos flujos; sin problema.
            }
        }
        mutedStreams.addAll(listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM))
        // Recordatorio persistente: si el proceso muere con el silencio puesto,
        // la próxima apertura de la app podrá deshacerlo (ver restoreLatchedMute).
        prefs.edit().putBoolean(KEY_STREAMS_MUTED, true).apply()
    }

    private fun restoreVolume() {
        val am = audioManager ?: return
        // Se despertan SIEMPRE ambos flujos aunque no consten en la lista: así
        // un silencio "heredado" de un proceso muerto nunca se queda pegado.
        for (stream in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: SecurityException) {
            }
        }
        mutedStreams.clear()
        prefs.edit().putBoolean(KEY_STREAMS_MUTED, false).apply()
    }

    companion object {
        /** Código interno para "no hay servicio de reconocimiento". */
        const val ERROR_NO_SERVICE = -100

        /** Código interno para "el servicio no da señal de vida" (watchdog). */
        const val ERROR_NO_RESPONSE = -101

        private const val WATCHDOG_TIMEOUT_MS = 12_000L

        private const val MAX_CONSECUTIVE_FAILURES = 6

        /** Los errores de servidor admiten más reintentos (con backoff largo). */
        private const val MAX_SERVER_FAILURES = 10

        private const val TAG = "SpeechEngine"

        private const val KEY_STREAMS_MUTED = "flujos_silenciados_por_sesion"

        /**
         * Autoreparación: si un proceso anterior murió con los flujos de
         * medios/sistema aún silenciados (el silencio anti-pitidos es por toda
         * la sesión), los despierta. Se llama al arrancar la app.
         */
        fun restoreLatchedMute(context: Context) {
            val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_STREAMS_MUTED, false)) return
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (stream in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)) {
                try {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: SecurityException) {
                }
            }
            prefs.edit().putBoolean(KEY_STREAMS_MUTED, false).apply()
        }

        fun errorMessage(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
            SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso de micrófono"
            SpeechRecognizer.ERROR_NETWORK -> "Error de red"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de red agotado"
            SpeechRecognizer.ERROR_NO_MATCH -> "No se ha entendido el audio"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
            SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Sin voz detectada"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Demasiadas peticiones al servicio"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "El servicio de reconocimiento se ha desconectado"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Este idioma no está soportado por el servicio"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Este idioma no está disponible ahora mismo"
            ERROR_NO_SERVICE -> "Sin servicio de reconocimiento"
            ERROR_NO_RESPONSE -> "El servicio de reconocimiento no responde"
            else -> "Error desconocido ($code)"
        }
    }
}
