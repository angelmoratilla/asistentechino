package com.babelong.asistentechino

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.babelong.asistentechino.data.TranscriptLine
import com.babelong.asistentechino.speech.EngineState
import com.babelong.asistentechino.speech.GoogleSpeechEngine
import com.babelong.asistentechino.speech.RecognitionConfig
import com.babelong.asistentechino.speech.SpeechEngine
import com.babelong.asistentechino.speech.SpeechEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Cerebro de la sesión de clase, independiente de la Activity.
 *
 * Vive en el proceso (singleton) y lo comparten el ViewModel y el servicio en
 * primer plano: así la clase sigue transcribiéndose y grabándose aunque la
 * pantalla se apague, la app pase a segundo plano o la UI se destruya. Las
 * líneas se autoguardan en el fichero de sesión a medida que llegan.
 *
 * Todas las llamadas desde el hilo principal.
 */
object ClassController {

    private const val TAG = "ClassController"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sessions get() = AppGraph.sessions

    /** Motores disponibles (se crean al primer uso). Solo uno está activo por sesión. */
    private val engines = mutableMapOf<String, SpeechEngine>()

    private fun engineFor(prefValue: String): SpeechEngine = engines.getOrPut(prefValue) {
        SpeechEngineFactory.create(
            SpeechEngineFactory.fromPrefValue(prefValue),
            appContext,
            scope,
        )
    }

    @Volatile
    private var activeEngineRef: SpeechEngine? = null

    @Volatile
    private var activeEngineId: String = "online"

    /** Motor en uso durante la sesión actual (null si no hay sesión). */
    fun activeEngine(): SpeechEngine? = activeEngineRef

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _lines = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val lines: StateFlow<List<TranscriptLine>> = _lines.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Aviso no fatal para el usuario (p. ej. grabación soltada por conflicto de micro). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // Solo el motor activo emite; el merge permite cambiar de motor entre sesiones.
    // perezosos: engineFor necesita appContext, que se fija en init().
    val state: Flow<EngineState> by lazy {
        merge(engineFor("online").state, engineFor("ondevice").state, engineFor("offline").state)
    }
    val restartCount: Flow<Int> by lazy {
        merge(engineFor("online").restartCount, engineFor("ondevice").restartCount, engineFor("offline").restartCount)
    }

