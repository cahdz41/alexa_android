package com.cahdz.alexa.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.rementia.openwakeword.OpenWakeWord
import xyz.rementia.openwakeword.WakeWordEvent

class WakeWordDetector(
    private val context: Context,
    private val modelAssetPath: String = "alexa.onnx",
    private val threshold: Float = 0.5f,
    private val onWakeWord: () -> Unit,
) {
    private var wakeWord: OpenWakeWord? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        val oww = OpenWakeWord.Builder(context)
            .addModelAsset(modelAssetPath)
            .setThreshold(threshold)
            .build()

        wakeWord = oww

        job = scope.launch(Dispatchers.Default) {
            oww.startListening()
            oww.events.collectLatest { event ->
                when (event) {
                    is WakeWordEvent.Detected -> {
                        Log.i(TAG, "Wake word detected: ${event.modelName} (score=${event.score})")
                        onWakeWord()
                    }
                    is WakeWordEvent.Error -> {
                        Log.e(TAG, "Wake word error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }

        Log.i(TAG, "Wake word detector started (model=$modelAssetPath, threshold=$threshold)")
    }

    fun stop() {
        job?.cancel()
        job = null
        wakeWord?.stopListening()
        wakeWord?.close()
        wakeWord = null
        Log.i(TAG, "Wake word detector stopped")
    }

    val isListening: Boolean
        get() = job?.isActive == true

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
