package com.cahdz.alexa

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.cahdz.alexa.debug.DebugLogStore
import com.cahdz.alexa.service.WakeWordService
import com.cahdz.alexa.service.WakeWordService.AssistantState
import com.cahdz.alexa.ui.AssistantScreen
import com.cahdz.alexa.ui.theme.AlexaTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var service: WakeWordService? = null
    private var bound by mutableStateOf(false)
    private val fallbackState = MutableStateFlow(AssistantState.IDLE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as WakeWordService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            startWakeWordService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AlexaTheme {
                val state by if (bound) {
                    service!!.state.collectAsState()
                } else {
                    fallbackState.collectAsState()
                }
                val wakeWordThreshold by if (bound) {
                    service!!.wakeWordThreshold.collectAsState()
                } else {
                    MutableStateFlow(0.50f).collectAsState()
                }
                val debugLogs by DebugLogStore.entries.collectAsState()

                AssistantScreen(
                    state = state,
                    wakeWordThreshold = wakeWordThreshold,
                    debugLogs = debugLogs,
                    onToggleListening = { toggleListening() },
                    onWakeWordThresholdChange = { threshold ->
                        service?.setWakeWordThreshold(threshold)
                    },
                    onClearDebugLogs = { DebugLogStore.clear() },
                    onCopyDebugLogs = { copyDebugLogs() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, WakeWordService::class.java)
        bindService(intent, connection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun toggleListening() {
        val currentState = service?.state?.value ?: AssistantState.IDLE

        if (currentState == AssistantState.IDLE) {
            if (hasAudioPermission()) {
                startWakeWordService()
            } else {
                requestPermissions()
            }
        } else {
            stopWakeWordService()
        }
    }

    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun stopWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_STOP
        }
        startService(intent)
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun copyDebugLogs() {
        val text = DebugLogStore.textSnapshot()
        if (text.isBlank()) {
            Toast.makeText(this, "No hay logs para copiar", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Alexa diagnostic logs", text))
        Toast.makeText(this, "Logs copiados", Toast.LENGTH_SHORT).show()
    }
}
