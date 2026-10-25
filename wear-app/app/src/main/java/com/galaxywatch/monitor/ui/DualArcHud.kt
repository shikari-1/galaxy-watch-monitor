package com.galaxywatch.monitor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.galaxywatch.monitor.data.HardwareTelemetry

/**
 * 1:1 Faithful Native Recreation of Web Companion "faceArc" (Dual Arc HUD).
 * - Top: Breathing 24-Hour Digital Clock
 * - Center: Dual Speedometer Arcs (GPU on left, CPU on right, 140° -> 40° sweep)
 * - Bottom: Dual Horizontal Progress Bars (VRAM & RAM with thermal gradient fill)
 */
@Composable
fun DualArcHudScreen(
    telemetry: HardwareTelemetry,
    modifier: Modifier = Modifier
) {
    // Breathing 24-Hour Clock Animation matching web keyframes
    val infiniteTransition = rememberInfiniteTransition(label = "WebClockBreathe")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ClockAlpha"
    )
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ClockScale"
    )

    val clockAlpha = if (telemetry.isOnline) breathingAlpha else 0.40f
    val clockScale = if (telemetry.isOnline) breathingScale else 1.0f

    // Smooth gauge transitions
    val animatedGpuTemp by animateFloatAsState(
        targetValue = telemetry.gpuTemp.toFloat(),
        animationSpec = tween(750, easing = FastOutSlowInEasing),
        label = "GpuTemp"
    )
    val animatedCpuTemp by animateFloatAsState(
        targetValue = telemetry.cpuTemp.toFloat(),
        animationSpec = tween(750, easing = FastOutSlowInEasing),
        label = "CpuTemp"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // =================================================================
            // 1. TOP BREATHING 24-HOUR DIGITAL CLOCK (.hud-top-clock)
            // =================================================================
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .scale(clockScale),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = telemetry.formattedIstTime,
                    color = Color.White.copy(alpha = clockAlpha),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )
            }

            // =================================================================
            // 2. CENTRAL DUAL SPEEDOMETER GAUGES (.hud-gauges-container)
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // --- Left: GPU Gauge Box (.hud-gauge-box) ---
                WebSpeedometerDial(
                    tag = "GPU",
                    temp = animatedGpuTemp.toInt(),
                    maxVal = 90,
                    modifier = Modifier.size(98.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // --- Right: CPU Gauge Box (.hud-gauge-box) ---
                WebSpeedometerDial(
                    tag = "CPU",
                    temp = animatedCpuTemp.toInt(),
                    maxVal = 90,
                    modifier = Modifier.size(98.dp)
                )
            }

            // =================================================================
            // 3. BOTTOM MEMORY SECTION (.hud-bottom-bar)
            // =================================================================
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // VRAM Segment
                WebMemoryBar(
                    label = "VRAM",
                    valueStr = "${telemetry.vramUsedGb} / ${telemetry.vramTotalGb}G (${telemetry.vramPercent}%)",
                    percent = telemetry.vramPercent
                )

                // RAM Segment
                WebMemoryBar(
                    label = "RAM",
                    valueStr = "${telemetry.ramUsedGb} / ${telemetry.ramTotalGb}G (${telemetry.ramPercent}%)",
                    percent = telemetry.ramPercent
                )
            }
        }
    }
}

/**
 * Speedometer Dial matching web `.hud-gauge-box` + SVG `M 20 135 A 72 72 0 1 1 140 135`.
 */
@Composable
private fun WebSpeedometerDial(
    tag: String,
    temp: Int,
    maxVal: Int,
    modifier: Modifier = Modifier
) {
    val progress = (temp.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
    val startAngle = 140f
    val sweepAngle = 260f

    // Thermal color gradient matching web #hudMeterGrad
    val gaugeColor = when {
        temp >= 83 -> Color(0xFFFF0022) // Crit Red
        temp >= 75 -> Color(0xFFFF5500) // Crit Orange
        temp >= 68 -> Color(0xFFFFB020) // Warn Amber
        else -> Color.White             // Pure White
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Speedometer Arc Track
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val strokeWidth = 8.dp.toPx()
            val arcSize = Size(size.width, size.height)

            // Background arc (.arc-bg: rgba(255, 255, 255, 0.08))
            drawArc(
                color = Color(0x1FFFFFFF),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset.Zero,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active filled arc (.arc-fg)
            if (progress > 0.01f) {
                drawArc(
                    color = gaugeColor,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle * progress,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Center Readout (.hud-arc-center)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            // Tag (.hud-tag)
            Text(
                text = tag,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Color.White
            )

            // Temperature Value + Degree (.hud-temp-wrap)
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$temp",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    lineHeight = 32.sp
                )
                Text(
                    text = "°",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xE6FFFFFF),
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(start = 1.dp)
                )
            }
        }
    }
}

/**
 * Memory Segment matching web `.hud-bar-segment`.
 */
@Composable
private fun WebMemoryBar(
    label: String,
    valueStr: String,
    percent: Int,
    modifier: Modifier = Modifier
) {
    // Thermal gradient matching web `.bar-fill`
    val fillBrush = Brush.horizontalGradient(
        colors = listOf(
            Color.White,
            Color.White,
            Color(0xFFFFB020), // 70% Amber
            Color(0xFFFF5500), // 82% Orange
            Color(0xFFFF0022)  // 90%+ Red
        )
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Metadata Row (.hud-bar-meta)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Label (.bar-lbl)
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = Color.White
            )

            // Value (.bar-val)
            Text(
                text = valueStr,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE5E7EB)
            )
        }

        // Mini Progress Bar (.mini-progress)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color(0x22FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fillBrush)
            )
        }
    }
}
