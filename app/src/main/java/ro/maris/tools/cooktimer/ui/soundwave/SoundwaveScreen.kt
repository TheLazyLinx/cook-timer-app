package ro.maris.tools.cooktimer.ui.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent
import kotlin.math.ln

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundwaveScreen(
    vm: SoundwaveViewModel,
    onBack: () -> Unit
) {
    val detectorState by vm.detectorState.collectAsState()
    val selectedEvent by vm.selectedEvent.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Soundwave Visualization") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val detectionColor = if (detectorState.isLit) Color.Red else MaterialTheme.colorScheme.surfaceVariant
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(detectionColor, shape = CircleShape)
                    )
                    Text("Detection", style = MaterialTheme.typography.labelSmall)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val intensity = (detectorState.currentRms * 20f).coerceIn(0f, 1f)
                    val continuousColor = Color(
                        red = intensity,
                        green = 1f - intensity * 0.5f,
                        blue = 0f
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(continuousColor, shape = CircleShape)
                    )
                    Text("Listening", style = MaterialTheme.typography.labelSmall)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Detections: ${detectorState.count}", style = MaterialTheme.typography.bodySmall)
                    Text("RMS: ${"%.3f".format(detectorState.currentRms)}", style = MaterialTheme.typography.bodySmall)
                    Text("Noise: ${"%.3f".format(detectorState.noiseFloor)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Detection events over time graph OR selected event detail
            if (selectedEvent != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Detection Detail",
                                style = MaterialTheme.typography.titleMedium
                            )
                            androidx.compose.material3.TextButton(onClick = { vm.selectEvent(null) }) {
                                Text("Back to Overview")
                            }
                        }
                        
                        Text(
                            text = "Sound Wave - ${selectedEvent!!.samples.size * 10}ms capture",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        DetectionWaveformGraph(
                            event = selectedEvent!!,
                            noiseFloor = detectorState.noiseFloor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Strength: ${"%.4f".format(selectedEvent!!.strength)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Peak: ${"%.4f".format(selectedEvent!!.peak)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${"%.1f".format(rmsToDb(selectedEvent!!.strength))} dB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${"%.1f".format(rmsToDb(selectedEvent!!.peak))} dB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Detection Events Over Time",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Text(
                        text = "dB vs Time (milliseconds)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    DetectionTimelineGraph(
                        events = detectorState.events,
                        noiseFloor = detectorState.noiseFloor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Events: ${detectorState.events.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Noise: %.1f dB".format(rmsToDb(detectorState.noiseFloor)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            // Current amplitude indicator
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Current Level",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "RMS: %.3f".format(detectorState.currentRms),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "dB: %.1f".format(rmsToDb(detectorState.currentRms)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Last Strength: %.3f".format(detectorState.lastStrength),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Peak: %.3f".format(detectorState.lastPeak),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Recent detections list
            Text(
                text = "Recent Detections",
                style = MaterialTheme.typography.titleMedium
            )
            
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(detectorState.events) { event ->
                    DetectionCard(
                        event = event,
                        onClick = { vm.selectEvent(event) }
                    )
                }
            }
        }
    }
}
}

