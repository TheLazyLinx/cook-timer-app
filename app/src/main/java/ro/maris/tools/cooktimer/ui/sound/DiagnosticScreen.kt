package ro.maris.tools.cooktimer.ui.sound

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ro.maris.tools.cooktimer.audio.DetectionDiagnostic
import ro.maris.tools.cooktimer.audio.ProfileScore
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent

// Stage display config
private data class StageInfo(val label: String, val color: Color)

private val stageColors = mapOf(
    "ACCEPTED" to StageInfo("OK", Color(0xFF4CAF50)),
    "SUSTAIN" to StageInfo("SUSTAIN", Color(0xFFE91E63)),
    "LATE_PEAK" to StageInfo("LATE_PK", Color(0xFFFF5722)),
    "REBOUND" to StageInfo("REBOUND", Color(0xFFFF9800)),
    "DURATION" to StageInfo("DURATION", Color(0xFFFFC107)),
    "DECAY" to StageInfo("DECAY", Color(0xFFCDDC39)),
    "LOW_CONFIDENCE" to StageInfo("LOW_CONF", Color(0xFF9C27B0)),
    "ARTIFACT" to StageInfo("ARTIFACT", Color(0xFF607D8B)),
    "ISOLATION" to StageInfo("ISOLATN", Color(0xFF2196F3)),
    "CHAOS_PRE" to StageInfo("CHAOS_PRE", Color(0xFF00BCD4)),
    "CHAOS_POST" to StageInfo("CHAOS_PST", Color(0xFF009688))
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticScreen(
    vm: DiagnosticViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) vm.startDetection()
    }

    // Auto-start listening if we have permission and nothing else is driving the detector
    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.startDetection()
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        onDispose { vm.stopDetection() }
    }

    val diagnostics = state.diagnostics
    val events = state.events
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Diagnostics", "Detections", "Logs")

    // Compute tallies
    val tallies = remember(diagnostics) {
        val map = mutableMapOf<String, Int>()
        diagnostics.forEach { d ->
            val key = d.rejectedAt ?: "ACCEPTED"
            map[key] = (map[key] ?: 0) + 1
        }
        map
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Sound Diagnostics") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                val badge = when (index) {
                                    0 -> if (diagnostics.isNotEmpty()) " (${diagnostics.size})" else ""
                                    1 -> if (events.isNotEmpty()) " (${events.size})" else ""
                                    2 -> if (state.logs.isNotEmpty()) " (${state.logs.size})" else ""
                                    else -> ""
                                }
                                Text(title + badge)
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // === LIVE METERS (always shown) ===
            LiveMetersSection(
                rms = state.currentRms,
                noiseFloor = state.noiseFloor,
                isLit = state.isLit,
                count = state.count,
                debugInfo = state.debugInfo
            )

            if (!hasPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Microphone permission required", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> DiagnosticsTab(diagnostics, tallies, vm, context)
                1 -> DetectionsTab(events)
                2 -> LogsTab(state.logs, vm, context)
            }
        }
    }
}

// ================== TAB 0: DIAGNOSTICS ==================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DiagnosticsTab(
    diagnostics: List<DetectionDiagnostic>,
    tallies: Map<String, Int>,
    vm: DiagnosticViewModel,
    context: Context
) {
    // Tally chips
    if (diagnostics.isNotEmpty()) {
        Text("Rejection Tally", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            stageColors.forEach { (key, info) ->
                val count = tallies[key] ?: 0
                if (count > 0) {
                    TallyChip(label = info.label, count = count, color = info.color)
                }
            }
        }
    }

    // Controls
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = {
                vm.copyDiagnosticsToClipboard(context)
                Toast.makeText(context, "CSV copied", Toast.LENGTH_SHORT).show()
            },
            enabled = diagnostics.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text("Copy CSV") }
        OutlinedButton(
            onClick = { vm.clearDiagnostics() },
            enabled = diagnostics.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text("Clear") }
    }

    // Diagnostic list
    if (diagnostics.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Diagnostics accumulate as you use the app.\nRun a timer/stopwatch/preset, or make sounds here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(diagnostics, key = { it.id }) { diag ->
                DiagnosticCard(diag, onNoteUpdate = { note -> vm.updateNote(diag.id, note) })
            }
        }
    }
}

// ================== TAB 1: DETECTIONS ==================

