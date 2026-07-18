package com.mp3downloader.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SplashRed = Color(0xFFFF0000)
private val SplashBg = Color(0xFF121212)
private val SplashWhite = Color(0xFFFFFFFF)

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    val glowScale = remember { Animatable(0f) }
    val ringScale = remember { Animatable(0f) }
    val noteAlpha = remember { Animatable(0f) }
    val noteScale = remember { Animatable(0f) }
    val stemLen = remember { Animatable(0f) }
    val arrowY = remember { Animatable(-20f) }
    val arrowAlpha = remember { Animatable(0f) }
    val lineLen = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }
    val fadeOut = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            // Phase 1: glow + ring expand simultaneously (0-400ms)
            launch {
                glowScale.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            }
            launch {
                ringScale.animateTo(1f, tween(350, 50, easing = FastOutSlowInEasing))
            }

            // Phase 2: note head pops in (300ms)
            launch {
                noteAlpha.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
                noteScale.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
            }

            // Phase 3: stem draws up (250ms)
            launch {
                delay(200)
                stemLen.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
            }

            // Phase 4: arrow drops + line draws together (250ms)
            launch {
                delay(350)
                arrowAlpha.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
                arrowY.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
            }
            launch {
                delay(400)
                lineLen.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            }

            // Pulse while loading
            launch {
                while (true) {
                    pulse.animateTo(0.6f, tween(500, easing = LinearEasing))
                    pulse.animateTo(1f, tween(500, easing = LinearEasing))
                }
            }

            // Hold ~600ms total from start
            delay(600)

            // Quick fade out
            fadeOut.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
            delay(50)
            onSplashFinished()
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBg)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(size.width, size.height) * 0.16f

        withTransform(transformBlock = { translate(left = cx, top = cy) }) {
            val a = fadeOut.value

            // Glow
            drawCircle(
                color = SplashRed.copy(alpha = 0.12f * pulse.value * a),
                radius = r * 1.3f * glowScale.value
            )

            // Ring
            drawCircle(
                color = SplashRed.copy(alpha = 0.25f * a),
                radius = r * ringScale.value,
                style = Stroke(width = r * 0.1f)
            )

            // Note head
            drawCircle(
                color = SplashRed.copy(alpha = noteAlpha.value * a),
                radius = r * 0.34f * noteScale.value,
                center = Offset(-r * 0.1f, r * 0.1f)
            )

            // Stem
            val sy = -r * 0.04f
            val ey = -r * 0.82f
            drawLine(
                color = SplashRed.copy(alpha = a),
                start = Offset(r * 0.2f, sy),
                end = Offset(r * 0.2f, sy + (ey - sy) * stemLen.value),
                strokeWidth = r * 0.07f,
                cap = StrokeCap.Round
            )

            // Arrow
            if (arrowAlpha.value > 0f) {
                val top = r * 0.36f + arrowY.value
                val bot = top + r * 0.32f
                val hw = r * 0.2f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, bot)
                    lineTo(-hw, top)
                    lineTo(hw, top)
                    close()
                }
                drawPath(path, SplashWhite.copy(alpha = arrowAlpha.value * a))
            }

            // Base line
            if (lineLen.value > 0f) {
                val ly = r * 0.78f
                val lw = r * 0.32f * lineLen.value
                drawLine(
                    color = SplashWhite.copy(alpha = lineLen.value * a),
                    start = Offset(-lw, ly),
                    end = Offset(lw, ly),
                    strokeWidth = r * 0.05f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
