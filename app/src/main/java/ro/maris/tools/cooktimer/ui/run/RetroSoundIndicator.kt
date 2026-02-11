package ro.maris.tools.cooktimer.ui.run

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ro.maris.tools.cooktimer.audio.SoundDetectorState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val RetroSegmentCount = 18
private const val RetroFlashDurationMs = 150L
private const val RetroRejectionMessageMs = 2000L
private const val RetroDetectionHoldShortMs = 350L
private const val RetroDetectionHoldLongMs = 900L
private const val RetroDetectionAttackMs = 80
private const val RetroDetectionDecayMs = 220
private const val RetroVuDecayMs = 70
private const val RetroGapScale = 0.65f
private val RetroGreen = Color(0xFF29C16F)
private val RetroBlue = Color(0xFF374CFF)
private val RetroBlueFlash = Color(0xFF6B7BFF)

@Composable
fun RetroArcSoundIndicator(
    state: SoundDetectorState,
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 80.dp,
    isPaused: Boolean = false
) {
    val baseThreshold = 0.035f
    val multiplier = 4.0f
    val computedThreshold = (state.noiseFloor * multiplier).coerceAtLeast(baseThreshold)
    val vuTarget = (state.currentRms / computedThreshold).coerceIn(0f, 1f)
    val vuProgress = rememberAttackDecayProgress(
        target = if (state.isLit) 1f else vuTarget
    )

    val detectionAnim = remember { Animatable(0f) }
    var detectionActive by remember { mutableStateOf(false) }
    var pulseToken by remember { mutableStateOf(0) }

    var flashOn by remember { mutableStateOf(false) }
    var flashToken by remember { mutableStateOf(0) }

    suspend fun triggerDetectionPulse(holdMs: Long) {
        pulseToken += 1
        val myToken = pulseToken
        detectionActive = true
        try {
            detectionAnim.stop()
            detectionAnim.snapTo(0f)
            val target = 1f
            detectionAnim.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = RetroDetectionAttackMs, easing = LinearEasing)
            )
            delay(holdMs)
            detectionAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = RetroDetectionDecayMs, easing = LinearOutSlowInEasing)
            )
        } finally {
            withContext(NonCancellable) {
                if (pulseToken == myToken) {
                    detectionAnim.snapTo(0f)
                    detectionActive = false
                }
            }
        }
    }

    LaunchedEffect(state.count) {
        if (state.count > 0) {
            triggerDetectionPulse(RetroDetectionHoldLongMs)
        }
    }

    LaunchedEffect(state.lastRejectionReason) {
        if (state.lastRejectionReason != null) {
            triggerDetectionPulse(RetroDetectionHoldShortMs)
        }
    }

    LaunchedEffect(isPaused) {
        if (isPaused) {
            flashOn = false
            detectionAnim.stop()
            detectionAnim.snapTo(0f)
            detectionActive = false
        }
    }
    LaunchedEffect(state.count) {
        if (state.count > 0) {
            flashToken += 1
            val myToken = flashToken
            flashOn = true
            try {
                delay(RetroFlashDurationMs)
            } finally {
                if (flashToken == myToken) {
                    flashOn = false
                }
            }
        }
    }

    val detectionProgress = if (flashOn) {
        1f
    } else if (detectionActive) {
        detectionAnim.value.coerceIn(0f, 1f)
    } else {
        0f
    }
    val micAlpha = (0.35f + (vuProgress * 0.65f)).coerceIn(0.35f, 1f)

    val errorMessage = remember(state.lastRejectionReason) {
        state.lastRejectionReason?.let { getFriendlyRejectionMessage(it) }
    }
    var showError by remember { mutableStateOf(false) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            showError = true
            delay(RetroRejectionMessageMs)
            showError = false
        }
    }
    val showWarning = showError

    BoxWithConstraints(modifier = modifier.height(indicatorSize)) {
        val resolvedIndicatorSize = (maxWidth * 0.22f).coerceIn(56.dp, indicatorSize)

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                HexArcIndicator(
                    size = resolvedIndicatorSize,
                    segmentCount = RetroSegmentCount,
                    progress = vuProgress,
                    activeColor = RetroGreen,
                    inactiveAlpha = 0.12f,
                    startAngleDeg = 90f,
                    sweepAngleDeg = 180f,
                    coreScale = 0.58f
                ) { coreSize ->
                    val iconSize = coreSize * 0.45f
                    Box(
                        modifier = Modifier
                            .size(coreSize)
                            .border(2.dp, RetroGreen.copy(alpha = 0.7f), CircleShape)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showWarning) Icons.Default.Warning else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (showWarning) MaterialTheme.colorScheme.error else RetroGreen.copy(alpha = micAlpha),
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                if (showError && errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                HexArcIndicator(
                    size = resolvedIndicatorSize,
                    segmentCount = RetroSegmentCount,
                    progress = detectionProgress,
                    activeColor = RetroBlue,
                    inactiveAlpha = 0.12f,
                    startAngleDeg = -90f,
                    sweepAngleDeg = 180f,
                    coreScale = 0.58f
                ) { coreSize ->
                    DetectionCore(
                        size = coreSize,
                        progress = detectionProgress,
                        flashOn = flashOn,
                        borderColor = RetroBlue,
                        fillColor = RetroBlueFlash,
                        backgroundColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
    }
}

@Composable
private fun HexArcIndicator(
    size: Dp,
    segmentCount: Int,
    progress: Float,
    activeColor: Color,
    inactiveAlpha: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
    coreScale: Float,
    centerContent: @Composable BoxScope.(Dp) -> Unit
) {
    val coreSize = size * coreScale
    Box(modifier = Modifier.size(size)) {
        HexArcSegments(
            segmentCount = segmentCount,
            progress = progress,
            activeColor = activeColor,
            inactiveAlpha = inactiveAlpha,
            startAngleDeg = startAngleDeg,
            sweepAngleDeg = sweepAngleDeg,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            centerContent(coreSize)
        }
    }
}

@Composable
private fun HexArcSegments(
    segmentCount: Int,
    progress: Float,
    activeColor: Color,
    inactiveAlpha: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
    modifier: Modifier = Modifier
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val safeProgress = progress.coerceIn(0f, 1f)
    val segmentPaths = remember(sizePx, segmentCount, startAngleDeg, sweepAngleDeg) {
        buildHexArcPaths(sizePx, segmentCount, startAngleDeg, sweepAngleDeg)
    }
    val activeCount = remember(safeProgress, segmentCount) {
        (safeProgress * segmentCount).roundToInt().coerceIn(0, segmentCount)
    }

    Canvas(
        modifier = modifier.onSizeChanged { sizePx = it }
    ) {
        if (segmentPaths.isEmpty()) return@Canvas
        segmentPaths.forEachIndexed { index, path ->
            val alpha = if (index < activeCount) 1f else inactiveAlpha
            drawPath(path, activeColor.copy(alpha = alpha))
        }
    }
}

@Composable
private fun DetectionCore(
    size: Dp,
    progress: Float,
    flashOn: Boolean,
    borderColor: Color,
    fillColor: Color,
    backgroundColor: Color
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val safeProgress = progress.coerceIn(0f, 1f)
    val dotColor = if (flashOn) fillColor else borderColor
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val layout = remember(sizePx, safeProgress, strokePx) {
        computeDetectionCoreLayout(sizePx, safeProgress, strokePx)
    }

    Canvas(
        modifier = Modifier
            .size(size)
            .onSizeChanged { sizePx = it }
    ) {
        val shape = layout ?: return@Canvas
        drawCircle(
            color = backgroundColor,
            radius = shape.outerRadius,
            center = shape.center
        )
        drawCircle(
            color = borderColor,
            radius = shape.outerRadius,
            center = shape.center,
            style = Stroke(width = strokePx)
        )
        val startTop = shape.topCenter
        val startBottom = shape.bottomCenter
        val endTop = shape.topTouch
        val endBottom = shape.bottomTouch
        val t = safeProgress
        val topCenter = Offset(
            x = startTop.x + (endTop.x - startTop.x) * t,
            y = startTop.y + (endTop.y - startTop.y) * t
        )
        val bottomCenter = Offset(
            x = startBottom.x + (endBottom.x - startBottom.x) * t,
            y = startBottom.y + (endBottom.y - startBottom.y) * t
        )

        drawArc(
            color = dotColor,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(topCenter.x - shape.dotRadius, topCenter.y - shape.dotRadius),
            size = Size(shape.dotRadius * 2f, shape.dotRadius * 2f)
        )
        drawArc(
            color = dotColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(bottomCenter.x - shape.dotRadius, bottomCenter.y - shape.dotRadius),
            size = Size(shape.dotRadius * 2f, shape.dotRadius * 2f)
        )
    }
}

private data class DetectionCoreLayout(
    val center: Offset,
    val outerRadius: Float,
    val dotRadius: Float,
    val topCenter: Offset,
    val bottomCenter: Offset,
    val topTouch: Offset,
    val bottomTouch: Offset
)

private fun computeDetectionCoreLayout(
    sizePx: IntSize,
    progress: Float,
    strokePx: Float
): DetectionCoreLayout? {
    if (sizePx.width == 0 || sizePx.height == 0) return null
    val diameter = min(sizePx.width, sizePx.height).toFloat()
    val center = Offset(sizePx.width / 2f, sizePx.height / 2f)
    val outerRadius = (diameter - strokePx) / 2f

    val innerDiameter = diameter - (strokePx * 2f)
    val dotRadius = innerDiameter * 0.165f
    val maxGap = (innerDiameter - (dotRadius * 4f)).coerceAtLeast(0f) * RetroGapScale
    val baseGap = maxGap * (1f - progress)
    val centerOffset = dotRadius + (baseGap / 2f)

    val topCenter = Offset(center.x, center.y - centerOffset)
    val bottomCenter = Offset(center.x, center.y + centerOffset)
    val topTouch = Offset(center.x, center.y - dotRadius)
    val bottomTouch = Offset(center.x, center.y + dotRadius)

    return DetectionCoreLayout(
        center = center,
        outerRadius = outerRadius,
        dotRadius = dotRadius,
        topCenter = topCenter,
        bottomCenter = bottomCenter,
        topTouch = topTouch,
        bottomTouch = bottomTouch
    )
}

private fun buildHexArcPaths(
    sizePx: IntSize,
    segmentCount: Int,
    startAngleDeg: Float,
    sweepAngleDeg: Float
): List<Path> {
    if (sizePx.width == 0 || sizePx.height == 0 || segmentCount <= 0) return emptyList()

    val minSize = min(sizePx.width, sizePx.height).toFloat()
    val center = Offset(sizePx.width / 2f, sizePx.height / 2f)
    val outerRadius = minSize / 2f
    val innerRadius = outerRadius * 0.52f
    val ringThickness = outerRadius - innerRadius
    val segmentLength = ringThickness * 0.9f
    val segmentWidth = segmentLength * 0.28f
    val ringRadius = innerRadius + ringThickness / 2f

    val startRad = Math.toRadians(startAngleDeg.toDouble()).toFloat()
    val sweepRad = Math.toRadians(sweepAngleDeg.toDouble()).toFloat()
    val step = if (segmentCount > 1) sweepRad / (segmentCount - 1) else 0f

    return List(segmentCount) { index ->
        val angle = startRad + (step * index)
        val segmentCenter = Offset(
            center.x + cos(angle) * ringRadius,
            center.y + sin(angle) * ringRadius
        )
        buildHexPath(segmentCenter, angle, segmentLength, segmentWidth)
    }
}

private fun buildHexPath(center: Offset, angleRad: Float, length: Float, width: Float): Path {
    val halfLength = length / 2f
    val halfWidth = width / 2f
    val tip = halfWidth
    val cosA = cos(angleRad)
    val sinA = sin(angleRad)

    val points = listOf(
        Offset(-halfLength, 0f),
        Offset(-halfLength + tip, -halfWidth),
        Offset(halfLength - tip, -halfWidth),
        Offset(halfLength, 0f),
        Offset(halfLength - tip, halfWidth),
        Offset(-halfLength + tip, halfWidth)
    )

    return Path().apply {
        points.forEachIndexed { index, p ->
            val rotated = Offset(
                center.x + (p.x * cosA - p.y * sinA),
                center.y + (p.x * sinA + p.y * cosA)
            )
            if (index == 0) {
                moveTo(rotated.x, rotated.y)
            } else {
                lineTo(rotated.x, rotated.y)
            }
        }
        close()
    }
}

@Composable
private fun rememberAttackDecayProgress(
    target: Float,
    attackMs: Int = 30,
    decayMs: Int = RetroVuDecayMs
): Float {
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(target) {
        val isAttack = target > animatable.value
        animatable.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = if (isAttack) attackMs else decayMs,
                easing = if (isAttack) LinearEasing else LinearOutSlowInEasing
            )
        )
    }
    return animatable.value
}
