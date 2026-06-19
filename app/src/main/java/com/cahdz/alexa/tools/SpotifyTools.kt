package com.cahdz.alexa.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Spotify control via App Remote SDK.
 *
 * NOTE: Requires the Spotify app installed + Premium account.
 * The spotify-app-remote AAR must be in app/libs/.
 * Until the AAR is added, this uses intent-based fallback to open Spotify.
 *
 * TODO: Integrate SpotifyAppRemote when AAR is available:
 *   - SpotifyAppRemote.connect() with ConnectionParams
 *   - playerApi.play(uri), pause(), resume(), skipNext(), skipPrevious()
 *   - playerApi.subscribeToPlayerState() for current track info
 */
class SpotifyTools(private val context: Context) {

    suspend fun play(query: String, contentType: String = "track"): String {
        return try {
            val searchUri = Uri.parse("spotify:search:$query")
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                "Abriendo '$query' en Spotify"
            } else {
                "Spotify no está instalado en este dispositivo"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spotify play failed", e)
            "Error al abrir Spotify: ${e.message}"
        }
    }

    suspend fun pause(): String {
        return sendMediaCommand("pause")
    }

    suspend fun resume(): String {
        return sendMediaCommand("play")
    }

    suspend fun next(): String {
        return sendMediaCommand("next")
    }

    suspend fun previous(): String {
        return sendMediaCommand("previous")
    }

    suspend fun setVolume(level: Int): String {
        // Volume control requires App Remote SDK
        return "Control de volumen requiere integración App Remote (pendiente)"
    }

    suspend fun currentTrack(): String {
        // Current track info requires App Remote SDK
        return "Info de track actual requiere integración App Remote (pendiente)"
    }

    private fun sendMediaCommand(command: String): String {
        return try {
            val intent = Intent("com.spotify.mobile.android.ui.widget.PLAY_$command").apply {
                setPackage("com.spotify.music")
            }
            context.sendBroadcast(intent)
            when (command) {
                "pause" -> "Spotify pausado"
                "play" -> "Reproducción reanudada"
                "next" -> "Siguiente canción"
                "previous" -> "Canción anterior"
                else -> "Comando enviado a Spotify"
            }
        } catch (e: Exception) {
            "Error controlando Spotify: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "SpotifyTools"
    }
}
