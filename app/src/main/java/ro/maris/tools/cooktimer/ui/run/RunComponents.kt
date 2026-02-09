package ro.maris.tools.cooktimer.ui.run

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.maris.tools.cooktimer.audio.SoundDetectorState
import ro.maris.tools.cooktimer.ui.theme.SoftRed
import kotlinx.coroutines.delay

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SoundDetectionIndicator(
    state: SoundDetectorState,
    modifier: Modifier = Modifier
) {
    // Threshold calculation logic from SoundDetector
    val baseThreshold = 0.035f // Matches new SoundDetector
    val multiplier = 4.0f 
    val computedThreshold = (state.noiseFloor * multiplier).coerceAtLeast(baseThreshold)
    
    // Normalize RMS (0.0 to 1.0 relative to threshold)
    val relativeRms = (state.currentRms / computedThreshold).coerceIn(0f, 1.2f)
    
    // Visual Logic:
    // 1. Cap non-detected movement to 60% to prevent "almost touching" confusion
    // 2. Deadzone: Don't move at all for low noise (< 20%)
    val visualCap = 0.6f
    val deadzone = 0.2f
    
    val targetProgress = if (state.isLit) {
        1.0f 
    } else {
        if (relativeRms < deadzone) 0f else relativeRms.coerceAtMost(visualCap)
    }

    // Smooth the visual movement
    // Use Tween for volume updates to absolutely prevent spring overshoot causing false visual clashes
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (state.isLit) spring(stiffness = Spring.StiffnessMedium) else tween(durationMillis = 100),
        label = "soundProgress"
    )
    
    val borderColor by animateColorAsState(
        targetValue = if (state.isLit) SoftRed else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "borderFlash"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )
    
    // Error message fading logic
    val errorMessage = remember(state.lastRejectionReason) {
         if (state.lastRejectionReason != null) {
              getFriendlyRejectionMessage(state.lastRejectionReason!!)
         } else null
    }
    
    var visibleError by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            visibleError = errorMessage
            // Fade after 15 seconds
            delay(15000)
            visibleError = null
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(4.dp, borderColor, RoundedCornerShape(24.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val circleRadius = h / 2f
                val trackWidth = w - (circleRadius * 2)
                
                val maxTravel = trackWidth / 2f
                val travel = maxTravel * animatedProgress
                
                val leftCenter = circleRadius + travel
                val rightCenter = (w - circleRadius) - travel
                
                val color = if (state.isLit) SoftRed else SoftRed.copy(alpha = breathingAlpha)
                
                drawCircle(
                    color = color,
                    radius = circleRadius - 2.dp.toPx(),
                    center = Offset(leftCenter, h / 2f)
                )
                
                drawCircle(
                    color = color,
                    radius = circleRadius - 2.dp.toPx(),
                    center = Offset(rightCenter, h / 2f)
                )
            }
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f),
                modifier = Modifier.size(16.dp)
            )
            
            if (visibleError != null && !state.isLit) {
                Text(
                    text = ":  $visibleError",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else if (state.isLit) {
                 val type = state.debugInfo.substringBefore(" ").take(10)
                 Text(
                    text = ":  Detected: $type",
                    style = MaterialTheme.typography.labelMedium,
                    color = SoftRed,
                    textAlign = TextAlign.Center
                )
            } else {
                 Text(
                    text = "Listening...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

fun getFriendlyRejectionMessage(reason: String): String {
    return when {
        reason.contains("Low Confidence") -> "Not clear. Try a sharper snap."
        reason.contains("Isolation") -> "Too much background noise."
        reason.contains("Too long") -> "Sound was too long."
        reason.contains("Decay") -> "Too much echo."
        reason.contains("Bouncy") -> "Sound wasn't clean."
        else -> "Try again."
    }
}

@Composable
fun StarterHelpOverlay(
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable { onDismiss() }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
         Column(
             verticalArrangement = Arrangement.spacedBy(16.dp),
             horizontalAlignment = Alignment.CenterHorizontally
         ) {
             Text(
                 text = "Hands-Free Control",
                 style = MaterialTheme.typography.headlineSmall,
                 color = Color.White
             )
             
             Text(
                 text = "Control the timer sound gestures!",
                 style = MaterialTheme.typography.bodyLarge,
                 color = Color.White.copy(alpha = 0.9f),
                 textAlign = TextAlign.Center
             )
             
             HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
             
             HelpItem("🫰", "Snap", "Start or Resume timer")
             HelpItem("🤚", "Slap", "Start or Next Step")
             HelpItem("⚪", "Circles", "Watch the circles below the timer. They meet when a sound is detected.")
             HelpItem("⚠️", "Tip", "Keep background noise low for best results.")

             Spacer(modifier = Modifier.height(16.dp))
             
             Button(
                 onClick = onDismiss,
                 colors = ButtonDefaults.buttonColors(containerColor = SoftRed)
             ) {
                 Text("Got it")
             }
         }
    }
}

@Composable
fun HelpItem(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = icon, style = MaterialTheme.typography.headlineMedium)
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