@Composable
private fun ColumnScope.DetectionsTab(events: List<SoundDetectionEvent>) {
    if (events.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No confirmed detections yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(events) { event ->
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
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    event.type,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    "${"%.0f".format(event.confidence * 100)}%",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4CAF50)
                    )
                )
                Text(
                    "${event.durationMs}ms",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Text(
                    "${ageSec}s ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Str: ${"%.3f".format(event.strength)}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text("Peak: ${"%.3f".format(event.peak)}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text("C: ${"%.0f".format(event.centroid)}Hz", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text("T: ${"%.1f".format(event.tilt)}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

// ================== TAB 2: LOGS ==================

@Composable
private fun ColumnScope.LogsTab(logs: List<String>, vm: DiagnosticViewModel, context: Context) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = {
                vm.copyLogsToClipboard(context)
                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
            },
            enabled = logs.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text("Copy Logs") }
        OutlinedButton(
            onClick = { vm.clearAll() },
            enabled = logs.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text("Clear All") }
    }

    if (logs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No log entries yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(logs.reversed()) { log ->
                Text(
                    log,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    ),
                    color = if (log.contains("DETECTED") || log.contains("ACCEPTED")) Color(0xFF4CAF50)
                    else if (log.contains("REJECTED")) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ================== SHARED COMPONENTS ==================

@Composable
private fun LiveMetersSection(
    rms: Float,
    noiseFloor: Float,
    isLit: Boolean,
    count: Int,
    debugInfo: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLit) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("RMS", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "%.4f".format(rms),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                Column {
                    Text("Floor", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "%.4f".format(noiseFloor),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                Column {
                    Text("Thresh", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "%.4f".format((noiseFloor * 4f).coerceAtLeast(0.035f)),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                if (isLit) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                    Text("$count", style = MaterialTheme.typography.labelSmall)
                }
            }
            // RMS bar
            LinearProgressIndicator(
                progress = { (rms * 15f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            )
            if (debugInfo.isNotEmpty()) {
                Text(debugInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TallyChip(label: String, count: Int, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            "$label: $count",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = color,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagnosticCard(diag: DetectionDiagnostic, onNoteUpdate: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    val stageInfo = stageColors[diag.stageLabel] ?: StageInfo(diag.stageLabel, Color.Gray)
    val isAccepted = diag.outcome == "ACCEPTED"
    val gestureKind = when (diag.gestureTapCount) {
        3 -> "TRIPLE"
        2 -> "DOUBLE"
        1 -> "SINGLE"
        else -> null
    }
    val gestureBracket = if (
        diag.gestureGroupId != null &&
        diag.gestureTapIndex != null &&
        diag.gestureTapCount != null
    ) {
        "[G${diag.gestureGroupId}:${diag.gestureTapIndex}/${diag.gestureTapCount}]"
    } else {
        null
    }

    if (showNoteDialog) {
        NoteDialog(
            currentNote = diag.note,
            onDismiss = { showNoteDialog = false },
            onSave = { note ->
                onNoteUpdate(note)
                showNoteDialog = false
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { showNoteDialog = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isAccepted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // === HEADER ROW ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stage badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(stageInfo.color)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        stageInfo.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
                if (gestureBracket != null) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF455A64))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            gestureBracket,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                // Key metrics
                Text(
                    "${diag.durationMs}ms",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    "C:${"%.0f".format(diag.avgCentroid)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(52.dp)
                )
                Text(
                    "${"%.0f".format(diag.bestConfidence * 100)}%",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isAccepted) FontWeight.Bold else FontWeight.Normal
                    ),
                    modifier = Modifier.width(36.dp)
                )
                Text(
                    diag.bestProfileName ?: "-",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    diag.timestamp.substringAfterLast(':').let { diag.timestamp.dropLast(it.length + 1).takeLast(5) + ":" + it.take(2) },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    modifier = Modifier.size(18.dp)
                )
            }

            if (gestureKind != null) {
                Text(
                    buildString {
                        append("Gesture: ")
                        append(gestureKind)
                        if (diag.gestureUnpairedReason != null) {
                            append(" - ")
                            append(diag.gestureUnpairedReason)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (gestureKind == "SINGLE") Color(0xFFFFA000) else Color(0xFF455A64),
                        fontSize = 11.sp
                    ),
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // === REJECTION DETAIL (always visible if rejected) ===
            if (!isAccepted && diag.rejectionDetail != null) {
                Text(
                    diag.rejectionDetail,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = stageInfo.color,
                        fontSize = 11.sp
                    ),
                    maxLines = if (expanded) 5 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // === NOTE (always visible if present) ===
            if (diag.note.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                        .clickable { showNoteDialog = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📝 ",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        diag.note,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // === EXPANDED DETAILS ===
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                    SectionHeader("Environment")
                    MetricRow("Noise Floor", "%.5f".format(diag.noiseFloor))
                    MetricRow("Threshold", "%.5f".format(diag.threshold))
                    MetricRow("Pre-Event Noise", "%.5f".format(diag.preEventNoiseAvg))
                    val isoRatio = if (diag.peakRms > 0) diag.preEventNoiseAvg / diag.peakRms else 0f
                    MetricRow("Isolation Ratio", "%.3f".format(isoRatio))

                    SectionHeader("Trigger")
                    MetricRow("Trigger RMS", "%.5f".format(diag.triggerRms))
                    MetricRow("Attack Ratio", "%.2f".format(diag.attackRatio) + "x")

                    SectionHeader("Temporal")
                    MetricRow("Duration", "${diag.durationMs}ms")
                    MetricRow("Peak RMS", "%.5f".format(diag.peakRms))
                    MetricRow("Peak Raw", "%.5f".format(diag.peakRaw))
                    MetricRow("Peak Frame", "${diag.peakFrameIndex}")
                    diag.sustainRatio?.let { MetricRow("Sustain Ratio @50ms", "%.3f".format(it)) }
                    diag.decayRatio?.let { MetricRow("Decay Ratio @60ms", "%.3f".format(it)) }

                    SectionHeader("Chaos")
                    diag.preEventCV?.let { MetricRow("Pre-Event Spike%", "%.3f".format(it)) }
                    diag.postEventRms?.let { MetricRow("Post-Event RMS", "%.5f".format(it)) }
                    diag.postEventQuietThreshold?.let { MetricRow("Post-Quiet Thresh", "%.5f".format(it)) }

                    SectionHeader("Spectral")
                    MetricRow("Avg ZCR", "%.3f".format(diag.avgZcr))
                    MetricRow("Avg Centroid", "%.0f Hz".format(diag.avgCentroid))
                    MetricRow("Avg Tilt", "%.2f".format(diag.avgTilt))

                    if (gestureKind != null) {
                        SectionHeader("Gesture Pairing")
                        val pairGroup = "G${diag.gestureGroupId ?: "-"}"
                        val pairPos = "${diag.gestureTapIndex ?: "-"}/${diag.gestureTapCount ?: "-"}"
                        MetricRow("Group", "$pairGroup  $pairPos")
                        MetricRow("Resolved As", gestureKind)
                        diag.gestureUnpairedReason?.let { MetricRow("Not Grouped Why", it) }
                    }

                    // Profile Scores
                    if (diag.allProfileScores.isNotEmpty()) {
                        SectionHeader("Profile Scores")
                        diag.allProfileScores
                            .sortedByDescending { it.confidence }
                            .forEach { score ->
                                ProfileScoreRow(score, isTop = score.name == diag.bestProfileName)
                            }
                    }
                }
            }
        }
    }
}

// ================== NOTE DIALOG ==================

private val quickNotes = listOf("False Positive", "False Negative", "Door", "Voice", "Dish/Pan", "Water", "TV/Music", "Good Snap", "Good Clap")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteDialog(
    currentNote: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(currentNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickNotes.forEach { label ->
                        FilterChip(
                            selected = text == label,
                            onClick = { text = if (text == label) "" else label },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            if (currentNote.isNotEmpty()) {
                OutlinedButton(onClick = { onSave("") }) { Text("Remove") }
            }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun ProfileScoreRow(score: ProfileScore, isTop: Boolean) {
    Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                score.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
                    color = if (isTop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            )
            Text(
                "${"%.0f".format(score.confidence * 100)}%",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        score.confidence >= 0.78f -> Color(0xFF4CAF50)
                        score.confidence >= 0.60f -> Color(0xFFFFC107)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            )
        }
        // Individual metric scores
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ScoreChip("D", score.durScore)
            ScoreChip("Z", score.zcrScore)
            ScoreChip("C", score.centroidScore)
            ScoreChip("T", score.tiltScore)
        }
    }
}

@Composable
private fun ScoreChip(label: String, value: Float) {
    val color = when {
        value >= 0.8f -> Color(0xFF4CAF50)
        value >= 0.5f -> Color(0xFFFFC107)
        else -> Color(0xFFE91E63)
    }
    Text(
        "$label:${"%.0f".format(value * 100)}",
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            color = color,
            fontSize = 10.sp
        )
    )
}
