package com.cahdz.alexa.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cahdz.alexa.service.WakeWordService.AssistantState

@Composable
fun AssistantScreen(
    state: AssistantState,
    wakeWordThreshold: Float,
    debugLogs: List<String>,
    onToggleListening: () -> Unit,
    onWakeWordThresholdChange: (Float) -> Unit,
    onClearDebugLogs: () -> Unit,
    onCopyDebugLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor by animateColorAsState(
        targetValue = accentFor(state),
        label = "accentColor",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF06111F), Color(0xFF020617))
                )
            )
            .padding(28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Alexa",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = statusText(state),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFC8D7EA),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(46.dp))

            Box(contentAlignment = Alignment.Center) {
                if (state == AssistantState.USER_SPEAKING ||
                    state == AssistantState.THINKING ||
                    state == AssistantState.SPEAKING
                ) {
                    AnimatedAura(state = state, accentColor = accentColor)
                } else {
                    StaticAura(state = state, accentColor = accentColor)
                }

                FloatingActionButton(
                    onClick = onToggleListening,
                    modifier = Modifier.size(108.dp),
                    shape = CircleShape,
                    containerColor = Color(0xFF0F172A),
                    contentColor = accentColor,
                ) {
                    Icon(
                        imageVector = when (state) {
                            AssistantState.IDLE -> Icons.Default.MicOff
                            AssistantState.LISTENING -> Icons.Default.Mic
                            else -> Icons.Default.RecordVoiceOver
                        },
                        contentDescription = "Microfono",
                        modifier = Modifier.size(50.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(34.dp))

            Surface(
                color = Color.White.copy(alpha = 0.07f),
                shape = CircleShape,
            ) {
                Text(
                    text = actionText(state),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1),
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            WakeWordSensitivityControl(
                threshold = wakeWordThreshold,
                accentColor = accentColor,
                onThresholdChange = onWakeWordThresholdChange,
            )

            Spacer(modifier = Modifier.height(18.dp))

            DebugLogPanel(
                logs = debugLogs,
                onClear = onClearDebugLogs,
                onCopy = onCopyDebugLogs,
            )
        }
    }
}

@Composable
private fun WakeWordSensitivityControl(
    threshold: Float,
    accentColor: Color,
    onThresholdChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sensibilidad wake word",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE2E8F0),
            )
            Text(
                text = thresholdLabel(threshold),
                style = MaterialTheme.typography.bodyMedium,
                color = accentColor,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Slider(
            value = threshold,
            onValueChange = onThresholdChange,
            valueRange = 0.05f..0.95f,
            steps = 17,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Mas sensible",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
            )
            Text(
                text = "Menos falsos positivos",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
            )
        }
    }
}

@Composable
private fun DebugLogPanel(
    logs: List<String>,
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp),
        color = Color.Black.copy(alpha = 0.28f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Diagnostico",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE2E8F0),
                    fontWeight = FontWeight.SemiBold,
                )
                Row {
                    TextButton(
                        onClick = onCopy,
                        enabled = logs.isNotEmpty(),
                    ) {
                        Text(text = "Copiar")
                    }
                    TextButton(onClick = onClear) {
                        Text(text = "Limpiar")
                    }
                }
            }

            if (logs.isEmpty()) {
                Text(
                    text = "Sin eventos todavia",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                )
            } else {
                Column(
                    modifier = Modifier
                        .height(150.dp)
                        .verticalScroll(scrollState),
                ) {
                    logs.takeLast(60).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = logColor(line),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StaticAura(state: AssistantState, accentColor: Color) {
    Canvas(modifier = Modifier.size(236.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.29f
        val alpha = when (state) {
            AssistantState.IDLE -> 0.20f
            AssistantState.LISTENING -> 0.42f
            AssistantState.SESSION_ACTIVE -> 0.65f
            else -> 0.45f
        }

        drawCircle(
            color = accentColor.copy(alpha = alpha * 0.20f),
            radius = radius + 24.dp.toPx(),
            center = center,
        )
        drawCircle(
            color = accentColor.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx()),
        )
        if (state == AssistantState.SESSION_ACTIVE) {
            drawCircle(
                color = accentColor.copy(alpha = 0.25f),
                radius = radius + 18.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun AnimatedAura(state: AssistantState, accentColor: Color) {
    val transition = rememberInfiniteTransition(label = "activeAura")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "sweep",
    )

    Canvas(modifier = Modifier.size(236.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.29f

        drawCircle(
            color = accentColor.copy(alpha = 0.22f),
            radius = radius + 26.dp.toPx(),
            center = center,
        )
        drawCircle(
            color = accentColor.copy(alpha = 0.88f),
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx()),
        )

        when (state) {
            AssistantState.USER_SPEAKING -> repeat(3) { index ->
                val progress = (pulse + index * 0.28f) % 1f
                drawCircle(
                    color = Color(0xFF38BDF8).copy(alpha = (1f - progress) * 0.42f),
                    radius = radius + progress * 56.dp.toPx(),
                    center = center,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            AssistantState.THINKING -> repeat(3) { index ->
                drawArc(
                    color = listOf(Color(0xFFFBBF24), Color(0xFF22D3EE), Color(0xFFA78BFA))[index],
                    startAngle = sweep + index * 120f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius - 34.dp.toPx(), center.y - radius - 34.dp.toPx()),
                    size = Size((radius + 34.dp.toPx()) * 2f, (radius + 34.dp.toPx()) * 2f),
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            AssistantState.SPEAKING -> drawArc(
                color = Color(0xFF60A5FA).copy(alpha = 0.85f),
                startAngle = sweep,
                sweepAngle = 210f,
                useCenter = false,
                topLeft = Offset(center.x - radius - 30.dp.toPx(), center.y - radius - 30.dp.toPx()),
                size = Size((radius + 30.dp.toPx()) * 2f, (radius + 30.dp.toPx()) * 2f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )

            else -> Unit
        }
    }
}

private fun accentFor(state: AssistantState): Color {
    return when (state) {
        AssistantState.IDLE -> Color(0xFF64748B)
        AssistantState.LISTENING -> Color(0xFF22D3EE)
        AssistantState.SESSION_ACTIVE -> Color(0xFF22C55E)
        AssistantState.USER_SPEAKING -> Color(0xFF38BDF8)
        AssistantState.THINKING -> Color(0xFFFBBF24)
        AssistantState.SPEAKING -> Color(0xFF60A5FA)
    }
}

private fun statusText(state: AssistantState): String {
    return when (state) {
        AssistantState.IDLE -> "Inactiva"
        AssistantState.LISTENING -> "Esperando que digas \"Alexa\""
        AssistantState.SESSION_ACTIVE -> "Palabra clave detectada. Habla ahora"
        AssistantState.USER_SPEAKING -> "Te estoy escuchando"
        AssistantState.THINKING -> "Procesando tu solicitud"
        AssistantState.SPEAKING -> "Respondiendo"
    }
}

private fun actionText(state: AssistantState): String {
    return when (state) {
        AssistantState.IDLE -> "Toca para iniciar"
        AssistantState.LISTENING -> "Modo wake word activo"
        else -> "Toca para detener"
    }
}

private fun thresholdLabel(threshold: Float): String {
    return "%.2f".format(threshold)
}

private fun logColor(line: String): Color {
    return when {
        " E/" in line -> Color(0xFFFCA5A5)
        " W/" in line -> Color(0xFFFDE68A)
        else -> Color(0xFFCBD5E1)
    }
}
