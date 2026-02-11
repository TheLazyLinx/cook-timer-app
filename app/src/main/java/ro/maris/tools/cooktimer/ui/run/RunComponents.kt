package ro.maris.tools.cooktimer.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.maris.tools.cooktimer.ui.theme.SoftRed

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
             HelpItem("🤚", "Clap", "Start or Next Step")
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