@Composable
private fun DetectionTimelineGraph(
    events: List<SoundDetectionEvent>,
    noiseFloor: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val noiseColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    
    Box(modifier = modifier) {
        if (events.isEmpty()) {
            Text(
                text = "Waiting for detections...",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Box
        }
        
        // Find time range
        val minTime = events.minOf { it.timestampMs }
        val maxTime = events.maxOf { it.timestampMs }
        val timeRange = (maxTime - minTime).coerceAtLeast(1L)
        
        // Find dB range
        val allValues = events.flatMap { listOf(it.strength, it.peak) } + noiseFloor
        val minDb = allValues.minOf { rmsToDb(it) }.coerceAtMost(-20f)
        val maxDb = allValues.maxOf { rmsToDb(it) }.coerceAtLeast(0f)
        val dbRange = (maxDb - minDb).coerceAtLeast(10f)
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val padding = 60f
            val graphWidth = width - padding * 2
            val graphHeight = height - padding * 2
            
            // Draw grid
            for (i in 0..5) {
                val y = padding + graphHeight * i / 5f
                drawLine(
                    color = gridColor,
                    start = Offset(padding, y),
                    end = Offset(width - padding, y),
                    strokeWidth = 1f
                )
            }
            
            // Draw time grid lines
            for (i in 0..5) {
                val x = padding + graphWidth * i / 5f
                drawLine(
                    color = gridColor,
                    start = Offset(x, padding),
                    end = Offset(x, height - padding),
                    strokeWidth = 1f
                )
            }
            
            // Draw noise floor line
            val noiseDb = rmsToDb(noiseFloor)
            val noiseY = padding + graphHeight * (maxDb - noiseDb) / dbRange
            drawLine(
                color = noiseColor,
                start = Offset(padding, noiseY),
                end = Offset(width - padding, noiseY),
                strokeWidth = 2f
            )
            
            // Draw strength points and lines
            val strengthPath = Path()
            events.forEachIndexed { index, event ->
                val x = padding + graphWidth * (event.timestampMs - minTime).toFloat() / timeRange
                val strengthDb = rmsToDb(event.strength)
                val peakDb = rmsToDb(event.peak)
                
                val strengthY = padding + graphHeight * (maxDb - strengthDb) / dbRange
                val peakY = padding + graphHeight * (maxDb - peakDb) / dbRange
                
                // Draw strength line
                if (index == 0) {
                    strengthPath.moveTo(x, strengthY)
                } else {
                    strengthPath.lineTo(x, strengthY)
                }
                
                // Draw peak point
                drawCircle(
                    color = secondaryColor,
                    radius = 4f,
                    center = Offset(x, peakY)
                )
                
                // Draw strength point
                drawCircle(
                    color = primaryColor,
                    radius = 6f,
                    center = Offset(x, strengthY)
                )
            }
            
            // Draw strength line connecting points
            drawPath(
                path = strengthPath,
                color = primaryColor,
                style = Stroke(width = 2f)
            )
        }
        
        // Overlay labels using Text composables
        Column(modifier = Modifier.fillMaxSize()) {
            // Y-axis labels (dB)
            for (i in 0..5) {
                val db = maxDb - (dbRange * i / 5f)
                Text(
                    text = "%.0f dB".format(db),
                    modifier = Modifier.offset(y = (i * 20).dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        
        // X-axis labels (time in ms)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 60.dp, end = 60.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 0..5) {
                val timeMs = (timeRange * i / 5f).toLong()
                Text(
                    text = "${timeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        
        // Noise label
        Text(
            text = "Noise",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-5).dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = noiseColor
        )
    }
}

@Composable
private fun SoundwaveGraph(
    currentRms: Float,
    noiseFloor: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val noiseColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        
        // Draw grid lines
        for (i in 0..4) {
            val y = height * i / 4f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f
            )
        }
        
        // Draw center line
        drawLine(
            color = gridColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 2f
        )
        
        // Draw noise floor line
        val noiseY = centerY - (noiseFloor * height * 10f).coerceIn(-centerY, centerY)
        drawLine(
            color = noiseColor,
            start = Offset(0f, noiseY),
            end = Offset(width, noiseY),
            strokeWidth = 2f
        )
        
        // Draw current RMS as a bar
        val rmsHeight = (currentRms * height * 10f).coerceIn(0f, centerY)
        drawLine(
            color = primaryColor,
            start = Offset(width / 2f, centerY - rmsHeight),
            end = Offset(width / 2f, centerY + rmsHeight),
            strokeWidth = 40f
        )
        
        // Draw peak indicator
        val peakY = centerY - rmsHeight
        drawCircle(
            color = primaryColor,
            radius = 8f,
            center = Offset(width / 2f, peakY)
        )
    }
}

@Composable
private fun DetectionCard(event: SoundDetectionEvent, onClick: () -> Unit) {
    val now = android.os.SystemClock.elapsedRealtime()
    val ageMs = (now - event.timestampMs).coerceAtLeast(0)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Time: ${event.timestampMs} ms",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${ageMs / 1000}s ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Strength: ${"%.4f".format(event.strength)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Peak: ${"%.4f".format(event.peak)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${"%.1f".format(rmsToDb(event.strength))} dB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Peak: ${"%.1f".format(rmsToDb(event.peak))} dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Show peak-to-strength ratio
            val ratio = if (event.strength > 0f) event.peak / event.strength else 0f
            Text(
                text = "Peak/Strength Ratio: ${"%.2f".format(ratio)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetectionWaveformGraph(
    event: SoundDetectionEvent,
    noiseFloor: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val peakColor = MaterialTheme.colorScheme.secondary
    val noiseColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    Box(modifier = modifier) {
        if (event.samples.isEmpty()) {
            Text(
                text = "No waveform data captured",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Box
        }

        // Find value range
        val maxValue = maxOf(event.samples.maxOrNull() ?: 0f, event.peak, noiseFloor)
        val minValue = 0f
        val valueRange = (maxValue - minValue).coerceAtLeast(0.01f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val padding = 60f
            val graphWidth = width - padding * 2
            val graphHeight = height - padding * 2

            // Draw grid
            for (i in 0..5) {
                val y = padding + graphHeight * i / 5f
                drawLine(
                    color = gridColor,
                    start = Offset(padding, y),
                    end = Offset(width - padding, y),
                    strokeWidth = 1f
                )
            }

            // Draw time grid
            for (i in 0..5) {
                val x = padding + graphWidth * i / 5f
                drawLine(
                    color = gridColor,
                    start = Offset(x, padding),
                    end = Offset(x, height - padding),
                    strokeWidth = 1f
                )
            }

            // Draw noise floor line
            val noiseY = height - padding - (graphHeight * noiseFloor / valueRange)
            drawLine(
                color = noiseColor,
                start = Offset(padding, noiseY),
                end = Offset(width - padding, noiseY),
                strokeWidth = 2f
            )

            // Draw strength line
            val strengthY = height - padding - (graphHeight * event.strength / valueRange)
            drawLine(
                color = peakColor.copy(alpha = 0.5f),
                start = Offset(padding, strengthY),
                end = Offset(width - padding, strengthY),
                strokeWidth = 2f
            )

            // Draw peak line
            val peakY = height - padding - (graphHeight * event.peak / valueRange)
            drawLine(
                color = peakColor,
                start = Offset(padding, peakY),
                end = Offset(width - padding, peakY),
                strokeWidth = 2f
            )

            // Draw waveform
            val waveformPath = Path()
            event.samples.forEachIndexed { index, sample ->
                val x =
                    padding + graphWidth * index.toFloat() / (event.samples.size - 1).coerceAtLeast(
                        1
                    )
                val y = height - padding - (graphHeight * sample / valueRange)

                if (index == 0) {
                    waveformPath.moveTo(x, y)
                } else {
                    waveformPath.lineTo(x, y)
                }

                // Draw sample point
                drawCircle(
                    color = primaryColor,
                    radius = 3f,
                    center = Offset(x, y)
                )
            }

            // Draw waveform line
            drawPath(
                path = waveformPath,
                color = primaryColor,
                style = Stroke(width = 2f)
            )
        } // End Canvas

        // Overlay labels
        Column(modifier = Modifier.fillMaxSize()) {
            for (i in 0..5) {
                val value = maxValue - (valueRange * i / 5f)
                Text(
                    text = "${"%.3f".format(value)} (${"%.0f".format(rmsToDb(value))} dB)",
                    modifier = Modifier.offset(y = (i * 50).dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // X-axis labels (time)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 60.dp, end = 60.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 0..5) {
                val timeMs = event.samples.size * 10 * i / 5
                Text(
                    text = "${timeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Labels
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(peakColor)
                )
                Text("Peak", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(peakColor.copy(alpha = 0.5f))
                )
                Text("Strength", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(noiseColor)
                )
                Text("Noise", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
            }
        }
    }
}


private fun rmsToDb(rms: Float): Float {
    if (rms <= 0f) return -100f
    return 20f * ln(rms) / ln(10f)
}
