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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.unit.dp
import ro.maris.tools.cooktimer.ui.run.diamondBorder
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
                else -> {
                    val label = when (state.phase) {
                        Phase.READY -> "READY"
                        Phase.PAUSED -> "PAUSED"
                        Phase.COMPLETED -> "DONE"
                        Phase.RUNNING -> "RUN"
                    }
                    "" to label
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val progress = if ((state.phase == Phase.RUNNING || state.phase == Phase.PAUSED) && step?.durationSec != null) {
                         val total = step.durationSec.toFloat()
                         val current = state.remainingSec?.toFloat() ?: state.pausedRemainingSec?.toFloat() ?: 0f
                         (current / total).coerceIn(0f, 1f)
                    } else if (state.isStopwatchRunning || state.stopwatchElapsedSec != null) {
                         val sec = state.stopwatchElapsedSec ?: 0
                         (sec % 60) / 60f
                    } else {
                         1f
                    }

                    ro.maris.tools.cooktimer.ui.run.CircularSegmentedProgress(
                        progress = progress,
                        modifier = Modifier.size(240.dp),
                        activeColor = SoftRed
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ro.maris.tools.cooktimer.ui.run.SixteenSegmentDisplay(
                                text = timerValue,
                                modifier = Modifier.width(180.dp),
                                segmentColor = SoftRed
                            )
                            
                            // Lap Counter if Stopwatch
                            val laps = state.laps
                            if (!laps.isNullOrEmpty()) {
                                 Spacer(Modifier.height(8.dp))
                                 ro.maris.tools.cooktimer.ui.run.SixteenSegmentDisplay(
                                    text = laps.size.toString().padStart(2, '0'),
                                    modifier = Modifier
                                         .height(30.dp)
                                         .width(50.dp),
                                    segmentColor = SoftRed
                                )
                                 Text(
                                    text = "Laps",
                                    style = MaterialTheme.typography.labelSmall,
                                     color = SoftRed
                                )
                            }
                        }
                    }

                    Text(
                        text = if (timerLabel.isNotEmpty()) "$timerLabel: $timerValue" else timerValue,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            // Laps List
            if (state.laps.isNotEmpty()) {
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f), 
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
            } else {
                 Spacer(Modifier.weight(1f))
            }

            HorizontalDivider()
            
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                 val isRunning = state.phase == Phase.RUNNING || (state.phase == Phase.READY && state.isStopwatchRunning)
                 
                 Row(
                     modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                     horizontalArrangement = Arrangement.SpaceEvenly,
                     verticalAlignment = Alignment.Bottom
                 ) {
                     // Left Button (Lap) - visible if running
                     if (isRunning) {
                         OutlinedButton(
                             onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.lap() },
                             modifier = Modifier.size(64.dp),
                             shape = CircleShape,
                             contentPadding = PaddingValues(0.dp)
                         ) {
                            Text("Lap", style = MaterialTheme.typography.labelMedium)
                        }
                     } else {
                         Spacer(Modifier.size(64.dp))
                     }

                     // Center Button (Start/Pause/Resume/Restart)
                     val (centerIcon, centerAction) = when {
                         isRunning -> Icons.Filled.Pause to { vm.pause() }
                         state.phase == Phase.PAUSED -> Icons.Filled.PlayArrow to { vm.resume() }
                         state.phase == Phase.COMPLETED -> Icons.Filled.Refresh to { vm.reset() }
                         else -> Icons.Filled.PlayArrow to { vm.start() }
                     }

                     Button(
                         onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { centerAction() },
                         modifier = Modifier.size(80.dp), // 25% bigger than 64dp
                         shape = CircleShape,
                         colors = accentButtonColors
                     ) {
                         Icon(
                            imageVector = centerIcon, 
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                         )
                     }

                     // Right Button (Reset) - visible if running or paused
                     val showReset = isRunning || state.phase == Phase.PAUSED
                     if (showReset) {
                         OutlinedButton(
                             onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.reset() },
                             modifier = Modifier.size(64.dp),
                             shape = CircleShape,
                             contentPadding = PaddingValues(0.dp)
                         ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                         }
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
