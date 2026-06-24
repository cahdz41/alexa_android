package com.cahdz.alexa.wakeword

import android.content.Context
import android.util.Log
import com.cahdz.alexa.debug.DebugLogStore
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WakeWordDetector(
    private val context: Context,
    private val modelAssetPath: String = "alexa_v0.1.onnx",
    private val threshold: Float = 0.10f,
    private val onWakeWord: () -> Unit,
) {
    private var engine: WakeWordEngine? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (isListening) return

        val models = listOf(
            WakeWordModel("alexa", modelAssetPath, threshold)
        )

        val wakeWordEngine = WakeWordEngine(
            context = context,
            models = models,
            detectionMode = DetectionMode.SINGLE_BEST,
            detectionCooldownMs = 2000L,
        )

        engine = wakeWordEngine
        wakeWordEngine.start()

        job = scope.launch(Dispatchers.Default) {
            wakeWordEngine.detections.collectLatest { detection ->
                DebugLogStore.i(TAG, "Wake word detected: ${detection.model.name} (score=${detection.score})")
                onWakeWord()
            }
        }

        DebugLogStore.i(TAG, "Wake word detector started (model=$modelAssetPath, threshold=$threshold)")
    }

    fun stop() {
        job?.cancel()
        job = null
        engine?.stop()
        engine?.release()
        engine = null
        DebugLogStore.i(TAG, "Wake word detector stopped")
    }

    val isListening: Boolean
        get() = job?.isActive == true

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
