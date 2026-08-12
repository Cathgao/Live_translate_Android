package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun WaveformVisualizer(
    isRecording: Boolean,
    volume: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    val barAmplitudes = remember { List(barCount) { Animatable(0.1f) } }

    LaunchedEffect(isRecording, volume) {
        if (isRecording) {
            barAmplitudes.forEachIndexed { index, animatable ->
                val randomFactor = 0.5f + Random.nextFloat() * 0.5f
                val target = (volume * randomFactor * (1f - (abs(index - barCount / 2f) / (barCount / 2f)) * 0.4f))
                    .coerceIn(0.08f, 1f)

                launch {
                    animatable.animateTo(
                        targetValue = target,
                        animationSpec = tween(
                            durationMillis = 80 + index * 5,
                            easing = FastOutSlowInEasing
                        )
                    )
                }
            }
        } else {
            barAmplitudes.forEach { animatable ->
                launch {
                    animatable.animateTo(
                        targetValue = 0.05f,
                        animationSpec = tween(durationMillis = 300)
                    )
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        val width = size.width
        val height = size.height
        val barWidth = 6.dp.toPx()
        val spacing = (width - (barCount * barWidth)) / (barCount + 1)

        val centerY = height / 2f

        barAmplitudes.forEachIndexed { index, animatable ->
            val amplitude = animatable.value
            val barHeight = (height * amplitude).coerceAtLeast(6.dp.toPx())
            val x = spacing + index * (barWidth + spacing)
            val y = centerY - (barHeight / 2f)

            drawRoundRect(
                color = if (isRecording) activeColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private fun abs(value: Float): Float = if (value < 0) -value else value
