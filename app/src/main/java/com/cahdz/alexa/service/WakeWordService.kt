package com.cahdz.alexa.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cahdz.alexa.AlexaApp
import com.cahdz.alexa.MainActivity
import com.cahdz.alexa.R
import com.cahdz.alexa.debug.DebugLogStore
import com.cahdz.alexa.voice.GeminiLiveSession
import com.cahdz.alexa.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    private var detector: WakeWordDetector? = null
    private var geminiSession: GeminiLiveSession? = null
    private var sessionJob: Job? = null
    private var safetyJob: Job? = null
    private var activeSessionId = 0
    private var shouldKeepListening = false
    private val preferences: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _wakeWordThreshold = MutableStateFlow(DEFAULT_WAKE_WORD_THRESHOLD)
    val wakeWordThreshold: StateFlow<Float> = _wakeWordThreshold.asStateFlow()

    enum class AssistantState {
        IDLE,
        LISTENING,
        USER_SPEAKING,
        THINKING,
        SPEAKING,
        SESSION_ACTIVE,
    }

    inner class LocalBinder : Binder() {
        fun getService(): WakeWordService = this@WakeWordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        _wakeWordThreshold.value = preferences.getFloat(
            PREF_WAKE_WORD_THRESHOLD,
            DEFAULT_WAKE_WORD_THRESHOLD,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        if (shouldKeepListening && detector?.isListening == true) return

        shouldKeepListening = true
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Escuchando..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        detector = WakeWordDetector(
            context = this,
            threshold = _wakeWordThreshold.value,
            onWakeWord = { onWakeWordDetected() },
        )
        detector?.start(scope)
        _state.value = AssistantState.LISTENING

        DebugLogStore.i(TAG, "Wake word service started")
    }

    fun setWakeWordThreshold(threshold: Float) {
        val rounded = (threshold * 100).toInt() / 100f
        val clamped = rounded.coerceIn(MIN_WAKE_WORD_THRESHOLD, MAX_WAKE_WORD_THRESHOLD)
        if (_wakeWordThreshold.value == clamped) return

        _wakeWordThreshold.value = clamped
        preferences.edit().putFloat(PREF_WAKE_WORD_THRESHOLD, clamped).apply()
        DebugLogStore.i(TAG, "Wake word threshold updated: $clamped")

        if (shouldKeepListening && _state.value == AssistantState.LISTENING) {
            detector?.stop()
            detector = WakeWordDetector(
                context = this,
                threshold = clamped,
                onWakeWord = { onWakeWordDetected() },
            )
            detector?.start(scope)
        }
    }

    private fun stopListening() {
        DebugLogStore.i(TAG, "Stopping wake word service")
        shouldKeepListening = false
        detector?.stop()
        detector = null
        geminiSession?.stop()
        safetyJob?.cancel()
        safetyJob = null
        activeSessionId++
        _state.value = AssistantState.IDLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun onWakeWordDetected() {
        if (_state.value != AssistantState.LISTENING) return

        DebugLogStore.i(TAG, "Wake word detected - starting Gemini session")
        _state.value = AssistantState.SESSION_ACTIVE

        bringAppToForeground()
        detector?.stop()

        val sessionId = ++activeSessionId
        safetyJob?.cancel()

        sessionJob = scope.launch {
            try {
                geminiSession = GeminiLiveSession(this@WakeWordService) { event ->
                    Log.d(TAG, "Session event: $event")
                    _state.value = when (event) {
                        GeminiLiveSession.SessionEvent.MIC_ACTIVE -> AssistantState.SESSION_ACTIVE
                        GeminiLiveSession.SessionEvent.USER_SPEAKING -> AssistantState.USER_SPEAKING
                        GeminiLiveSession.SessionEvent.THINKING -> AssistantState.THINKING
                        GeminiLiveSession.SessionEvent.GEMINI_SPEAKING -> AssistantState.SPEAKING
                        GeminiLiveSession.SessionEvent.IDLE -> {
                            if (shouldKeepListening) AssistantState.LISTENING else AssistantState.IDLE
                        }
                    }
                }
                geminiSession?.run()
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "Gemini session error", e)
            } finally {
                if (activeSessionId != sessionId) {
                    DebugLogStore.i(TAG, "Ignoring stale session cleanup: $sessionId")
                    return@launch
                }

                safetyJob?.cancel()
                safetyJob = null
                geminiSession = null
                if (shouldKeepListening) {
                    _state.value = AssistantState.LISTENING
                    restoreListeningNotification()
                    detector?.start(scope)
                    DebugLogStore.i(TAG, "Session ended - listening again")
                } else {
                    _state.value = AssistantState.IDLE
                    DebugLogStore.i(TAG, "Session ended - service stopped")
                }
            }
        }

        safetyJob = scope.launch {
            delay(SESSION_SAFETY_TIMEOUT_MS)
            if (
                activeSessionId == sessionId &&
                _state.value != AssistantState.LISTENING &&
                geminiSession != null
            ) {
                DebugLogStore.w(TAG, "Session safety timeout - forcing back to wake word (session=$sessionId)")
                geminiSession?.stop()
                geminiSession = null
                sessionJob?.cancel()
                if (shouldKeepListening) {
                    _state.value = AssistantState.LISTENING
                    restoreListeningNotification()
                    detector?.stop()
                    detector = WakeWordDetector(
                        context = this@WakeWordService,
                        threshold = _wakeWordThreshold.value,
                        onWakeWord = { onWakeWordDetected() },
                    )
                    detector?.start(scope)
                }
            }
        }
    }

    private fun bringAppToForeground() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, 2, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, AlexaApp.CHANNEL_SESSION)
            .setContentTitle("Alexa Assistant")
            .setContentText("Sesion activa - habla ahora")
            .setSmallIcon(R.drawable.ic_mic)
            .setFullScreenIntent(fullScreenPi, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .build()

        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun restoreListeningNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification("Escuchando..."))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, AlexaApp.CHANNEL_LISTENING)
            .setContentTitle("Alexa Assistant")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Detener", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        shouldKeepListening = false
        detector?.stop()
        detector = null
        geminiSession?.stop()
        safetyJob?.cancel()
        safetyJob = null
        activeSessionId++
        scope.cancel()
        _state.value = AssistantState.IDLE
        DebugLogStore.i(TAG, "Wake word service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1
        private const val SESSION_SAFETY_TIMEOUT_MS = 18_000L
        private const val PREFS_NAME = "wake_word_settings"
        private const val PREF_WAKE_WORD_THRESHOLD = "wake_word_threshold"
        private const val DEFAULT_WAKE_WORD_THRESHOLD = 0.50f
        private const val MIN_WAKE_WORD_THRESHOLD = 0.05f
        private const val MAX_WAKE_WORD_THRESHOLD = 0.95f
        const val ACTION_START = "com.cahdz.alexa.START_LISTENING"
        const val ACTION_STOP = "com.cahdz.alexa.STOP_LISTENING"
    }
}
