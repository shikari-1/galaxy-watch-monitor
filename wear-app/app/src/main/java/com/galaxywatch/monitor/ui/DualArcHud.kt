package com.galaxywatch.monitor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
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
import com.galaxywatch.monitor.data.HardwareTelemetry
import androidx.wear.compose.material.Text
import kotlin.math.min

/**
 * 1:1 Recreation of Web Companion "faceArc" (Dual Arc HUD) for Wear OS.
 * Features:
 * - Top: Breathing 24-Hour Digital Clock with Seconds ("HH:MM:SS" IST)
 * - Center: Dual Perfectly Circular Speedometer Arcs (GPU & CPU with 140° -> 40° sweep)
 * - Bottom: Dual Horizontal Memory Bars (VRAM & RAM with full-track thermal gradient)
 */
@Composable
fun DualArcHudScreen(
    telemetry: HardwareTelemetry,
    modifier: Modifier = Modifier
) {
    // Breathing 24-Hour Clock Animation matching web keyframes
    val infiniteTransition = rememberInfiniteTransition(label = "WebClockBreathe")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.60f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ClockAlpha"
    )
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ClockScale"
    )

    // Smooth online/offline alpha without harsh snapping
    val clockAlpha by animateFloatAsState(
        targetValue = if (telemetry.isOnline) breathingAlpha else 0.40f,
        animationSpec = tween(600),
        label = "SmoothClockAlpha"
    )
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
            // 1. TOP BREATHING 24-HOUR DIGITAL CLOCK WITH SECONDS (.hud-top-clock)
            // =================================================================
            var localTimeStr by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while (true) {
                    val now = java.time.LocalTime.now()
                    localTimeStr = "%02d:%02d:%02d".format(now.hour, now.minute, now.second)
                    kotlinx.coroutines.delay(500)
                }
            }
            val activeClockText = if (telemetry.isOnline && telemetry.lastUpdateTimestamp > 0) {
                telemetry.formattedIstTime
            } else {
                localTimeStr
            }

            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .scale(clockScale),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (telemetry.isOnline) Color(0xFF00E676) else Color(0xFFFF9100))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = activeClockText,
                        color = Color.White.copy(alpha = clockAlpha),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // =================================================================
            // 2. CENTRAL DUAL SPEEDOMETER GAUGES (.hud-gauges-container)
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // --- Left: GPU Gauge Box (Sweeps Outer Left) ---
                WebSpeedometerDial(
                    tag = "GPU",
                    temp = animatedGpuTemp.toInt(),
                    subText = "${telemetry.gpuUsage}% · ${telemetry.gpuPowerW}W",
                    maxVal = 90,
                    isMirrored = false,
                    modifier = Modifier.size(94.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // --- Right: CPU Gauge Box (Sweeps Mirrored Outer Right) ---
                WebSpeedometerDial(
                    tag = "CPU",
                    temp = animatedCpuTemp.toInt(),
                    subText = "${telemetry.cpuUsage}%",
                    maxVal = 90,
                    isMirrored = true,
                    modifier = Modifier.size(94.dp)
                )
            }

            // =================================================================
            // 3. BOTTOM MEMORY SECTION (.hud-bottom-bar)
            // =================================================================
            Column(
                modifier = Modifier
                    .width(168.dp)
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
 * Enforces a strict 1:1 equilateral square so the arc is perfectly circular.
 */
@Composable
private fun WebSpeedometerDial(
    tag: String,
    temp: Int,
    subText: String,
    maxVal: Int,
    isMirrored: Boolean = false,
    modifier: Modifier = Modifier
) {
    val progress = (temp.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
    val startAngle = if (!isMirrored) 140f else 40f
    val sweepAngle = if (!isMirrored) 260f else -260f

    // Thermal color gradient matching web #hudMeterGrad
    val gaugeColor = when {
        temp >= 83 -> Color(0xFFFF0022) // Crit Red
        temp >= 75 -> Color(0xFFFF5500) // Crit Orange
        temp >= 68 -> Color(0xFFFFB020) // Warn Amber
        else -> Color.White             // Pure White
    }

    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        // Speedometer Arc Track (enforcing 1:1 circle diameter)
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val strokeWidth = 8.dp.toPx()
            val diameter = min(size.width, size.height) - strokeWidth
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            // Background arc (.arc-bg: rgba(255, 255, 255, 0.08))
            drawArc(
                color = Color(0x1FFFFFFF),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
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
                    topLeft = topLeft,
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
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = Color.White
            )

            // Temperature Value + Degree (.hud-temp-wrap)
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$temp",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    lineHeight = 28.sp
                )
                Text(
                    text = "°",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xE6FFFFFF),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 1.dp)
                )
            }

            // Sub-metric: Load % and Power
            Text(
                text = subText,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFB0B0B0)
            )
        }
    }
}

/**
 * Memory Segment matching web `.hud-bar-segment` and CSS `.bar-fill`.
 * Rounded pill clipping prevents flat cutoffs.
 */
@Composable
private fun WebMemoryBar(
    label: String,
    valueStr: String,
    percent: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Metadata Row (.hud-bar-meta)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White
            )

            Text(
                text = valueStr,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE5E7EB)
            )
        }

        val webGradient = Brush.horizontalGradient(
            0.00f to Color.White,
            0.55f to Color.White,
            0.70f to Color(0xFFFFB020),
            0.82f to Color(0xFFFF5500),
            0.90f to Color(0xFFFF0022),
            1.00f to Color(0xFFFF0022)
        )

        // Pill clipped progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                .background(Color(0x22FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                    .background(webGradient)
            )
        }
    }
}
