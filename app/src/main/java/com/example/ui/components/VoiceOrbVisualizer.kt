package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonIndigo
import kotlin.math.sin

@Composable
fun VoiceOrbVisualizer(
    isListening: Boolean,
    isProcessing: Boolean,
    isSpeaking: Boolean,
    rmsLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    // Breathing pulse animation
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Waveform rotation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val activeColor = when {
        isProcessing -> ElectricViolet
        isListening -> CyanPrimary
        isSpeaking -> NeonIndigo
        else -> CyanPrimary.copy(alpha = 0.6f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .testTag("voice_orb_button")
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 110.dp),
                    onClick = onClick
                )
        ) {
            // Animated Canvas drawing ripple rings and waveform frequencies
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension / 3.4f
                val dynamicRadius = if (isListening) {
                    baseRadius + (rmsLevel * 28f)
                } else if (isSpeaking) {
                    baseRadius * pulseScale
                } else {
                    baseRadius * (if (isProcessing) pulseScale else 0.95f)
                }

                // Outer Ripple 1
                if (isListening || isSpeaking || isProcessing) {
                    drawCircle(
                        color = activeColor.copy(alpha = 0.15f),
                        radius = dynamicRadius * 1.5f,
                        center = center
                    )
                    drawCircle(
                        color = activeColor.copy(alpha = 0.25f),
                        radius = dynamicRadius * 1.25f,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // AI Radial Orb Core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            activeColor,
                            NeonIndigo.copy(alpha = 0.8f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = dynamicRadius
                    ),
                    radius = dynamicRadius,
                    center = center
                )

                // Soundwave Bars reacting to audio level around orb
                val barCount = 24
                for (i in 0 until barCount) {
                    val angle = Math.toRadians((i * (360.0 / barCount) + rotation))
                    val barHeight = if (isListening) {
                        (rmsLevel * 30f * (0.4f + 0.6f * sin((i * 1.2)).toFloat().coerceAtLeast(0.1f))) + 6f
                    } else if (isSpeaking) {
                        (14f * sin((i * 0.8 + rotation * 0.1).toFloat()).coerceAtLeast(0.2f)) + 8f
                    } else {
                        4f
                    }

                    val startX = (center.x + (dynamicRadius + 4f) * kotlin.math.cos(angle)).toFloat()
                    val startY = (center.y + (dynamicRadius + 4f) * sin(angle)).toFloat()
                    val endX = (center.x + (dynamicRadius + 4f + barHeight) * kotlin.math.cos(angle)).toFloat()
                    val endY = (center.y + (dynamicRadius + 4f + barHeight) * sin(angle)).toFloat()

                    drawLine(
                        color = activeColor.copy(alpha = 0.85f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 3.dp.toPx()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // State indicator text
        val statusText = when {
            isListening -> "Listening to your voice..."
            isProcessing -> "Processing command..."
            isSpeaking -> "Speaking response..."
            else -> "Tap the AI Orb to speak"
        }

        Text(
            text = statusText,
            color = if (isListening) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = if (isListening) FontWeight.Bold else FontWeight.Medium
        )
    }
}
