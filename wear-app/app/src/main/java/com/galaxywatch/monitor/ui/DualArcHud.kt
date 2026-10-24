package com.galaxywatch.monitor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * Native Dual Arc Speedometer HUD for Galaxy Watch 4 (Wear OS).
 * Renders at full 60fps in rich AMOLED color 24/7 without monochrome drop.
 */
@Composable
fun DualArcHudScreen(
    telemetry: HardwareTelemetry,
    modifier: Modifier = Modifier
) {
    // Breathing 24-Hour Clock Animation
    val infiniteTransition = rememberInfiniteTransition(label = "ClockBreathing")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
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

    // When offline, freeze breathing and dim the digits
    val clockAlpha = if (telemetry.isOnline) breathingAlpha else 0.45f
    val clockScale = if (telemetry.isOnline) breathingScale else 1.0f

    val gpuColor = getProgressiveColor(telemetry.gpuTemp * 100 / 90, TextWhite)
    val cpuColor = getProgressiveColor(telemetry.cpuTemp * 100 / 90, TextWhite)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack),
        contentAlignment = Alignment.Center
    ) {
        // --- 1. Top Breathing 24-Hour Digital Clock ---
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = telemetry.formattedIstTime,
                color = TextWhite.copy(alpha = clockAlpha),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                modifier = Modifier.scale(clockScale)
            )
        }

        // --- 2. Central Dual Speedometer Gauge Arcs (GPU & CPU) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 42.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: GPU Speedometer Arc
            SpeedometerGauge(
                title = "GPU",
                value = telemetry.gpuTemp,
                maxVal = 90,
                color = gpuColor,
                unit = "°",
                modifier = Modifier.size(160.dp)
            )

            // Right: CPU Speedometer Arc
            SpeedometerGauge(
                title = "CPU",
                value = telemetry.cpuTemp,
                maxVal = 90,
                color = cpuColor,
                unit = "°",
                modifier = Modifier.size(160.dp)
            )
        }

        // --- 3. Bottom Memory Readouts: VRAM & RAM ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
                .width(260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // VRAM Progress Bar
            MemoryBarSegment(
                label = "VRAM",
                currentGb = telemetry.vramUsedGb,
                totalGb = telemetry.vramTotalGb,
                percent = telemetry.vramPercent
            )

            // RAM Progress Bar
            MemoryBarSegment(
                label = "RAM",
                currentGb = telemetry.ramUsedGb,
                totalGb = telemetry.ramTotalGb,
                percent = telemetry.ramPercent
            )
        }
    }
}

@Composable
fun SpeedometerGauge(
    title: String,
    value: Int,
    maxVal: Int,
    color: Color,
    unit: String,
    modifier: Modifier = Modifier
) {
    val progress = (value.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
    val startAngle = 140f
    val sweepAngle = 260f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val strokeWidth = 14.dp.toPx()
            val arcSize = Size(size.width, size.height)

            // Background Track
            drawArc(
                color = TrackBackground,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset.Zero,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active Filled Gauge
            if (progress > 0.01f) {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle * progress,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Center Digital Readout
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                letterSpacing = 1.sp
            )
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "$value",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = unit,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = color.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun MemoryBarSegment(
    label: String,
    currentGb: Float,
    totalGb: Float,
    percent: Int,
    modifier: Modifier = Modifier
) {
    val barColor = getProgressiveColor(percent, TextWhite)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "%.1f / %.1fG (%d%%)".format(currentGb, totalGb, percent),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = barColor
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Progress Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .background(TrackBackground, RoundedCornerShape(999.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(barColor, RoundedCornerShape(999.dp))
            )
        }
    }
}
