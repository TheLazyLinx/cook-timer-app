package ro.maris.tools.cooktimer.ui.timer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.delay
import ro.maris.tools.cooktimer.audio.LocalSfxPlayer
import ro.maris.tools.cooktimer.audio.sfxClick
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.ui.theme.SoftRed
import ro.maris.tools.cooktimer.ui.run.StarterHelpOverlay
import ro.maris.tools.cooktimer.ui.run.RetroArcSoundIndicator
import ro.maris.tools.cooktimer.ui.run.NumberDisplay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack

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
    val settings by vm.settings.collectAsState(initial = null)
    var showHelp by remember { mutableStateOf(false) }
    val useSegmentedDisplay = settings?.segmentedDisplayEnabled ?: true
    
    // Show help if not shown before
    LaunchedEffect(settings) {
        if (settings?.hasShownSoundHelpOverlay == false) {
            showHelp = true
        }
    }
    
    val lastState = remember { mutableStateOf(state) }

    DisposableEffect(Unit) {
        vm.onVisibilityChange(true)
        onDispose {
            vm.onVisibilityChange(false)
        }
    }

    LaunchedEffect(state) {
        val prev = lastState.value
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
    }
    SideEffect {
        lastState.value = state
    }

    val step = state.activeStep
    val next = state.nextStep

    val mainActionColor = SoftRed
    val secondaryActionColor = MaterialTheme.colorScheme.primary
    val buttonBgColor = MaterialTheme.colorScheme.surface

    // Helper for styled buttons
    @Composable
    fun ActionButton(
        onClick: () -> Unit,
        icon: @Composable () -> Unit,
        isPrimary: Boolean = true,
        size: androidx.compose.ui.unit.Dp = 80.dp
    ) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            shape = CircleShape, 
            border = BorderStroke(if (isPrimary) 3.dp else 2.dp, if (isPrimary) mainActionColor else secondaryActionColor),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = buttonBgColor,
                contentColor = if (isPrimary) mainActionColor else secondaryActionColor
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            icon()
        }
    }

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
                        },
                        useSegmentedDisplay = useSegmentedDisplay
                    )
                }
            } else {
                val timerValue = when {
                    state.phase == Phase.RUNNING -> formatTime(requireNotNull(state.remainingSec))
                    state.isStopwatchRunning -> formatTime(requireNotNull(state.stopwatchElapsedSec))
                    state.phase == Phase.PAUSED -> {
                        val timeValue = state.pausedRemainingSec ?: state.stopwatchElapsedSec ?: 0
                        formatTime(timeValue)
                    }
                    state.isReadyToAdvance && state.stopwatchElapsedSec != null ->
                        formatTime(requireNotNull(state.stopwatchElapsedSec))
                    state.phase == Phase.READY -> "READY"
                    else -> ""
                }
                
                val progress = if ((state.phase == Phase.RUNNING || state.phase == Phase.PAUSED) && step?.durationSec != null) {
                    val total = step.durationSec.toFloat()
                    val current = state.remainingSec?.toFloat() ?: state.pausedRemainingSec?.toFloat() ?: 0f
                    (current / total).coerceIn(0f, 1f)
                } else if (state.isStopwatchRunning) {
                     val sec = state.stopwatchElapsedSec ?: 0
                     (sec % 60) / 60f
                } else {
                    1f
                }

                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                      ro.maris.tools.cooktimer.ui.run.CircularSegmentedProgress(
                        progress = progress,
                        modifier = Modifier.size(240.dp),
                        activeColor = SoftRed
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             NumberDisplay(
                                text = timerValue,
                                useSegmentedDisplay = useSegmentedDisplay,
                                modifier = Modifier.width(180.dp),
                                segmentColor = SoftRed,
                                textStyle = MaterialTheme.typography.displayLarge,
                                textColor = SoftRed
                            )
                        }
                    }
                }
            }

            val notesText = step?.notes
            if (!notesText.isNullOrBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = notesText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 360.dp)
                    )
                }
            }

            RetroArcSoundIndicator(
                state = detectorState, 
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                isPaused = state.phase == Phase.PAUSED
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Slot: Back (if stack available)
                    if (canGoBack) {
                       ActionButton(
                           onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.back() },
                           icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") },
                           isPrimary = false,
                           size = 64.dp
                       )
                    } else {
                        // Or if timer is running and we want Reset on left?
                        // Requirement: Start/Pause/Restart and Reset.
                        // Usually Reset is on the side. 
                        // If Running/Paused, Right Slot can be Reset?
                        // Let's stick to Right Slot for Reset.
                        Spacer(Modifier.size(64.dp))
                    }
                    
                    Spacer(Modifier.width(24.dp))

                    // Center Slot (Main Toggle)
                     when (state.phase) {
                        Phase.READY -> {
                             // Start or Restart
                             // If isReadyToAdvance, it means we ran and finished?
                             // But for Single Step Timer, phase goes to COMPLETED usually?
                             // vm.start()
                             
                             // If just opened: Phase.READY -> Play
                             // If we finished a loop? Phase.COMPLETED -> Refresh
                             
                             // logic from before:
                             val readyToAdvance = state.isReadyToAdvance
                             val stopwatchRunning = state.isStopwatchRunning
                             
                             if (stopwatchRunning) { 
                                 // Pause
                                 ActionButton(
                                     onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() },
                                     icon = { Icon(Icons.Default.Pause, "Pause") },
                                     isPrimary = true,
                                     size = 80.dp
                                 )
                             } else if (readyToAdvance) {
                                 // Done? Refresh
                                 ActionButton(
                                     onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.reset() },
                                     icon = { Icon(Icons.Default.Refresh, "Restart") },
                                     isPrimary = true,
                                     size = 80.dp
                                 )
                             } else {
                                 // Start
                                 ActionButton(
                                     onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.start() },
                                     icon = { Icon(Icons.Default.PlayArrow, "Start") },
                                     isPrimary = true,
                                     size = 80.dp
                                 )
                             }
                        }
                        Phase.RUNNING -> {
                            ActionButton(
                                onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() },
                                icon = { Icon(Icons.Default.Pause, "Pause") },
                                isPrimary = true,
                                size = 80.dp
                            )
                        }
                        Phase.PAUSED -> {
                             ActionButton(
                                onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.resume() },
                                icon = { Icon(Icons.Default.PlayArrow, "Resume") },
                                isPrimary = true,
                                size = 80.dp
                            )
                        }
                        Phase.COMPLETED -> {
                             ActionButton(
                                onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.reset() },
                                icon = { Icon(Icons.Default.Refresh, "Restart") },
                                isPrimary = true,
                                size = 80.dp
                            )
                        }
                     }
                     
                     Spacer(Modifier.width(24.dp))
                     
                     // Right Slot (Reset)
                     // Visible if Running? Usually not. Only Paused?
                     // Request: "next for the timer we only need Start/Pause/Restart and Reset."
                     // Usually Reset is available when Paused.
                     if (state.phase == Phase.RUNNING || state.phase == Phase.PAUSED || state.phase == Phase.COMPLETED || state.isStopwatchRunning) {
                         ActionButton(
                             onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.reset() },
                             icon = { Icon(Icons.Default.Refresh, "Reset") },
                             isPrimary = false,
                             size = 64.dp
                         )
                     } else {
                         Spacer(Modifier.size(64.dp))
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
