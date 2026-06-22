package com.cahdz.alexa.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.cahdz.alexa.BuildConfig
import com.cahdz.alexa.tools.ToolRegistry
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)
class GeminiLiveSession(
    private val context: Context,
    private val onSessionEvent: (SessionEvent) -> Unit = {},
) {

    enum class SessionEvent { MIC_ACTIVE, GEMINI_SPEAKING, IDLE }

    private val toolRegistry = ToolRegistry(context)

    @Volatile private var running = false
    @Volatile private var modelSpeaking = false

    private var session: com.google.firebase.ai.type.LiveSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private val sessionDone = CompletableDeferred<Unit>()

    private var lastActivityMs = 0L
    private var lastModelAudioMs = 0L
    private var sessionStartMs = 0L
    private var userHasSpoken = false
    private var unmuteAtMs = 0L

    suspend fun run() {
        running = true
        val now = System.currentTimeMillis()
        lastActivityMs = now
        sessionStartMs = now

        val systemInstruction = content { text(SYSTEM_PROMPT) }
        val tools = toolRegistry.buildGeminiTools()

        val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .liveModel(
                modelName = BuildConfig.GEMINI_MODEL,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    speechConfig = SpeechConfig(voice = Voice("Aoede"))
                },
                systemInstruction = systemInstruction,
                tools = tools,
            )

        try {
            Log.i(TAG, "Connecting to Gemini Live...")
            session = liveModel.connect()
            Log.i(TAG, "Connected! Starting manual audio session...")

            initAudio()
            onSessionEvent(SessionEvent.MIC_ACTIVE)

            coroutineScope {
                launch(Dispatchers.IO) {
                    try { sendAudioLoop() }
                    catch (e: Exception) { Log.w(TAG, "Send ended: ${e.message}") }
                    sessionDone.complete(Unit)
                }
                launch(Dispatchers.IO) {
                    try { receiveLoop() }
                    catch (e: Exception) { Log.w(TAG, "Receive ended: ${e.message}") }
                    sessionDone.complete(Unit)
                }
                launch(Dispatchers.Default) {
                    try { idleWatchdog() }
                    catch (e: Exception) { Log.w(TAG, "Watchdog ended: ${e.message}") }
                    sessionDone.complete(Unit)
                }

                sessionDone.await()
                running = false
                coroutineContext.cancelChildren()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session error: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            running = false
            releaseAudio()
            withContext(NonCancellable) {
                try { session?.close() } catch (_: Exception) {}
            }
            session = null
            onSessionEvent(SessionEvent.IDLE)
            Log.i(TAG, "Gemini session closed")
        }
    }

    private fun initAudio() {
        val minBufIn = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN, CHANNEL_IN, ENCODING)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_IN,
            CHANNEL_IN,
            ENCODING,
            maxOf(minBufIn, CHUNK_BYTES * 4),
        )

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
            echoCanceler?.enabled = true
            Log.i(TAG, "AEC enabled")
        }

        val minBufOut = AudioTrack.getMinBufferSize(SAMPLE_RATE_OUT, CHANNEL_OUT, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufOut, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord!!.startRecording()
        audioTrack!!.play()
        Log.i(TAG, "Audio initialized (mic=${SAMPLE_RATE_IN}Hz, speaker=${SAMPLE_RATE_OUT}Hz)")
    }

    private fun releaseAudio() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        try { echoCanceler?.release() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
        echoCanceler = null
    }

    private fun isMuted(): Boolean =
        modelSpeaking || System.currentTimeMillis() < unmuteAtMs

    private suspend fun sendAudioLoop() {
        val buffer = ByteArray(CHUNK_BYTES)
        val record = audioRecord ?: return
        val sess = session ?: return

        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) { delay(10); continue }
            if (isMuted()) continue

            val chunk = buffer.copyOf(read)
            sess.sendAudioRealtime(InlineData(chunk, AUDIO_MIME))
        }
    }

    private suspend fun receiveLoop() {
        val sess = session ?: return

        sess.receive().collect { msg ->
            if (!running) return@collect

            when (msg) {
                is LiveServerContent -> handleContent(msg)
                is LiveServerToolCall -> handleFunctionCalls(sess, msg.functionCalls)
                is LiveServerGoAway -> {
                    Log.w(TAG, "Server go_away — closing")
                    running = false
                    sessionDone.complete(Unit)
                }
                else -> {}
            }
        }
        Log.i(TAG, "Receive stream ended")
    }

    private fun handleContent(msg: LiveServerContent) {
        msg.content?.parts?.forEach { part ->
            if (part is InlineDataPart) {
                modelSpeaking = true
                lastModelAudioMs = System.currentTimeMillis()
                onSessionEvent(SessionEvent.GEMINI_SPEAKING)
                audioTrack?.write(part.inlineData, 0, part.inlineData.size)
            }
        }

        msg.inputTranscription?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            lastActivityMs = System.currentTimeMillis()
            userHasSpoken = true
            Log.i(TAG, "[User] $it")
        }

        msg.outputTranscription?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Log.i(TAG, "[Gemini] $it")
        }

        if (msg.turnComplete || msg.generationComplete) {
            modelSpeaking = false
            val now = System.currentTimeMillis()
            unmuteAtMs = now + ECHO_TAIL_MS
            lastActivityMs = now + ECHO_TAIL_MS
            onSessionEvent(SessionEvent.MIC_ACTIVE)
            Log.i(TAG, "Turn complete — listening")
        }

        if (msg.interrupted) {
            modelSpeaking = false
            unmuteAtMs = System.currentTimeMillis() + ECHO_TAIL_MS
            audioTrack?.flush()
            onSessionEvent(SessionEvent.MIC_ACTIVE)
            Log.i(TAG, "Barge-in — cleared audio")
        }
    }

    private suspend fun handleFunctionCalls(
        sess: com.google.firebase.ai.type.LiveSession,
        calls: List<FunctionCallPart>,
    ) {
        lastActivityMs = System.currentTimeMillis()

        val responses = calls.map { call ->
            Log.i(TAG, "Tool call: ${call.name}(${call.args})")

            val result = try {
                val args = call.args.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.content ?: v.toString().trim('"')
                }
                toolRegistry.handleCall(call.name, args)
            } catch (e: Exception) {
                Log.e(TAG, "Tool ${call.name} failed", e)
                "Error: ${e.message}"
            }

            Log.i(TAG, "Tool result: $result")

            FunctionResponsePart(
                call.name,
                JsonObject(mapOf("result" to JsonPrimitive(result)))
            )
        }

        lastActivityMs = System.currentTimeMillis()
        try {
            sess.sendFunctionResponse(responses)
        } catch (e: Exception) {
            Log.e(TAG, "sendFunctionResponse failed", e)
        }
    }

    private suspend fun idleWatchdog() {
        while (running) {
            delay(1_000L)
            val now = System.currentTimeMillis()

            if (now - sessionStartMs >= MAX_SESSION_MS) {
                Log.i(TAG, "Max session duration reached — closing")
                return
            }

            if (modelSpeaking) {
                if (now - lastModelAudioMs > MODEL_SPEAK_TIMEOUT_MS) {
                    Log.w(TAG, "Model speaking stuck (${(now - lastModelAudioMs) / 1000}s without audio) — forcing idle")
                    modelSpeaking = false
                    lastActivityMs = now
                }
                continue
            }

            val timeout = if (userHasSpoken) IDLE_TIMEOUT_MS else INITIAL_TIMEOUT_MS
            if (now - lastActivityMs >= timeout) {
                Log.i(TAG, "Idle timeout (${(now - lastActivityMs) / 1000}s) — closing")
                return
            }
        }
    }

    fun stop() {
        running = false
        sessionDone.complete(Unit)
    }

    companion object {
        private const val TAG = "GeminiLiveSession"

        private const val SAMPLE_RATE_IN = 16000
        private const val SAMPLE_RATE_OUT = 24000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_MIME = "audio/pcm;rate=16000"
        private const val CHUNK_BYTES = 1280

        private const val IDLE_TIMEOUT_MS = 12_000L
        private const val INITIAL_TIMEOUT_MS = 15_000L
        private const val MAX_SESSION_MS = 300_000L
        private const val ECHO_TAIL_MS = 300L
        private const val MODEL_SPEAK_TIMEOUT_MS = 5_000L

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
