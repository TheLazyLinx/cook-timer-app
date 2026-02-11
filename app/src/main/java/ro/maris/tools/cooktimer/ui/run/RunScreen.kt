package ro.maris.tools.cooktimer.ui.run

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.maris.tools.cooktimer.audio.LocalSfxPlayer
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.audio.sfxClick
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.ui.theme.SoftRed

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
    val useSegmentedDisplay = settings?.segmentedDisplayEnabled ?: true
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showHelp by remember { mutableStateOf(false) }

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
    val notesText = step?.notes
    val canGoBack = state.activeStepIndex > 0
    val mainActionColor = SoftRed
    val secondaryActionColor = MaterialTheme.colorScheme.primary
    val buttonBgColor = MaterialTheme.colorScheme.surface

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

    @Composable
    fun ClockArea(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
    }

    @Composable
    fun ControlPanel(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HorizontalDivider()
            Text(
                text = "Next: ${next?.title ?: "-"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if ((state.phase == Phase.RUNNING || state.phase == Phase.PAUSED) && step?.durationSec == null) {
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

                when (state.phase) {
                    Phase.READY -> {
                        val isLastStep = next == null
                        val readyToAdvance = state.isReadyToAdvance
                        val stopwatchRunning = state.isStopwatchRunning

                        if (stopwatchRunning) {
                            ActionButton(
                                onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() },
                                icon = { Icon(Icons.Default.Pause, "Pause") },
                                isPrimary = true,
                                size = 80.dp
                            )
                        } else if (readyToAdvance) {
                            val icon = if (isLastStep) Icons.Default.Refresh else Icons.Default.SkipNext
                            val action = if (isLastStep) vm::reset else vm::next
                            ActionButton(
                                onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { action() },
                                icon = { Icon(icon, "Next") },
                                isPrimary = true,
                                size = 80.dp
                            )
                        } else {
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

                if (state.phase == Phase.RUNNING || state.phase == Phase.PAUSED) {
                    val isLastStep = next == null
                    val icon = if (isLastStep) Icons.Default.Cancel else Icons.Default.SkipNext
                    ActionButton(
                        onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.next() },
                        icon = { Icon(icon, "Next") },
                        isPrimary = false,
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

    val accentTextButtonColors = ButtonDefaults.textButtonColors(contentColor = SoftRed)

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        ClockArea()
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.8f)
                            .fillMaxHeight()
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = when (state.phase) {
                                    Phase.READY -> "READY"
                                    Phase.RUNNING -> "RUNNING"
                                    Phase.PAUSED -> "PAUSED"
                                    Phase.COMPLETED -> "COMPLETED"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            if (!notesText.isNullOrBlank()) {
                                Text(
                                    text = notesText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.widthIn(max = 320.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        RetroArcSoundIndicator(
                            state = detectorState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            isPaused = state.phase == Phase.PAUSED
                        )
                        ControlPanel()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClockArea()
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    isPaused = state.phase == Phase.PAUSED
                )

                Spacer(Modifier.weight(1f))
                ControlPanel()
            }
        }

        if (showHelp) {
            StarterHelpOverlay(
                onDismiss = {
                    showHelp = false
                    vm.markHelpAsShown()
                }
            )
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
