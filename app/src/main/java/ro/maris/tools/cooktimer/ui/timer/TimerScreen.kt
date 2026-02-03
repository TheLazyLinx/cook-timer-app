package ro.maris.tools.cooktimer.ui.timer

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
fun TimerScreen(
    vm: TimerViewModel,
) {
    val state = vm.state.collectAsState().value

    // In TimerScreen, state should be non-null almost immediately due to init block in ViewModel
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val sfx = LocalSfxPlayer.current
    val detectorState by vm.detectorState.collectAsState()
    var showHelp by remember { mutableStateOf(false) } // Default to false for these screens? User said copy logic. RunScreen had true by default. I will stick to RunScreen logic if unclear, but "Presets" context made sense for help. "Timer" might not. I will keep it but maybe default false.
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
    // Back navigation logic from RunScreen: canGoBack is step index > 0.
    // In simple Timer/Stopwatch with 1 step, this will likely be false.
    val canGoBack = state.activeStepIndex > 0

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Title
            Text(text = state.presetName, style = MaterialTheme.typography.titleMedium)

            // Timer Display or Picker
            // If READY and not stopwatch running, show picker.
            // Otherwise show standard reading.
            val isEditable = state.phase == Phase.READY && !state.isStopwatchRunning && !state.isReadyToAdvance

            if (isEditable) {
                // Show Duration Picker
                // We use the current duration from the step.
                val currentDuration = step?.durationSec ?: 0
                Box(
                     modifier = Modifier.fillMaxWidth().weight(1f),
                     contentAlignment = Alignment.Center
                ) {
                    DurationPicker(
                        durationSec = currentDuration,
                        onDurationChange = { newDuration ->
                            vm.setDuration(newDuration)
                        }
                    )
                }
            } else {
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
                    state.phase == Phase.READY -> "Ready" // Should be covered by isEditable logic unless isReadyToAdvance
                    else -> ""
                }
                
                // Show standard text
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = timerText,
                            style = timerTextStyle,
                            color = if (isDarkTheme && (state.phase == Phase.RUNNING || state.isStopwatchRunning)) {
                                SoftRed
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
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
                    }
                }
            }

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

            // Removed heavy Spacer(weight) since I used weight in the Box above.
            // But we have buttons below.
            // Actually the Layout above:
            // Column(fillMax)
            //   Header
            //   Box(weight(1f)) -> Center Content
            //   Indicator
            //   Divider
            //   Buttons
            // So SoundDetectionIndicator will be below content box.
            // But I removed the Spacer(Modifier.weight(1f)).
            // The content box uses weight(1f) effectively pushing the rest down?
            // Wait, if box has weight 1, it expands.
            // SoundDetectionIndicator will be pushed towards bottom? No.
            // Column allows multiple children to have weights? Yes.
            // If I want indicator near bottom I should check if I broke the layout.
            // The Box(weight(1f)) takes available space.
            // Elements after it will be pushed down? No, elements after it will be constrained at end of column.
            // So: Header (fixed), Content (Expand), Indicator (fixed), Footer (fixed).
            // This looks correct.
            
            // Spacer(Modifier.weight(1f)) // Removed

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider()

                // "Next step" info is useless for single-step timer, but if we keep it generic...
                if (next != null) {
                    Text(
                        text = "Next: ${next.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Back logic mainly for multi-step
                    if (canGoBack) {
                       OutlinedButton(onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.back() }) { Text("Back") }
                    }
                    val isLastStep = next == null
                    val showRestart = isLastStep && (state.isReadyToAdvance || state.phase == Phase.COMPLETED)
                    val nextLabel = if (showRestart) "Restart" else if (isLastStep) "Stop" else "Next"
                    val nextAction = if (showRestart) vm::reset else vm::next
                    // Hide "Next" logic if it's a single step timer/stopwatch and we are just running/stopping?
                    // But "Stop" is handled here for last step.
                    // If it is the last step (which it is for single step), nextLabel is "Stop".
                    // But wait, "Stop" action is vm::next ?
                    // In RunViewModel, next() advances. If it's last step, next() completes it?
                    // Let's stick to the copied logic.
                    
                    // Actually, if it is running, the main button below handles "Stop" or "Next".
                    // This row is for auxiliary nav? 
                    // In RunScreen:
                    // Row contains "Back" and "Next/Stop/Restart" secondary buttons?
                    // No, look at RunScreen again.
                    /*
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ..., enabled = canGoBack) { Text("Back") }
                        ...
                        OutlinedButton(onClick = ...) { Text(nextLabel) }
                    }
                    */
                    // And then below that, there is the big Primary Button.
                    
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
        }
        if (showHelp) {
            StarterHelpOverlay(onDismiss = { showHelp = false })
        }
    }
}

private fun formatTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
