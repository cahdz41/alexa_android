package com.cahdz.alexa.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cahdz.alexa.AlexaApp
import com.cahdz.alexa.MainActivity
import com.cahdz.alexa.R
import com.cahdz.alexa.voice.GeminiLiveSession
import com.cahdz.alexa.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    private var detector: WakeWordDetector? = null
    private var geminiSession: GeminiLiveSession? = null

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    enum class AssistantState {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING,
        SESSION_ACTIVE,
    }

    inner class LocalBinder : Binder() {
        fun getService(): WakeWordService = this@WakeWordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startListening() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Escuchando..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        detector = WakeWordDetector(
            context = this,
            onWakeWord = { onWakeWordDetected() },
        )
        detector?.start(scope)
        _state.value = AssistantState.LISTENING

        Log.i(TAG, "Wake word service started")
    }

    private fun onWakeWordDetected() {
        if (_state.value == AssistantState.SESSION_ACTIVE) return

        Log.i(TAG, "Wake word detected — starting Gemini session")
        _state.value = AssistantState.SESSION_ACTIVE
        updateNotification("Sesión activa — hablando con Gemini")

        detector?.stop()

        scope.launch {
            try {
                geminiSession = GeminiLiveSession(this@WakeWordService)
                geminiSession?.run()
            } catch (e: Exception) {
                Log.e(TAG, "Gemini session error", e)
            } finally {
                geminiSession = null
                _state.value = AssistantState.LISTENING
                updateNotification("Escuchando...")
                detector?.start(scope)
                Log.i(TAG, "Session ended — listening again")
            }
        }
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

    private fun updateNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        detector?.stop()
        scope.launch {
            geminiSession?.stop()
        }
        scope.cancel()
        _state.value = AssistantState.IDLE
        Log.i(TAG, "Wake word service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.cahdz.alexa.START_LISTENING"
        const val ACTION_STOP = "com.cahdz.alexa.STOP_LISTENING"
    }
}
