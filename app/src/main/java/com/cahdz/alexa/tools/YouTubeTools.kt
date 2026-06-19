package com.cahdz.alexa.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class YouTubeTools(private val context: Context) {

    suspend fun play(query: String): String {
        return try {
            val searchUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (ytIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(ytIntent)
                "Buscando '$query' en YouTube"
            } else {
                context.startActivity(intent)
                "Abriendo '$query' en YouTube (navegador)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube play failed", e)
            "Error al abrir YouTube: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "YouTubeTools"
    }
}
