package ro.maris.tools.cooktimer.ui.stopwatch

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import ro.maris.tools.cooktimer.audio.LocalSfxPlayer
import ro.maris.tools.cooktimer.audio.sfxClick
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.ui.theme.SoftRed
import ro.maris.tools.cooktimer.ui.run.StarterHelpOverlay
import ro.maris.tools.cooktimer.ui.run.SoundDetectionIndicator

@Composable
fun StopwatchScreen(
    vm: StopwatchViewModel,
) {
    val state = vm.state.collectAsState().value

    // In StopwatchScreen, state should be non-null almost immediately due to init block in ViewModel
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val sfx = LocalSfxPlayer.current
    val detectorState by vm.detectorState.collectAsState()
    val settings by vm.settings.collectAsState(initial = null)
    var showHelp by remember { mutableStateOf(false) }
    
    // Show help if not shown before
    LaunchedEffect(settings) {
        if (settings?.hasShownSoundHelpOverlay == false) {
            showHelp = true
        }
    }
    
    var lastState by remember { mutableStateOf(state) }

    DisposableEffect(Unit) {
        vm.onVisibilityChange(true)
        onDispose {
            vm.onVisibilityChange(false)
        }
    }

    LaunchedEffect(state) {
        val prev = lastState
        val autoAdvanced = state.activeStepIndex > prev.activeStepIndex && !prev.stepCompleted
        if (prev.phase != Phase.COMPLETED && state.phase == Phase.COMPLETED) {
            sfx.play(SfxId.PresetFinished)
        } else if (state.phase != Phase.COMPLETED && autoAdvanced) {
            sfx.play(SfxId.TimerFinished)
            delay(140)
            sfx.play(SfxId.StepStart)
        } else {
            if (state.phase != Phase.COMPLETED && state.stepCompleted && !prev.stepCompleted) {
                sfx.play(SfxId.TimerFinished)
            }
            if ((prev.phase == Phase.READY && state.phase == Phase.RUNNING) ||
                (!prev.isStopwatchRunning && state.isStopwatchRunning)
            ) {
                sfx.play(SfxId.StepStart)
            }
        }
        lastState = state
    }

    val step = state.activeStep
    val next = state.nextStep

    val isDarkTheme = isSystemInDarkTheme()
    val accentButtonColors = ButtonDefaults.buttonColors(
        containerColor = SoftRed,
        contentColor = Color.White
    )
    val accentTextButtonColors = ButtonDefaults.textButtonColors(
        contentColor = SoftRed
    )
    val timerTextStyle = MaterialTheme.typography.displayLarge.copy(
        fontFamily = FontFamily.Monospace
    )
    val canGoBack = state.activeStepIndex > 0

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = state.presetName, style = MaterialTheme.typography.titleMedium)
            
            val timerText = when {
                state.phase == Phase.RUNNING -> "Remaining: ${formatTime(requireNotNull(state.remainingSec))}"
                state.isStopwatchRunning -> "Elapsed: ${formatTime(requireNotNull(state.stopwatchElapsedSec))}"
                state.phase == Phase.PAUSED -> {
                    val label = if (step?.durationSec != null) "Remaining" else "Elapsed"
                    val timeValue = state.pausedRemainingSec ?: state.stopwatchElapsedSec ?: 0
                    "$label: ${formatTime(timeValue)}"
                }
                state.isReadyToAdvance && state.stopwatchElapsedSec != null ->
                    "Elapsed: ${formatTime(requireNotNull(state.stopwatchElapsedSec))}"
                state.phase == Phase.READY -> "Ready"
                else -> {
                    val label = when (state.phase) {
                        Phase.READY -> "Ready"
                        Phase.PAUSED -> "Paused"
                        Phase.COMPLETED -> "Completed"
                        Phase.RUNNING -> "Running"
                    }
                    label
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = timerText,
                    style = timerTextStyle,
                    color = if (isDarkTheme && (state.phase == Phase.RUNNING || state.isStopwatchRunning)) {
                        SoftRed
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            Text(
                text = when (state.phase) {
                    Phase.READY -> "READY"
                    Phase.RUNNING -> "RUNNING"
                    Phase.PAUSED -> "PAUSED"
                    Phase.COMPLETED -> "COMPLETED"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            if (!step?.notes.isNullOrBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = step!!.notes!!,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 360.dp)
                    )
                }
            }

            SoundDetectionIndicator(
                state = detectorState, 
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider()

                if (next != null) {
                    Text(
                        text = "Next: ${next.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canGoBack) {
                       OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.back() }) { Text("Back") }
                    }
                    val isLastStep = next == null
                    val showRestart = isLastStep && (state.isReadyToAdvance || state.phase == Phase.COMPLETED)
                    val nextLabel = if (showRestart) "Restart" else if (isLastStep) "Stop" else "Next"
                    val nextAction = if (showRestart) vm::reset else vm::next
                    OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { nextAction() }, modifier = Modifier.weight(1f)) { Text(nextLabel) }
                }

                when (state.phase) {
                    Phase.READY -> {
                        val isLastStep = next == null
                        val action = when {
                            state.isReadyToAdvance -> if (isLastStep) vm::reset else vm::next
                            state.isStopwatchRunning -> vm::next
                            step?.startMode == StartMode.MANUAL || step?.durationSec == null -> vm::start
                            else -> vm::start
                        }
                        val label = when {
                            state.isReadyToAdvance -> if (isLastStep) "Restart" else "Next"
                            state.isStopwatchRunning -> if (isLastStep) "Stop" else "Next"
                            step?.startMode == StartMode.MANUAL || step?.durationSec == null -> "Start"
                            else -> "Start"
                        }
                        Button(
                            onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { action() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = accentButtonColors
                        ) {
                            Text(label)
                        }
                        if (state.isStopwatchRunning) {
                            OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.lap() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Lap")
                            }
                            OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Pause")
                            }
                        }
                    }
                    Phase.RUNNING -> {
                        val isLastStep = next == null
                        val label = if (isLastStep) "Stop" else "Next"
                        Button(
                            onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.next() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = accentButtonColors
                        ) {
                            Text(label)
                        }
                        if (step?.durationSec == null) {
                            OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.lap() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Lap")
                            }
                        }
                        OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Pause")
                        }
                    }
                    Phase.PAUSED -> {
                        val isLastStep = next == null
                        val label = if (isLastStep) "Stop" else "Next"
                        Button(
                            onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.next() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = accentButtonColors
                        ) {
                            Text(label)
                        }
                        OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.resume() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Resume")
                        }
                    }
                    Phase.COMPLETED -> Button(
                        onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = accentButtonColors
                    ) {
                        Text("Restart")
                    }
                }
            }
            
            // Laps List
            if (state.laps.isNotEmpty()) {
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f), // Take remaining space or at least some space
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.laps.reversed()) { lap ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Lap ${lap.index}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatTime(lap.lapTimeSec),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Total: ${formatTime(lap.splitTimeSec)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
        if (showHelp) {
            StarterHelpOverlay(onDismiss = { 
                showHelp = false
                vm.markHelpAsShown()
            })
        }
    }
}

private fun formatTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
