package com.cahdz.alexa

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.FirebaseApp

class AlexaApp : Application() {

    companion object {
        const val CHANNEL_LISTENING = "wake_word_listening"
        const val CHANNEL_SESSION = "session_active"
        const val CHANNEL_ALARMS = "alarms"
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENING,
                "Escucha activa",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Muestra cuando Alexa está escuchando el wake word"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION,
                "Sesión activa",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerta cuando Alexa detecta el wake word"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARMS,
                "Alarmas y recordatorios",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de alarmas y recordatorios"
            }
        )
    }
}
