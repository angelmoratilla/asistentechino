package com.babelong.asistentechino.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Motor 100 % offline (sherpa-onnx, modelo zipformer-CTC chino int8 en assets).
 *
 * La app es la ÚNICA dueña del micrófono: un [PcmAudioCapture] entrega el PCM
 * a una COLA y un hilo de decodificación dedicado ("sherpa-decode") lo pasa al
 * reconocedor; el mismo PCM se codifica a .m4a. Grabación y transcripción
 * siempre compatibles, sin pitidos y sin conexión.
 *
 * Solo chino de momento (modelo zh). Todas las llamadas desde el hilo principal.
 *
 * Modos DEBUG (solo Fase 1): si existe /data/local/tmp/feed0.wav, el reconocedor
 * se alimenta de ese wav (16 kHz mono int16) EN VEZ del micro; si existe
 * /data/local/tmp/feedmic, el PCM crudo del micro se vuelca a files/model/mic_debug.pcm
 * para validarlo en el PC (ffmpeg -f s16le -ar 16000 -ac 1 -i mic_debug.pcm out.wav).
 */
class OfflineSpeechEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
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

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var capture: PcmAudioCapture? = null
    private var isRunning = false

    private val pcmQueue = ArrayBlockingQueue<ShortArray>(256)
    @Volatile private var decodeThreadRunning = false
    private var decodeThread: Thread? = null

    private var lastPartial = ""
    private var feedCalls = 0L
    private var pcmDumpFile: File? = null

    override fun start(config: RecognitionConfig) {
        if (isRunning) return

        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!ensureModelCopied(modelDir)) {
            _state.value = EngineState.Error(
                ERROR_MODEL,
                "No he podido preparar el modelo offline en el almacenamiento del móvil.",
            )
            return
        }

        try {
            val recognizerCfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                        model = File(modelDir, "model.int8.onnx").absolutePath,
                    ),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    numThreads = 2,
                ),
                enableEndpoint = true,
                endpointConfig = EndpointConfig(),
            )
            val rec = OnlineRecognizer(assetManager = null, config = recognizerCfg)
            recognizer = rec
            stream = rec.createStream()

            isRunning = true
            _restartCount.value = 0
            _partial.value = ""
            lastPartial = ""
            feedCalls = 0
            _state.value = EngineState.Listening
            startDecodeThread()

            // Modos debug (ver doc de la clase).
            val debugWav = File("/data/local/tmp/feed0.wav")
            val debugMicDump = File("/data/local/tmp/feedmic").exists()
            val debugNoMic = debugWav.exists()
            if (debugNoMic) {
                Log.d(TAG, "start offline DEBUG: alimentando desde wav, micro desactivado")
                scope.launch(Dispatchers.IO) { feedWav(debugWav) }
            } else {
                pcmDumpFile = if (debugMicDump) {
                    File(context.filesDir, "mic_debug.pcm").apply {
                        if (exists()) delete()
                    }.also { Log.d(TAG, "volcando PCM crudo del micro a ${it.absolutePath}") }
                } else {
                    null
                }
                val recordToFile = config.recordToFile
                capture = PcmAudioCapture(16_000, onPcm = { samples -> enqueuePcm(samples) })
                val captureOk = capture?.start(recordToFile) ?: false
                if (!captureOk) {
                    failFatal("No he podido acceder al micrófono para el motor offline.")
                    return
                }
                Log.d(TAG, "start offline lang=${config.languageTag} grabando=${recordToFile != null} dump=$debugMicDump")
            }
        } catch (e: Exception) {
            Log.w(TAG, "error iniciando el motor offline: ${e.message}")
            releaseRecognizer()
            _state.value = EngineState.Error(
                ERROR_MODEL,
                "No he podido arrancar el motor offline: ${e.message ?: "error desconocido"}",
            )
        }
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        capture?.stop()
        capture = null
        decodeThreadRunning = false
        decodeThread?.join(3_000)
        decodeThread = null
        pcmQueue.clear()
        releaseRecognizer()
        _partial.value = ""
        _state.value = EngineState.Idle
    }

    /** Hilo de audio: encola el PCM (y lo vuelca si el modo debug de volcado está activo). */
    private fun enqueuePcm(samples: ShortArray) {
        if (pcmDumpFile != null) {
            try {
                pcmDumpFile?.appendBytes(
                    samples.flatMap { listOf((it.toInt() and 0xFF).toByte(), (it.toInt() shr 8).toByte()) }
                        .toByteArray(),
                )
            } catch (_: Exception) {
            }
        }
        pcmQueue.offer(samples)
    }

    /** Hilo "sherpa-decode": consume la cola y alimenta el reconocedor. */
    private fun startDecodeThread() {
        decodeThreadRunning = true
        decodeThread = Thread({
            while (decodeThreadRunning) {
                val samples = try {
                    pcmQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                processSamples(samples)
            }
        }, "sherpa-decode").apply { start() }
    }

    /** SOLO desde el hilo "sherpa-decode": PCM → reconocedor; endpoint → frase final. */
    private fun processSamples(samples: ShortArray) {
        val rec = recognizer ?: return
        val st = stream ?: return
        if (!isRunning) return

        feedCalls += 1
        val floats = FloatArray(samples.size) { samples[it] / 32_768f }
        st.acceptWaveform(floats, 16_000)
        while (rec.isReady(st)) {
            rec.decode(st)
        }

        val text = rec.getResult(st).text.trim()
        if (text.isNotEmpty() && text != lastPartial) {
            lastPartial = text
            _partial.value = text
            Log.d(TAG, "parcial #$feedCalls: '${text.take(30)}'")
        }

        if (rec.isEndpoint(st)) {
            if (text.isNotEmpty()) {
                Log.d(TAG, "ENDPOINT: '$text'")
                _finals.tryEmit(FinalSegment(text, System.currentTimeMillis()))
            }
            lastPartial = ""
            _partial.value = ""
            rec.reset(st)
        }
    }

    /** DEBUG: encola un wav conocido-bueno (16 kHz mono int16) para el decodificador. */
    private suspend fun feedWav(wav: File) {
        try {
            val bytes = wav.readBytes()
            var i = 12
            var dataStart = -1
            var dataLen = 0
            while (i + 8 <= bytes.size) {
                val id = String(bytes, i, 4, Charsets.US_ASCII)
                val size = readLeInt(bytes, i + 4)
                if (id == "data") {
                    dataStart = i + 8
                    dataLen = size
                    break
                }
                i += 8 + size + (size % 2)
            }
            if (dataStart < 0) {
                Log.w(TAG, "feedWav: sin chunk data")
                return
            }
            val n = dataLen / 2
            val chunk = 2_048
            Log.d(TAG, "feedWav: encolando $n muestras")
            for (off in 0 until n step chunk) {
                if (!isRunning) return
                val count = minOf(chunk, n - off)
                val samples = ShortArray(count)
                for (k in 0 until count) {
                    val b0 = bytes[dataStart + (off + k) * 2].toInt() and 0xFF
                    val b1 = bytes[dataStart + (off + k) * 2 + 1].toInt()
                    samples[k] = ((b1 shl 8) or b0).toShort()
                }
                while (!pcmQueue.offer(samples, 100, TimeUnit.MILLISECONDS) && isRunning) {
                    // cola llena: esperar (el ritmo es tiempo real de todos modos)
                }
                delay(128)
            }
            Log.d(TAG, "feedWav: terminado, último texto='${lastPartial.take(40)}'")
        } catch (e: Exception) {
            Log.w(TAG, "feedWav: ${e.message}")
        }
    }

    private fun readLeInt(bytes: ByteArray, pos: Int): Int =
        (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)

    /**
     * Copia el modelo de assets a filesDir la primera vez (el JNI de sherpa
     * lee mejor rutas reales que assets comprimidos). Idempotente.
     */
    private fun ensureModelCopied(modelDir: File): Boolean = try {
        modelDir.mkdirs()
        for (name in MODEL_FILES) {
            val target = File(modelDir, name)
            if (target.exists() && target.length() > 0L) continue // ya copiado
            context.assets.open("$MODEL_DIR/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "copiando modelo offline: ${e.message}")
        false
    }

    private fun failFatal(message: String) {
        isRunning = false
        capture?.stop()
        capture = null
        releaseRecognizer()
        _partial.value = ""
        _state.value = EngineState.Error(ERROR_MODEL, message)
    }

    private fun releaseRecognizer() {
        try {
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
        try {
            recognizer?.release()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    companion object {
        private const val TAG = "OfflineSpeechEngine"
        private const val MODEL_DIR = "model"
        private val MODEL_FILES = listOf("model.int8.onnx", "tokens.txt", "bbpe.model")

        /** Código interno: modelo/almacenamiento no disponible. */
        const val ERROR_MODEL = -110
    }
}
