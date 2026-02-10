package ro.maris.tools.cooktimer.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.ui.theme.SoftRed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import ro.maris.tools.cooktimer.audio.LocalSfxPlayer
import ro.maris.tools.cooktimer.audio.sfxClick
import ro.maris.tools.cooktimer.audio.SfxId
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay

@Composable
fun RunScreen(
    vm: RunViewModel,
    onBack: () -> Unit
) {
    val state = vm.state.collectAsState().value

    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No preset loaded")
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

    val accentTextButtonColors = ButtonDefaults.textButtonColors(
        contentColor = SoftRed
    )
    val canGoBack = state.activeStepIndex > 0
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

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = sfxClick(sfx, SfxId.NavPresets) { onBack() },
            colors = accentTextButtonColors
        ) {
            Text(text = "Presets")
        }
        Text(text = state.presetName, style = MaterialTheme.typography.titleMedium)
        Text(text = step?.title ?: "-", style = MaterialTheme.typography.headlineSmall)

        val (timerLabel, timerValue) = when {
            state.phase == Phase.RUNNING -> "Remaining" to formatTime(requireNotNull(state.remainingSec))
            state.isStopwatchRunning -> "Elapsed" to formatTime(requireNotNull(state.stopwatchElapsedSec))
            state.phase == Phase.PAUSED -> {
                val label = if (step?.durationSec != null) "Remaining" else "Elapsed"
                val timeValue = state.pausedRemainingSec ?: state.stopwatchElapsedSec ?: 0
                label to formatTime(timeValue)
            }
            state.isReadyToAdvance && state.stopwatchElapsedSec != null ->
                "Elapsed" to formatTime(requireNotNull(state.stopwatchElapsedSec))
            state.phase == Phase.READY -> "" to "READY"
            else -> "" to "DONE"
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val progress = if ((state.phase == Phase.RUNNING || state.phase == Phase.PAUSED) && step?.durationSec != null) {
                val total = step.durationSec.toFloat()
                val current = state.remainingSec?.toFloat() ?: state.pausedRemainingSec?.toFloat() ?: 0f
                (current / total).coerceIn(0f, 1f)
            } else if (state.isStopwatchRunning) {
                 // For stopwatch, loop progress every 60s
                 val sec = state.stopwatchElapsedSec ?: 0
                 (sec % 60) / 60f
            } else {
                1f
            }
            
            CircularSegmentedProgress(
                progress = progress,
                modifier = Modifier.size(240.dp),
                activeColor = SoftRed
            ) {
                 NumberDisplay(
                    text = timerValue,
                    useSegmentedDisplay = useSegmentedDisplay,
                    modifier = Modifier.width(180.dp),
                    segmentColor = SoftRed,
                    textStyle = MaterialTheme.typography.displayLarge,
                    textColor = SoftRed
                )
            }

            Text(
                text = if (timerLabel.isNotEmpty()) "$timerLabel: $timerValue" else timerValue,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HorizontalDivider()
            
            Text(
                text = "Next: ${next?.title ?: "-"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Unified Circular Button Layout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 // Left Button (Side Action: Back or Reset if needed)
                 // If running/paused, Left might be "Lap" or "Reset"?
                 // Standardizing on: Left=Back/Lap, Center=Action, Right=Reset/Stop?
                 
                 // Left Slot
                 if ((state.phase == Phase.RUNNING || state.phase == Phase.PAUSED) && step?.durationSec == null) {
                     // Stopwatch Mode -> Lap
                     ActionButton(
                        onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.lap() },
                        icon = { Text("Lap", style = MaterialTheme.typography.labelMedium) },
                        isPrimary = false,
                        size = 64.dp
                     )
                 } else if (canGoBack) {
                     ActionButton(
                         onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.back() },
                         icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") },
                         isPrimary = false,
                         size = 64.dp
                     )
                 } else {
                     Spacer(Modifier.size(64.dp))
                 }

                 Spacer(Modifier.width(24.dp))

                 // Center Slot (Main Action)
                 when (state.phase) {
                    Phase.READY -> {
                         val isLastStep = next == null
                         val readyToAdvance = state.isReadyToAdvance
                         val stopwatchRunning = state.isStopwatchRunning
                         
                         if (stopwatchRunning) {
                             // Stop Stopwatch (Next/Stop) vs Pause
                             // Layout: Lap (Left), Pause (Center), Stop (Right)
                             // Re-think for this block as we are inside "Center Slot" logic
                             // If Stopwatch is running, we are effectively RUNNING phase but internally mapped to READY+flag
                             
                             // Let's use Pause as Center action
                             ActionButton(
                                 onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() },
                                 icon = { Icon(Icons.Default.Pause, "Pause") },
                                 isPrimary = true,
                                 size = 80.dp
                             )
                         } else if (readyToAdvance) {
                             // Step done, waiting for confirmation
                             val icon = if (isLastStep) Icons.Default.Refresh else Icons.Default.SkipNext
                             val action = if (isLastStep) vm::reset else vm::next
                             ActionButton(
                                 onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { action() },
                                 icon = { Icon(icon, "Next") },
                                 isPrimary = true,
                                 size = 80.dp
                             )
                         } else {
                             // Ready to Start
                             ActionButton(
                                 onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.start() },
                                 icon = { Icon(Icons.Default.PlayArrow, "Start") },
                                 isPrimary = true,
                                 size = 80.dp
                             )
                         }
                    }
                    Phase.RUNNING -> {
                        // If timed step running -> Pause is main action? Or Next (Skip)?
                        // Usually Pause is center in Timer apps. Next/Skip is side.
                        // But original code had "Next" as Primary Button and "Pause" as secondary.
                        // Let's stick to "Pause" as center for Timer feel.
                        // Wait, if I Pause, I want to Resume.
                        
                        // New Logic: 
                        // Center = Toggle (Start/Pause/Resume)
                        // Right = Forward (Next/Skip)
                        
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
                 
                 // Right Slot (Next / Skip / Reset)
                 if (state.phase == Phase.RUNNING || state.phase == Phase.PAUSED) {
                      // Skip / Next
                      val isLastStep = next == null
                      val icon = if (isLastStep) Icons.Default.Cancel else Icons.Default.SkipNext // Stop/Skip
                      
                      ActionButton(
                          onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.next() },
                          icon = { Icon(icon, "Next") },
                          isPrimary = false, // Secondary action compared to Pause/Resume
                          size = 64.dp
                      )
                 } else if (state.isStopwatchRunning) {
                      ActionButton(
                          onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() },
                          icon = { Icon(Icons.Default.Pause, "Pause") },
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
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
