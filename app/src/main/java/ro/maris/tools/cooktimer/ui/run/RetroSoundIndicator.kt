package ro.maris.tools.cooktimer.ui.run

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.maris.tools.cooktimer.audio.SoundDetectorState
import ro.maris.tools.cooktimer.ui.theme.SoftRed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff

// ...existing code...
@Composable
fun RetroSoundIndicator(
    state: SoundDetectorState,
    modifier: Modifier = Modifier
) {
    // Threshold calculation logic
    val baseThreshold = 0.035f
    val multiplier = 4.0f
    val computedThreshold = (state.noiseFloor * multiplier).coerceAtLeast(baseThreshold)
    
    // Normalize RMS
    val relativeRms = (state.currentRms / computedThreshold).coerceIn(0f, 1.2f)

    // Visual Logic
    // Responsive Indication mapped to Threshold (Percentage System)
    // 0% -> Silence, 100% -> Threshold Met
    // Using simple ratio as requested: 50% sound (relative to limit) = 50% bar
    val rawRatio = (state.currentRms / computedThreshold).coerceIn(0f, 1f)
    val targetProgress = if (state.isLit) 1.0f else rawRatio

    // Asymmetric Animation: Fast Attack, Slow Decay
    // Ensures brief peaks are visible to the user ("Pulse Extender" effect via physics)
    val progressAnimatable = remember { Animatable(0f) }
    
    LaunchedEffect(targetProgress) {
        if (targetProgress > progressAnimatable.value) {
            // Attack: Instant (30ms) to catch the peak
            progressAnimatable.animateTo(targetProgress, tween(30, easing = LinearEasing))
        } else {
            // Decay: Faster release (150ms) to feel responsive but perceptible
            progressAnimatable.animateTo(targetProgress, tween(150, easing = LinearOutSlowInEasing))
        }
    }
    
    val animatedProgress = progressAnimatable.value
    
    val micAlpha by animateFloatAsState(
        targetValue = if (state.isLit) 1f else (0.3f + (relativeRms * 0.7f)).coerceIn(0.3f, 1f),
        label = "micAlpha"
    )

    // Error message 
    val errorMessage = remember(state.lastRejectionReason) {
         if (state.lastRejectionReason != null) {
              getFriendlyRejectionMessage(state.lastRejectionReason!!)
         } else null
    }
    
    var visibleError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            visibleError = errorMessage
            delay(5000)
            visibleError = null
        }
    }

    Row(
        modifier = modifier.height(64.dp), 
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Microphone Icon in Circle
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(2.dp, SoftRed.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
             Icon(
                 imageVector = if (visibleError != null) Icons.Default.Warning else Icons.Default.Mic,
                 contentDescription = "Microphone Level",
                 tint = if (visibleError != null) MaterialTheme.colorScheme.error else SoftRed.copy(alpha = micAlpha),
                 modifier = Modifier.size(28.dp)
             )
        }
        
        // 2. Bar Precision Detector
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .border(2.dp, SoftRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val segmentCount = 24
                val w = size.width
                val h = size.height
                val gap = 2.dp.toPx()
                val totalGap = gap * (segmentCount - 1)
                val segmentWidth = (w - totalGap) / segmentCount
                
                // Map progress to segments
                val activeCount = (animatedProgress * segmentCount).toInt().coerceIn(0, segmentCount)
                
                for (i in 0 until segmentCount) {
                    val x = i * (segmentWidth + gap)
                    val centerX = x + segmentWidth / 2
                    val centerY = h / 2
                    
                    // Draw skinny Hexagon (Vertical segment style)
                    val tipH = segmentWidth / 2 // 45 degree points
                    val safeTipH = tipH.coerceAtMost(h / 2)
                    
                    val path = Path().apply {
                        moveTo(centerX, 0f) // Top Tip
                        lineTo(x + segmentWidth, safeTipH) // Top Right Shoulder
                        lineTo(x + segmentWidth, h - safeTipH) // Bot Right Shoulder
                        lineTo(centerX, h) // Bot Tip
                        lineTo(x, h - safeTipH) // Bot Left Shoulder
                        lineTo(x, safeTipH) // Top Left Shoulder
                        close()
                    }
                    
                    val isActive = i < activeCount
                    val alpha = if (isActive) 1.0f else 0.1f
                    
                    drawPath(path, SoftRed.copy(alpha = alpha))
                }
            }
            
            // Overlay Text for Status
            if (visibleError != null) {
                Text(
                    text = visibleError!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
