package com.cahdz.alexa.tools

import android.content.Context
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool

class ToolRegistry(private val context: Context) {

    private val spotifyTools = SpotifyTools(context)
    private val alarmTools = AlarmTools(context)
    private val youtubeTools = YouTubeTools(context)

    private val handlers: Map<String, suspend (Map<String, String>) -> String> = mapOf(
        "play_spotify" to { args -> spotifyTools.play(args["query"] ?: "", args["content_type"] ?: "track") },
        "pause_spotify" to { _ -> spotifyTools.pause() },
        "resume_spotify" to { _ -> spotifyTools.resume() },
        "next_track" to { _ -> spotifyTools.next() },
        "previous_track" to { _ -> spotifyTools.previous() },
        "set_volume" to { args -> spotifyTools.setVolume(args["level"]?.toIntOrNull() ?: 50) },
        "current_track" to { _ -> spotifyTools.currentTrack() },
        "play_youtube" to { args -> youtubeTools.play(args["query"] ?: "") },
        "set_alarm" to { args -> alarmTools.setAlarm(args["time"] ?: "", args["label"] ?: "Alarma") },
        "set_reminder" to { args -> alarmTools.setReminder(args["time"] ?: "", args["message"] ?: "") },
        "list_alarms" to { _ -> alarmTools.listAlarms() },
        "cancel_alarm" to { args -> alarmTools.cancelAlarm(args["alarm_id"] ?: "") },
        "stop_alarm" to { _ -> alarmTools.stopAlarm() },
    )

    fun buildGeminiTools(): List<Tool> = listOf(
        Tool.functionDeclarations(buildDeclarations())
    )

    suspend fun handleCall(name: String, args: Map<String, String>): String {
        val handler = handlers[name] ?: return "Error: herramienta '$name' no encontrada"
        return try {
            handler(args)
        } catch (e: Exception) {
            "Error ejecutando $name: ${e.message}"
        }
    }

    private fun buildDeclarations(): List<FunctionDeclaration> = listOf(
        FunctionDeclaration(
            name = "play_spotify",
            description = "Busca y reproduce una canción, album o playlist en Spotify.",
            parameters = mapOf(
                "query" to Schema.string("Búsqueda: nombre de canción, artista, album o playlist"),
                "content_type" to Schema.string("Tipo: 'track', 'playlist', o 'album'. Default: 'track'"),
            ),
            optionalParameters = listOf("content_type"),
        ),
        FunctionDeclaration(
            name = "pause_spotify",
            description = "Pausa la reproducción actual de Spotify.",
            parameters = emptyMap(),
        ),
        FunctionDeclaration(
            name = "resume_spotify",
            description = "Reanuda la reproducción de Spotify.",
            parameters = emptyMap(),
        ),
        FunctionDeclaration(
            name = "next_track",
            description = "Salta a la siguiente canción en Spotify.",
            parameters = emptyMap(),
        ),
        FunctionDeclaration(
            name = "previous_track",
            description = "Regresa a la canción anterior en Spotify.",
            parameters = emptyMap(),
        ),
        FunctionDeclaration(
            name = "set_volume",
            description = "Ajusta el volumen de Spotify (0 a 100).",
            parameters = mapOf(
                "level" to Schema.integer("Nivel de volumen de 0 a 100"),
            ),
        ),
        FunctionDeclaration(
            name = "current_track",
            description = "Obtiene información de la canción actual en Spotify.",
            parameters = emptyMap(),
        ),
        FunctionDeclaration(
            name = "play_youtube",
            description = "Busca y abre un video de YouTube en la app.",
            parameters = mapOf(
                "query" to Schema.string("Búsqueda: nombre de video, canción o tema"),
            ),
        ),
        FunctionDeclaration(
            name = "set_alarm",
            description = "Programa una alarma. Acepta tiempo relativo ('5 minutos') o absoluto ('7:30').",
            parameters = mapOf(
                "time" to Schema.string("Tiempo: relativo ('5 minutos', '1 hora') o absoluto ('7:30', '19:00')"),
                "label" to Schema.string("Etiqueta de la alarma (ej: 'despertar', 'medicina')"),
            ),
            optionalParameters = listOf("label"),
        ),
        FunctionDeclaration(
            name = "set_reminder",
            description = "Programa un recordatorio con un mensaje.",
            parameters = mapOf(
                "time" to Schema.string("Tiempo: relativo ('10 minutos') o absoluto ('14:00')"),
                "message" to Schema.string("Mensaje del recordatorio"),
            ),
        ),
        FunctionDeclaration(
            name = "list_alarms",
            description = "Lista todas las alarmas y recordatorios activos.",
            parameters = emptyMap(),
        ),
        FunctionDeclaration(
            name = "cancel_alarm",
            description = "Cancela una alarma o recordatorio por su ID o nombre.",
            parameters = mapOf(
                "alarm_id" to Schema.string("ID o nombre de la alarma a cancelar"),
            ),
        ),
        FunctionDeclaration(
            name = "stop_alarm",
            description = "Apaga la alarma que está sonando en este momento.",
            parameters = emptyMap(),
        ),
    )
}
