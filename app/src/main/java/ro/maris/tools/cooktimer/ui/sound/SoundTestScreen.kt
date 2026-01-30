package ro.maris.tools.cooktimer.ui.sound

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent
import android.widget.Toast

@Composable
fun SoundTestScreen(
    vm: SoundTestViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var requestedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            vm.startDetection()
        }
    }
    val state by vm.state.collectAsState()
    
    // We only start detection explicitly if we have permission.
    // NOTE: We do NOT rely on "Start" button anymore for viewing logs.
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            vm.startDetection()
        }
    }
    LaunchedEffect(hasPermission, requestedOnce) {
        if (!hasPermission && !requestedOnce) {
            requestedOnce = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back") }
            Text(text = "Sound Test (Logs: ${state.logs.size})", style = MaterialTheme.typography.titleLarge)
    
            if (state.logs.isNotEmpty()) {
                Row(
                   horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { 
                        vm.copyLogsToClipboard(context) 
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy Logs")
                    }
                    Button(onClick = { vm.clearLogs() }) {
                        Text("Clear Logs")
                    }
                }
            }
    
            if (!hasPermission) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Microphone permission is required.")
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text("Grant Permission")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val detectionColor = if (state.isLit) Color.Red else MaterialTheme.colorScheme.surfaceVariant
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(detectionColor, shape = androidx.compose.foundation.shape.CircleShape)
                )
                Text("Detection", style = MaterialTheme.typography.labelSmall)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val intensity = (state.currentRms * 20f).coerceIn(0f, 1f)
                val continuousColor = Color(
                    red = intensity,
                    green = 1f - intensity * 0.5f,
                    blue = 0f
                )
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(continuousColor, shape = androidx.compose.foundation.shape.CircleShape)
                )
                Text("Continuous", style = MaterialTheme.typography.labelSmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Detections: ${state.count}")
                Text("Strength: ${"%.3f".format(state.lastStrength)}")
                Text("Peak: ${"%.3f".format(state.lastPeak)}")
                Text("Current: ${"%.3f".format(state.currentRms)}")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(text = "Recent detections", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.events) { event ->
                DetectionRow(event)
            }
        }
    }
}

@Composable
private fun DetectionRow(event: SoundDetectionEvent) {
    val ageMs = (SystemClock.elapsedRealtime() - event.timestampMs).coerceAtLeast(0)
    val ageSec = ageMs / 1000
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Age: ${ageSec}s")
            Text("Strength: ${"%.3f".format(event.strength)}")
            Text("Peak: ${"%.3f".format(event.peak)}")
        }
    }
}
