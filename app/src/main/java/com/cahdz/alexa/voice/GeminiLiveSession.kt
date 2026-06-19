package com.cahdz.alexa.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.cahdz.alexa.BuildConfig
import com.cahdz.alexa.tools.ToolRegistry
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveGenerationConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GeminiLiveSession(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var running = false
    private var sendJob: Job? = null

    private val toolRegistry = ToolRegistry(context)

    suspend fun run() = coroutineScope {
        running = true

        val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .liveModel(
                modelName = BuildConfig.GEMINI_MODEL,
                liveGenerationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    speechConfig {
                        voice { prebuiltVoiceName = "Kore" }
                        languageCode = "es-MX"
                    }
                    systemInstruction = content {
                        text(SYSTEM_PROMPT)
                    }
                },
                tools = toolRegistry.buildGeminiTools(),
            )

        val session = liveModel.connect()

        initAudio()

        try {
            sendJob = launch(Dispatchers.IO) { sendAudioLoop(session) }

            session.receive().collect { response ->
                if (!running) return@collect

                response.data?.let { audioData ->
                    audioTrack?.write(audioData, 0, audioData.size)
                }

                response.functionCalls.forEach { call ->
                    Log.i(TAG, "Tool call: ${call.name}(${call.args})")
                    val result = toolRegistry.handleCall(call.name, call.args)
                    Log.i(TAG, "Tool result: $result")
                    session.sendFunctionResponse(
                        listOf(
                            FunctionResponsePart(
                                name = call.name,
                                response = JSONObject().apply {
                                    put("result", result)
                                },
                            )
                        )
                    )
                }

                if (response.status?.endOfTurn == true) {
                    Log.i(TAG, "Turn complete")
                }
            }
        } finally {
            sendJob?.cancel()
            releaseAudio()
            session.disconnect()
            running = false
            Log.i(TAG, "Gemini session closed")
        }
    }

    suspend fun stop() {
        running = false
        sendJob?.cancel()
    }

    private fun initAudio() {
        val minBufRecord = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufRecord * 2,
        )
        audioRecord?.startRecording()

        val minBufTrack = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBufTrack * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private suspend fun sendAudioLoop(session: com.google.firebase.ai.type.LiveSession) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_SIZE)
            while (running && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    session.sendMediaStream(
                        data = buffer.copyOf(read),
                        mimeType = "audio/pcm;rate=$SAMPLE_RATE",
                    )
                }
            }
        }
    }

    private fun releaseAudio() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    companion object {
        private const val TAG = "GeminiLiveSession"
        private const val SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val CHUNK_SIZE = 4096

        private const val SYSTEM_PROMPT = """Eres Alexa, asistente de voz personal en un teléfono Android.
Hablas español mexicano, relajado y amigable — como un cuate que sabe de todo.
Sé conciso: respuestas cortas y directas, sin rodeos.
Tu zona horaria es America/Mexico_City (Querétaro).

HERRAMIENTAS DISPONIBLES:
- Búsqueda web: cuando pregunten datos actuales, clima, noticias, o cualquier cosa que necesite internet.
- Spotify: cuando pidan música, playlists, o control de reproducción (pausar, siguiente, volumen).
  Usa content_type='playlist' si piden playlist, 'album' si piden álbum, 'track' para canciones.
- YouTube: cuando pidan ver algo de YouTube. Se abre en la app de YouTube del teléfono.
- Alarmas: cuando pidan poner una alarma o timer. Usa set_alarm con tiempo relativo ('5 minutos') o absoluto ('7:30').
- Recordatorios: cuando pidan recordar algo. Usa set_reminder con tiempo y mensaje.
- list_alarms: para ver alarmas activas. cancel_alarm: para cancelar una.

Si no estás seguro de qué herramienta usar, pregunta.
Si algo falla, dilo directamente sin disculparte demasiado."""
    }
}
