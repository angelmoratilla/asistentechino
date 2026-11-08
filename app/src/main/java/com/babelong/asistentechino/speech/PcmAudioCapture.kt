package com.babelong.asistentechino.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * Captura PCM del micrófono UNA sola vez y lo entrega a la vez a:
 *  - [onPcm] (el reconocedor offline lo consume), y
 *  - un codificador AAC → fichero .m4a.
 *
 * Es la pieza que resuelve el conflicto de micrófono de MIUI: ya no compiten
 * MediaRecorder y SpeechRecognizer por el micro; hay un único dueño del audio.
 *
 * Todas las llamadas desde el hilo principal; el trabajo ocurre en su hilo propio.
 */
class PcmAudioCapture(
    private val sampleRate: Int = 16_000,
    private val onPcm: (ShortArray) -> Unit,
) {

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamples = 0L

    /**
     * Empieza a capturar el micro. Si [outputFile] != null, además codifica el
     * mismo PCM a AAC-LC 96 kbps mono en ese fichero. Devuelve false si el
     * micro no está disponible.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO se comprueba antes de arrancar la sesión
    fun start(outputFile: File?): Boolean = try {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, 16_384),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord no inicializado (¿micro ocupado?)")
        }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        if (outputFile != null) {
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder = enc
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        running = true
        thread = Thread({ loop() }, "PcmAudioCapture").apply { start() }
        true
    } catch (e: Exception) {
        Log.w(TAG, "no se pudo iniciar la captura PCM: ${e.message}")
        releaseAll()
        false
    }

    /** Detiene la captura, cierra el fichero (EOS + muxer) y libera todo. Idempotente. */
    fun stop() {
        running = false
        thread?.join(3_000)
        thread = null
        releaseAll()
    }

    private fun loop() {
        val record = audioRecord ?: return
        val enc = encoder
        try {
            enc?.start()
            record.startRecording()
            val buffer = ShortArray(2_048)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                onPcm(buffer.copyOf(read))

                if (enc == null) continue  // sin grabación: solo alimenta al reconocedor

                val inIndex = enc.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = enc.getInputBuffer(inIndex)!!
                    inBuf.clear()
                    for (i in 0 until read) inBuf.putShort(buffer[i])
                    val ptsUs = totalSamples * 1_000_000L / sampleRate
                    totalSamples += read
                    enc.queueInputBuffer(inIndex, 0, read * 2, ptsUs, 0)
                }
                drainEncoder(endOfStream = false)
            }
            if (enc == null) return
            // Fin de sesión: EOS al codificador y drenaje final.
            val inIndex = enc.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                enc.queueInputBuffer(
                    inIndex, 0, 0,
                    totalSamples * 1_000_000L / sampleRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drainEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.w(TAG, "bucle de captura interrumpido: ${e.message}")
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        mux.writeSampleData(trackIndex, outBuf, info)
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!endOfStream) return
            }
        }
    }

    private fun releaseAll() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        try {
            muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
        trackIndex = -1
    }

    private companion object {
        const val TAG = "PcmAudioCapture"
    }
}
