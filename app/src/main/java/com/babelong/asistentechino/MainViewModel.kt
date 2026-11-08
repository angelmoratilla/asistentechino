package com.babelong.asistentechino

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.babelong.asistentechino.data.SettingsRepository
import com.babelong.asistentechino.data.TranscriptLine
import com.babelong.asistentechino.speech.EngineState
import com.babelong.asistentechino.speech.RecognitionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de la pantalla "En vivo". */
data class LiveUiState(
    val isListening: Boolean = false,
    val partial: String = "",
    val lines: List<TranscriptLine> = emptyList(),
    val elapsedMs: Long = 0L,
    val restartCount: Int = 0,
    val recording: Boolean = false,
    val fatalMessage: String? = null,
    val infoMessage: String? = null,
    val recognitionAvailable: Boolean = true,
    /** Si el motor "en el dispositivo" está disponible en este móvil (API 31+ y modelo). */
    val onDeviceAvailable: Boolean = false,
    /** Si la app está exenta de la optimización de batería (importante en MIUI). */
    val batteryIgnored: Boolean = true,
    // Espejo de los ajustes, para que la UI reaccione en vivo:
    val pinyinEnabled: Boolean = true,
    val fontSizeSp: Int = 28,
    val keepScreenOn: Boolean = true,
    val muteBeep: Boolean = true,
    val recordAudio: Boolean = true,
    val cleanAudioDays: Int = 30,
    val engineId: String = "online",
    val theme: String = "dark",
    val coloredPinyin: Boolean = false,
    val language: String = "zh-CN",
)

