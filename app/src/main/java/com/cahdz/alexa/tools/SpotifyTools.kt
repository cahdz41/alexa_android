package com.cahdz.alexa.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.cahdz.alexa.BuildConfig
import com.cahdz.alexa.debug.DebugLogStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Empty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Spotify playback control.
 *
 * Intents can open Spotify searches, but they cannot reliably start playback.
 * Real playback needs the Spotify App Remote AAR in app/libs/ plus a Spotify
 * Developer app configured with the redirect URI from BuildConfig.
 */
class SpotifyTools(private val context: Context) {

    suspend fun play(query: String, contentType: String = "track"): String {
        if (query.isBlank()) {
            return "No recibi el nombre de la cancion para Spotify"
        }

        return try {
            DebugLogStore.i(TAG, "Play requested: query='$query', contentType='$contentType'")
            val item = searchSpotify(query, contentType)
                ?: return openSpotifySearch(query, "No encontre '$query' en Spotify")

            DebugLogStore.i(TAG, "Playing Spotify URI: ${item.uri} (${item.label})")
            playUri(item.uri)
            "Reproduciendo ${item.label} en Spotify"
        } catch (e: MissingSpotifyConfigException) {
            DebugLogStore.w(TAG, "Spotify configuration missing: ${e.message}")
            openSpotifySearch(query, e.message ?: "Falta configurar Spotify")
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "Spotify play failed", e)
            openSpotifySearch(query, "No pude reproducir en Spotify: ${e.message}")
        }
    }

    suspend fun pause(): String {
        return runPlayerCommand("pause", "Spotify pausado")
    }

    suspend fun resume(): String {
        return runPlayerCommand("resume", "Reproduccion reanudada")
    }

    suspend fun next(): String {
        return runPlayerCommand("skipNext", "Siguiente cancion")
    }

    suspend fun previous(): String {
        return runPlayerCommand("skipPrevious", "Cancion anterior")
    }

    suspend fun setVolume(level: Int): String {
        // App Remote controls playback, but device volume is outside this SDK.
        return "El volumen de Spotify en Android requiere controlar el volumen del sistema"
    }

    suspend fun currentTrack(): String {
        return "La informacion de la cancion actual queda pendiente con App Remote"
    }

    private suspend fun runPlayerCommand(methodName: String, successMessage: String): String {
        return try {
            DebugLogStore.i(TAG, "Player command requested: $methodName")
            val playerApi = connectAppRemote().playerApi
            val call = when (methodName) {
                "pause" -> playerApi.pause()
                "resume" -> playerApi.resume()
                "skipNext" -> playerApi.skipNext()
                "skipPrevious" -> playerApi.skipPrevious()
                else -> error("Comando Spotify no soportado: $methodName")
            }
            awaitSpotifyCommand(methodName, call)
            DebugLogStore.i(TAG, "Player command completed: $methodName")
            successMessage
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "Spotify command failed: $methodName", e)
            "Error controlando Spotify: ${e.message}"
        }
    }

    private suspend fun searchSpotify(query: String, contentType: String): SpotifyItem? {
        val type = when (contentType.lowercase().trim()) {
            "playlist" -> "playlist"
            "album" -> "album"
            else -> "track"
        }

        val token = getSpotifyToken()
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = URL("https://api.spotify.com/v1/search?q=$encodedQuery&type=$type&limit=1")
        DebugLogStore.i(TAG, "Searching Spotify: query='$query', type='$type'")

        val response = withContext(Dispatchers.IO) {
            (url.openConnection() as HttpURLConnection).use { connection ->
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        }

        val root = JsonParser.parseString(response).asJsonObject
        val items = root.getAsJsonObject("${type}s")
            ?.getAsJsonArray("items")
            ?: return null
        val first = items.firstOrNull()?.asJsonObject ?: return null
        val uri = first.get("uri")?.asString ?: return null
        val name = first.get("name")?.asString ?: query

        val item = SpotifyItem(uri = uri, label = labelFor(type, name, first))
        DebugLogStore.i(TAG, "Spotify search selected: uri=${item.uri}, label=${item.label}")
        return item
    }

    private fun labelFor(type: String, name: String, item: JsonObject): String {
        return when (type) {
            "track" -> {
                val artists = item.getAsJsonArray("artists")
                    ?.joinToString(", ") { it.asJsonObject.get("name").asString }
                    .orEmpty()
                if (artists.isBlank()) "'$name'" else "'$name' de $artists"
            }
            "album" -> {
                val artists = item.getAsJsonArray("artists")
                    ?.joinToString(", ") { it.asJsonObject.get("name").asString }
                    .orEmpty()
                if (artists.isBlank()) "el album '$name'" else "el album '$name' de $artists"
            }
            "playlist" -> "la playlist '$name'"
            else -> "'$name'"
        }
    }

    private suspend fun getSpotifyToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { token ->
            if (now < token.expiresAtMillis) {
                DebugLogStore.i(TAG, "Using cached Spotify token")
                return token.accessToken
            }
        }

        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        val clientSecret = BuildConfig.SPOTIFY_CLIENT_SECRET
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw MissingSpotifyConfigException(
                "Faltan SPOTIFY_CLIENT_ID y SPOTIFY_CLIENT_SECRET en local.properties para buscar y reproducir canciones.",
            )
        }

        val response = withContext(Dispatchers.IO) {
            val credentials = "$clientId:$clientSecret"
            val basic = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
            (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).use { connection ->
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Basic $basic")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write("grant_type=client_credentials")
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        }

        val json = JsonParser.parseString(response).asJsonObject
        val accessToken = json.get("access_token").asString
        val expiresInSeconds = json.get("expires_in").asLong
        cachedToken = SpotifyToken(
            accessToken = accessToken,
            expiresAtMillis = now + (expiresInSeconds - TOKEN_EXPIRY_BUFFER_SECONDS) * 1000,
        )
        DebugLogStore.i(TAG, "Fetched Spotify client credentials token")
        return accessToken
    }

    private suspend fun playUri(uri: String) {
        val call = connectAppRemote().playerApi.play(uri)
        awaitSpotifyCommand("play", call)
        DebugLogStore.i(TAG, "Play command completed for URI: $uri")
    }

    private suspend fun connectAppRemote(): SpotifyAppRemote {
        appRemote?.let {
            if (it.isConnected) {
                DebugLogStore.i(TAG, "Reusing connected Spotify App Remote")
                return it
            }
            DebugLogStore.w(TAG, "Cached Spotify App Remote is disconnected; reconnecting")
            appRemote = null
        }

        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        val redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI
        if (clientId.isBlank() || redirectUri.isBlank()) {
            throw MissingSpotifyConfigException(
                "Faltan SPOTIFY_CLIENT_ID y SPOTIFY_REDIRECT_URI para conectar con Spotify App Remote.",
            )
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val params = ConnectionParams.Builder(clientId)
                    .setRedirectUri(redirectUri)
                    .showAuthView(true)
                    .build()

                SpotifyAppRemote.connect(
                    context.applicationContext,
                    params,
                    object : Connector.ConnectionListener {
                        override fun onConnected(remote: SpotifyAppRemote) {
                            DebugLogStore.i(TAG, "Spotify App Remote connected")
                            appRemote = remote
                            continuation.resume(remote)
                        }

                        override fun onFailure(error: Throwable) {
                            DebugLogStore.e(TAG, "Spotify App Remote connection failed", error)
                            continuation.resumeWithException(error)
                        }
                    },
                )
            }
        }
    }

    private suspend fun awaitSpotifyCommand(methodName: String, call: CallResult<Empty>) {
        withContext(Dispatchers.IO) {
            val result = call.await(APP_REMOTE_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!result.isSuccessful) {
                throw IllegalStateException(result.errorMessage ?: "Spotify command failed: $methodName")
            }
        }
    }

    private fun openSpotifySearch(query: String, message: String): String {
        return try {
            val searchUri = Uri.parse("spotify:search:${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                DebugLogStore.i(TAG, "Opened Spotify search fallback for query='$query'")
                "$message Abri la busqueda en Spotify."
            } else {
                "Spotify no esta instalado en este dispositivo"
            }
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "Spotify fallback failed", e)
            "$message Error al abrir Spotify: ${e.message}"
        }
    }

    private fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private data class SpotifyItem(
        val uri: String,
        val label: String,
    )

    private data class SpotifyToken(
        val accessToken: String,
        val expiresAtMillis: Long,
    )

    private class MissingSpotifyConfigException(message: String) : Exception(message)

    companion object {
        private const val TAG = "SpotifyTools"
        private const val TOKEN_EXPIRY_BUFFER_SECONDS = 60L
        private const val APP_REMOTE_COMMAND_TIMEOUT_SECONDS = 4L

        @Volatile
        private var appRemote: SpotifyAppRemote? = null

        @Volatile
        private var cachedToken: SpotifyToken? = null
    }
}
