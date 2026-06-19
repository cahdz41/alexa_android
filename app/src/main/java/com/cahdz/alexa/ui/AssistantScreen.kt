package com.cahdz.alexa.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cahdz.alexa.service.WakeWordService.AssistantState

@Composable
fun AssistantScreen(
    state: AssistantState,
    onToggleListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state != AssistantState.IDLE

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == AssistantState.SESSION_ACTIVE) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            AssistantState.IDLE -> Color.Gray
            AssistantState.LISTENING -> MaterialTheme.colorScheme.primary
            AssistantState.SESSION_ACTIVE -> Color(0xFF4CAF50)
            AssistantState.THINKING -> Color(0xFFFFC107)
            AssistantState.SPEAKING -> Color(0xFF4CAF50)
        },
        label = "ringColor",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Alexa",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when (state) {
                AssistantState.IDLE -> "Toca para empezar a escuchar"
                AssistantState.LISTENING -> "Escuchando... di \"Alexa\""
                AssistantState.SESSION_ACTIVE -> "Sesión activa — habla"
                AssistantState.THINKING -> "Pensando..."
                AssistantState.SPEAKING -> "Respondiendo..."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(64.dp))

        Box(contentAlignment = Alignment.Center) {
            // Pulse ring
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(pulseScale)
                        .background(
                            color = ringColor.copy(alpha = 0.2f),
                            shape = CircleShape,
                        )
                )
            }

            FloatingActionButton(
                onClick = onToggleListening,
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                containerColor = if (isActive) ringColor else MaterialTheme.colorScheme.surface,
                contentColor = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    imageVector = when (state) {
                        AssistantState.IDLE -> Icons.Default.MicOff
                        AssistantState.SESSION_ACTIVE,
                        AssistantState.THINKING,
                        AssistantState.SPEAKING -> Icons.Default.RecordVoiceOver
                        else -> Icons.Default.Mic
                    },
                    contentDescription = "Micrófono",
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isActive) {
            Text(
                text = "Toca para detener",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}