    private var sessionFile: File? = null
    private var sessionId: String? = null
    private var startedAtMs = 0L
    private var recorder: MediaRecorder? = null
    private var timerJob: Job? = null
    private var collecting = false
    private var currentLanguage = "zh-CN"
    private var currentMuteBeep = true
    private var lastEngineActivityMs = 0L
    private var starvationJob: Job? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // Si un proceso anterior murió a mitad de clase, el silencio anti-pitidos
        // pudo quedar pegado al sistema: lo deshacemos al arrancar.
        GoogleSpeechEngine.restoreLatchedMute(appContext)
    }

    fun currentSessionId(): String? = sessionId

    /** Marca/desmarca la última frase confirmada (botón ★ de la pantalla en vivo). */
    fun toggleImportantLast() {
        val last = _lines.value.lastOrNull() ?: return
        setImportant(last.offsetMs, last.text, !last.important)
    }

    /** Marca/desmarca una línea concreta y lo persiste en el fichero de sesión. */
    fun setImportant(offsetMs: Long, text: String, important: Boolean) {
        _lines.update { list ->
            list.map {
                if (it.offsetMs == offsetMs && it.text == text) {
                    it.copy(important = important)
                } else {
                    it
                }
            }
        }
        sessionFile?.let { file -> sessions.setImportant(file, offsetMs, text, important) }
    }

    /**
     * Empieza una clase nueva (fichero de transcripción + audio si procede).
     * Devuelve true si la grabación de audio se inició (o no se pidió).
     */
    fun start(language: String, muteBeep: Boolean, recordAudio: Boolean, engineId: String): Boolean {
        if (_isListening.value) return true
        val file = sessions.newSession(language)
        startedAtMs = System.currentTimeMillis()
        sessionId = file.nameWithoutExtension
        sessionFile = file
        currentLanguage = language
        currentMuteBeep = muteBeep
        _lines.value = emptyList()
        _partial.value = ""
        _elapsedMs.value = 0L
        _notice.value = null
        _isListening.value = true
        lastEngineActivityMs = System.currentTimeMillis()

        // Orden probado en Xiaomi/MIUI: el reconocedor toma el micro PRIMERO y
        // la grabadora después (así el reconocedor no se queda sin audio).
        // Excepción: el motor offline es el ÚNICO dueño del micro y graba él
        // mismo el mismo PCM que reconoce (config.recordToFile).
        val engine = engineFor(engineId)
        activeEngineRef = engine
        activeEngineId = engineId
        val recordFile = if (recordAudio && engineId == "offline") {
            sessions.audioFileOf(file.nameWithoutExtension)
        } else {
            null
        }
        engine.start(RecognitionConfig(languageTag = language, muteBeep = muteBeep, recordToFile = recordFile))
        val recorded = when {
            engineId == "offline" -> recordAudio
            recordAudio -> startRecorder()
            else -> true
        }
        _recording.value = recordAudio && recorded

        observeEngineIfNeeded()
        startTimer()
        startStarvationMonitor()
        return recorded
    }

    fun stop() {
        if (!_isListening.value) return
        activeEngineRef?.stop()
        stopRecorder()
        sessionFile?.let { sessions.finalize(it) }
        sessionFile = null
        sessionId = null
        timerJob?.cancel()
        starvationJob?.cancel()
        activeEngineRef = null
        _isListening.value = false
        _partial.value = ""
        _recording.value = false
    }

    /**
     * Reanuda la sesión actual con OTRO motor (misma grabación y fichero):
     * para reversiones automáticas cuando un motor no funciona con el idioma.
     */
    fun restartWithEngine(engineId: String) {
        if (!_isListening.value) return
        activeEngineRef?.stop()
        val engine = engineFor(engineId)
        activeEngineRef = engine
        engine.start(RecognitionConfig(languageTag = currentLanguage, muteBeep = currentMuteBeep))
        lastEngineActivityMs = System.currentTimeMillis()
    }

    private fun observeEngineIfNeeded() {
        if (collecting) return
        collecting = true
        scope.launch {
            merge(engineFor("online").finals, engineFor("ondevice").finals, engineFor("offline").finals)
                .collect { segment ->
                lastEngineActivityMs = System.currentTimeMillis()
                // El offset respecto al inicio de la clase lo calcula el
                // controlador: así los reinicios o cambios de motor no
                // descolocan la línea temporal (ni el sincronizado con audio).
                val offset = (segment.timestampMs - startedAtMs).coerceAtLeast(0L)
                val line = TranscriptLine(offsetMs = offset, text = segment.text)
                _lines.update { it + line }
                sessionFile?.let { file -> sessions.appendLine(file, line) }
            }
        }
        scope.launch {
            merge(engineFor("online").partial, engineFor("ondevice").partial, engineFor("offline").partial)
                .collect { text ->
                if (text.isNotEmpty()) lastEngineActivityMs = System.currentTimeMillis()
                _partial.value = text
            }
        }
        scope.launch {
            state.collect {
                if (it is EngineState.Listening) lastEngineActivityMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * Monitor de inanición: en silencio normal el servicio responde con
     * NO_MATCH en unos segundos, así que si llevamos 45 s sin NINGÚN callback
     * es que el servicio se ha quedado sin micro. En algunos Xiaomi (MIUI)
     * pasa cuando nuestra grabación de audio le roba el micrófono al
     * reconocedor de Google: en ese caso soltamos la grabación, recreamos el
     * reconocedor y avisamos — la transcripción nunca se queda muerta.
     */
    private fun startStarvationMonitor() {
        starvationJob?.cancel()
        starvationJob = scope.launch {
            while (_isListening.value) {
                delay(15_000)
                // El monitor es un parche del motor de Google (que puede quedarse
                // sin micro en MIUI): el motor offline nunca se queda mudo porque
                // él mismo dueño del micro, así que no aplica.
                if (activeEngineId == "offline") continue
                if (!_isListening.value) break
                val idleMs = System.currentTimeMillis() - lastEngineActivityMs
                if (idleMs <= STARVATION_TIMEOUT_MS) continue
                Log.w(TAG, "sin actividad del reconocedor en ${idleMs}ms")
                if (_recording.value) {
                    Log.w(TAG, "soltando la grabación para devolver el micro al reconocedor")
                    stopRecorder()
                    _recording.value = false
                    _notice.value =
                        "He dejado de grabar el audio: este móvil no permite grabar y " +
                            "transcribir a la vez con el servicio de Google. La transcripción sigue."
                }
                val engine = activeEngineRef ?: break
                engine.stop()
                delay(400)
                if (_isListening.value) {
                    engine.start(RecognitionConfig(languageTag = currentLanguage, muteBeep = currentMuteBeep))
                    lastEngineActivityMs = System.currentTimeMillis()
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_isListening.value) {
                delay(500)
                _elapsedMs.value = System.currentTimeMillis() - startedAtMs
            }
        }
    }

    // ----- Grabación de audio (AAC 96 kbps mono, ~43 MB/h) -----

    private fun startRecorder(): Boolean = try {
        val file = sessionFile ?: return false
        val audio = sessions.audioFileOf(file.nameWithoutExtension)
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder?.apply {
            // VOICE_RECOGNITION (y no MIC): misma fuente que usa el reconocedor
            // de Google, sin procesado agresivo del fabricante y capaz de
            // compartir el micrófono con él en MIUI (MIC se lo queda en exclusiva).
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48_000)
            setAudioEncodingBitRate(96_000)
            setAudioChannels(1)
            setOutputFile(audio.absolutePath)
            prepare()
            start()
        }
        Log.d(TAG, "grabación iniciada: ${audio.name}")
        true
    } catch (e: Exception) {
        Log.w(TAG, "no se pudo iniciar la grabación: ${e.message}")
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        false
    }

    private fun stopRecorder() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }

    /** Sin ningún callback del servicio durante esto = servicio sin micro. */
    private const val STARVATION_TIMEOUT_MS = 45_000L
}
