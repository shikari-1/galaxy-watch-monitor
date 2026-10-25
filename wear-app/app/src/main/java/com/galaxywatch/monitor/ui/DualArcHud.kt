package com.galaxywatch.monitor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.galaxywatch.monitor.data.HardwareTelemetry

/**
 * Premium Full-Screen Radial Dual Arc Cockpit HUD for Galaxy Watch 4 (Wear OS).
 * - Left Radial Arc: GPU Thermal Speedometer (Cyan -> Amber -> Red)
 * - Right Radial Arc: CPU Thermal Speedometer (Coral -> Orange -> Red)
 * - Bottom Rim Arc: System Memory Track (Purple)
 * - Top Stage: Breathing 24-Hour Digital Clock + Live Status Dot
 * - Center Stage: Balanced 3-Column Telemetry Dashboard (GPU | Power | CPU)
 * - Bottom Stage: Ultra-Compact Memory & VRAM Status Bars
 */
@Composable
fun DualArcHudScreen(
    telemetry: HardwareTelemetry,
    modifier: Modifier = Modifier
) {
    // Smooth transition animations for gauges (1s duration for 1Hz telemetry)
    val animatedGpuTemp by animateFloatAsState(
        targetValue = telemetry.gpuTemp.toFloat(),
        animationSpec = tween(750, easing = FastOutSlowInEasing),
        label = "GpuTempAnim"
    )
    val animatedCpuTemp by animateFloatAsState(
        targetValue = telemetry.cpuTemp.toFloat(),
        animationSpec = tween(750, easing = FastOutSlowInEasing),
        label = "CpuTempAnim"
    )

    // Breathing 24-Hour Clock Animation
    val infiniteTransition = rememberInfiniteTransition(label = "ClockPulse")
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

    // Dynamic warning colors based on temperature
    val gpuColor = when {
        telemetry.gpuTemp >= 83 -> CritRed
        telemetry.gpuTemp >= 75 -> WarnAmber
        else -> GpuCyan
    }
    val cpuColor = when {
        telemetry.cpuTemp >= 85 -> CritRed
        telemetry.cpuTemp >= 75 -> CritOrange
        else -> CpuCoral
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack),
        contentAlignment = Alignment.Center
    ) {
        // =====================================================================
        // LAYER 1: Full-Screen Radial Speedometer Arcs (Canvas)
        // =====================================================================
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val strokeWidth = 8.dp.toPx()
            val arcSize = Size(size.width, size.height)

            // --- Left Arc: GPU Thermal Speedometer ---
            // Sweeps from bottom-left (100°) up through left (180°) to top-left (260°)
            // Total sweep angle = 160°
            val gpuTrackStart = 100f
            val gpuTotalSweep = 160f
            val gpuFraction = (animatedGpuTemp / 90f).coerceIn(0f, 1f)

            // GPU Background Track
            drawArc(
                color = TrackBackground,
                startAngle = gpuTrackStart,
                sweepAngle = gpuTotalSweep,
                useCenter = false,
                topLeft = Offset.Zero,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active GPU Progress (Climbs up the left side)
            if (gpuFraction > 0.02f) {
                drawArc(
                    color = gpuColor,
                    startAngle = gpuTrackStart,
                    sweepAngle = gpuTotalSweep * gpuFraction,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // --- Right Arc: CPU Thermal Speedometer ---
            // Sweeps from bottom-right (80°) up through right (0°) to top-right (-80° / 280°)
            // Total sweep angle = -160°
            val cpuTrackStart = 80f
            val cpuTotalSweep = -160f
            val cpuFraction = (animatedCpuTemp / 90f).coerceIn(0f, 1f)

            // CPU Background Track
            drawArc(
                color = TrackBackground,
                startAngle = cpuTrackStart,
                sweepAngle = cpuTotalSweep,
                useCenter = false,
                topLeft = Offset.Zero,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active CPU Progress (Climbs up the right side)
            if (cpuFraction > 0.02f) {
                drawArc(
                    color = cpuColor,
                    startAngle = cpuTrackStart,
                    sweepAngle = cpuTotalSweep * cpuFraction,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // =====================================================================
        // LAYER 2: Foreground Cockpit Telemetry Layout
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // --- 1. Top Section: Breathing 24-Hour Digital Clock + Live Dot ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                // Live Status Indicator Pulse
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (telemetry.isOnline) Color(0xFF00FF66) else Color(0x55888888))
                )
                Spacer(modifier = Modifier.width(6.dp))
                // 24H IST Clock
                Text(
                    text = telemetry.formattedIstTime,
                    color = TextWhite.copy(alpha = clockAlpha),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.scale(clockScale)
                )
            }

            // --- 2. Middle Section: Balanced 3-Column Cockpit (GPU | PWR | CPU) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // --- Left Column: GPU Cluster ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    // Tag Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(GpuCyan.copy(alpha = 0.22f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "GPU",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GpuCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    // Temperature
                    Text(
                        text = "${telemetry.gpuTemp}°",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = gpuColor
                    )
                    // Usage Load
                    Text(
                        text = "LOAD ${telemetry.gpuUsage}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                    // VRAM
                    Text(
                        text = "${telemetry.vramUsedGb}G",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = GpuCyan.copy(alpha = 0.85f)
                    )
                }

                // --- Center Column: Power & Divider ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(Color(0x33FFFFFF))
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${telemetry.gpuPowerW}W",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarnAmber
                    )
                    Text(
                        text = "PWR",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(Color(0x33FFFFFF))
                    )
                }

                // --- Right Column: CPU Cluster ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    // Tag Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CpuCoral.copy(alpha = 0.22f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "CPU",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CpuCoral
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    // Temperature
                    Text(
                        text = "${telemetry.cpuTemp}°",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = cpuColor
                    )
                    // Usage Load
                    Text(
                        text = "LOAD ${telemetry.cpuUsage}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                    // RAM
                    Text(
                        text = "${telemetry.ramUsedGb}G",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = RamPurple.copy(alpha = 0.90f)
                    )
                }
            }

            // --- 3. Bottom Section: Compact Horizontal Memory & VRAM Progress Bars ---
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // VRAM Bar
                MiniProgressBar(
                    label = "VRAM",
                    percent = telemetry.vramPercent,
                    color = GpuCyan
                )
                // RAM Bar
                MiniProgressBar(
                    label = "RAM",
                    percent = telemetry.ramPercent,
                    color = RamPurple
                )
            }
        }
    }
}

/**
 * Compact memory status row with pill progress bar.
 */
@Composable
private fun MiniProgressBar(
    label: String,
    percent: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            modifier = Modifier.width(30.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x22FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$percent%",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
            textAlign = TextAlign.End,
            modifier = Modifier.width(26.dp)
        )
    }
}