/**
 * Capa fina de UI sobre [ClassController] (que posee el motor, la grabación y
 * el autoguardado) y los ajustes. La clase vive en el controlador: si la UI se
 * destruye, la sesión sigue y al reabrir la app se recupera el estado.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)

    private val _ui = MutableStateFlow(LiveUiState())
    val ui: StateFlow<LiveUiState> = _ui.asStateFlow()

    /** Idioma que funcionaba antes del último cambio (para revertir si falla el nuevo). */
    private var previousLanguage: String? = null
    private var languageSwitchJob: kotlinx.coroutines.Job? = null

    /** Motor que funcionaba antes del último cambio (reversión si el nuevo falla). */
    private var previousEngineId: String? = null

    init {
        AppGraph.init(app)
        _ui.value = LiveUiState(
            isListening = ClassController.isListening.value,
            partial = ClassController.partial.value,
            lines = ClassController.lines.value,
            elapsedMs = ClassController.elapsedMs.value,
            recording = ClassController.recording.value,
            recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(app),
            onDeviceAvailable = isOnDeviceRecognitionAvailable(app),
            batteryIgnored = isIgnoringBatteryOptimizations(app),
            pinyinEnabled = settings.pinyinEnabled,
            fontSizeSp = settings.fontSizeSp,
            keepScreenOn = settings.keepScreenOn,
            muteBeep = settings.muteBeep,
            recordAudio = settings.recordAudio,
            cleanAudioDays = settings.cleanAudioDays,
            engineId = settings.engineId,
            theme = settings.theme,
            coloredPinyin = settings.coloredPinyin,
            language = settings.language,
        )
        // Limpieza de audio antiguo (la transcripción siempre se conserva).
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.sessions.cleanOldAudio(settings.cleanAudioDays)
        }
        // Estado en vivo desde el controlador (que sigue vivo sin la UI):
        viewModelScope.launch {
            ClassController.isListening.collect { v -> _ui.update { it.copy(isListening = v) } }
        }
        viewModelScope.launch {
            ClassController.partial.collect { v -> _ui.update { it.copy(partial = v) } }
        }
        viewModelScope.launch {
            ClassController.lines.collect { v -> _ui.update { it.copy(lines = v) } }
        }
        viewModelScope.launch {
            ClassController.elapsedMs.collect { v -> _ui.update { it.copy(elapsedMs = v) } }
        }
        viewModelScope.launch {
            ClassController.recording.collect { v -> _ui.update { it.copy(recording = v) } }
        }
        viewModelScope.launch {
            ClassController.restartCount.collect { v -> _ui.update { it.copy(restartCount = v) } }
        }
        viewModelScope.launch {
            ClassController.notice.collect { v -> _ui.update { it.copy(infoMessage = v) } }
        }
        viewModelScope.launch {
            ClassController.state.collect { state ->
                if (state is EngineState.Error) handleEngineError(state)
            }
        }
    }

    fun startListening() {
        val app = getApplication<Application>()
        val state = _ui.value
        if (state.isListening) return
        if (!state.recognitionAvailable) {
            _ui.update {
                it.copy(
                    fatalMessage = "Este dispositivo no tiene servicio de reconocimiento de voz " +
                        "(normalmente lo aporta la app de Google). Instálalo o actualízalo e inténtalo de nuevo.",
                )
            }
            return
        }
        // El servicio en primer plano va antes: declara el uso del micrófono
        // para que la escucha sobreviva a la pantalla apagada.
        ClassService.start(app)
        val engineId = if (state.engineId == "ondevice" && !state.onDeviceAvailable) {
            _ui.update {
                it.copy(
                    engineId = "online",
                    infoMessage = "El reconocimiento en el dispositivo no está disponible; " +
                        "sigo con Google en línea.",
                )
            }
            settings.engineId = "online"
            "online"
        } else {
            state.engineId
        }
        val recorded = ClassController.start(state.language, state.muteBeep, state.recordAudio, engineId)
        if (state.recordAudio && !recorded) {
            _ui.update {
                it.copy(
                    fatalMessage = null,
                    infoMessage = "No he podido iniciar la grabación de audio; " +
                        "sigo solo con la transcripción.",
                )
            }
        } else {
            _ui.update { it.copy(fatalMessage = null) }
        }
    }

    fun stopListening() {
        if (!_ui.value.isListening) return
        ClassController.stop()
        ClassService.stop(getApplication())
    }

    fun clearFatalMessage() = _ui.update { it.copy(fatalMessage = null) }

    fun clearInfoMessage() = _ui.update { it.copy(infoMessage = null) }

    /** Botón ★ de la pantalla en vivo: marca/desmarca la última frase confirmada. */
    fun toggleImportantLast() = ClassController.toggleImportantLast()

    /** Estrella de una línea concreta (en vivo o en el detalle). */
    fun setImportant(offsetMs: Long, text: String, important: Boolean) =
        ClassController.setImportant(offsetMs, text, important)

    /** Recalcula el estado de la optimización de batería (p. ej. al volver de Ajustes). */
    fun refreshBatteryState() {
        _ui.update { it.copy(batteryIgnored = isIgnoringBatteryOptimizations(getApplication())) }
    }

    // ----- Ajustes -----

    fun setPinyinEnabled(enabled: Boolean) {
        settings.pinyinEnabled = enabled
        _ui.update { it.copy(pinyinEnabled = enabled) }
    }

    fun setFontSizeSp(size: Int) {
        settings.fontSizeSp = size
        _ui.update { it.copy(fontSizeSp = size) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        settings.keepScreenOn = enabled
        _ui.update { it.copy(keepScreenOn = enabled) }
    }

    fun setMuteBeep(enabled: Boolean) {
        settings.muteBeep = enabled
        _ui.update { it.copy(muteBeep = enabled) }
    }

    fun setRecordAudio(enabled: Boolean) {
        settings.recordAudio = enabled
        _ui.update { it.copy(recordAudio = enabled) }
    }

    fun setCleanAudioDays(days: Int) {
        settings.cleanAudioDays = days
        _ui.update { it.copy(cleanAudioDays = days) }
    }

    fun setEngineId(id: String) {
        if (id == _ui.value.engineId) return
        previousEngineId = _ui.value.engineId
        settings.engineId = id
        _ui.update { it.copy(engineId = id, infoMessage = null) }
        if (_ui.value.isListening) {
            languageSwitchJob?.cancel()
            languageSwitchJob = viewModelScope.launch {
                ClassController.activeEngine()?.stop()
                delay(400)
                if (_ui.value.isListening && _ui.value.engineId == id) {
                    ClassController.restartWithEngine(id)
                }
            }
        }
    }

    fun setTheme(theme: String) {
        settings.theme = theme
        _ui.update { it.copy(theme = theme) }
    }

    fun setColoredPinyin(enabled: Boolean) {
        settings.coloredPinyin = enabled
        _ui.update { it.copy(coloredPinyin = enabled) }
    }

    fun setLanguage(tag: String) {
        if (tag == _ui.value.language) return
        previousLanguage = _ui.value.language
        settings.language = tag
        _ui.update { it.copy(language = tag, infoMessage = null) }
        if (_ui.value.isListening) {
            // Cambia de idioma sin perder la sesión en curso. Pequeña pausa tras
            // stop(): reconectar inmediatamente provoca ERROR_SERVER_DISCONNECTED.
            languageSwitchJob?.cancel()
            languageSwitchJob = viewModelScope.launch {
                ClassController.activeEngine()?.stop()
                delay(400)
                val engine = ClassController.activeEngine() ?: return@launch
                if (_ui.value.isListening && _ui.value.language == tag) {
                    engine.start(
                        RecognitionConfig(languageTag = tag, muteBeep = _ui.value.muteBeep),
                    )
                }
            }
        }
    }

    // ----- Internos -----

    /**
     * Si el idioma elegido hace poco no funciona (p. ej. el servicio no lo sirve),
     * volvemos automáticamente al idioma anterior y seguimos con la clase; el
     * usuario ve un aviso pero no pierde la sesión. Si el error es del idioma
     * "de siempre", ahí sí: parar y mostrar el error en grande.
     */
    private suspend fun handleEngineError(state: EngineState.Error) {
        val current = _ui.value.language
        val previous = previousLanguage
        when {
            // Idioma recién cambiado que falla: revertir idioma.
            previous != null && previous != current -> {
                previousLanguage = null
                _ui.update {
                    it.copy(
                        fatalMessage = null,
                        infoMessage = "No he podido usar «${languageLabel(current)}» " +
                            "(${state.message}). Sigo con «${languageLabel(previous)}».",
                    )
                }
                settings.language = previous
                _ui.update { it.copy(language = previous) }
                ClassController.activeEngine()?.stop()
                delay(400)
                val engine = ClassController.activeEngine()
                if (_ui.value.isListening && engine != null) {
                    engine.start(
                        RecognitionConfig(languageTag = previous, muteBeep = _ui.value.muteBeep),
                    )
                }
            }
            // Motor recién cambiado que falla, o motor no-por-defecto guardado
            // que resulta no funcionar: revertir a Google en línea.
            (previousEngineId != null && previousEngineId != _ui.value.engineId) ||
                _ui.value.engineId != "online" -> {
                val failed = _ui.value.engineId
                val fallback = if (previousEngineId != null && previousEngineId != failed) {
                    previousEngineId!!
                } else {
                    "online"
                }
                previousEngineId = null
                _ui.update {
                    it.copy(
                        fatalMessage = null,
                        infoMessage = "El motor «${engineLabel(failed)}» no funciona aquí " +
                            "(${state.message}). Sigo con «${engineLabel(fallback)}».",
                    )
                }
                settings.engineId = fallback
                _ui.update { it.copy(engineId = fallback) }
                delay(400)
                if (_ui.value.isListening) ClassController.restartWithEngine(fallback)
            }
            else -> {
                _ui.update { it.copy(fatalMessage = state.message) }
                if (_ui.value.isListening) stopListening()
            }
        }
    }

    private fun engineLabel(id: String): String = when (id) {
        "ondevice" -> "en el dispositivo"
        else -> "Google en línea"
    }

    private fun languageLabel(tag: String): String = when (tag) {
        "zh-CN" -> "中文"
        "zh-TW" -> "繁體"
        "es-ES" -> "Español"
        else -> tag
    }

    private fun isIgnoringBatteryOptimizations(app: Application): Boolean {
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }

    private fun isOnDeviceRecognitionAvailable(app: Application): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
}
