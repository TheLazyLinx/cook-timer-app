package ro.maris.tools.cooktimer.ui.timer

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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import ro.maris.tools.cooktimer.ui.run.NumberDisplay
import ro.maris.tools.cooktimer.ui.run.RetroArcSoundIndicator
import ro.maris.tools.cooktimer.ui.run.StarterHelpOverlay
import ro.maris.tools.cooktimer.ui.theme.SoftRed

@Composable
fun TimerScreen(
    vm: TimerViewModel,
) {
    val state = vm.state.collectAsState().value

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
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
    val canEditDuration = state.phase == Phase.READY && !state.isStopwatchRunning && !state.isReadyToAdvance
    val hasDurationSelected = (step?.durationSec ?: 0) > 0

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

    val mainActionColor = SoftRed
    val secondaryActionColor = MaterialTheme.colorScheme.primary
    val buttonBgColor = MaterialTheme.colorScheme.surface

    @Composable
    fun ActionButton(
        onClick: () -> Unit,
        icon: @Composable () -> Unit,
        isPrimary: Boolean = true,
        enabled: Boolean = true,
        size: androidx.compose.ui.unit.Dp = 80.dp
    ) {
        val activeColor = if (isPrimary) mainActionColor else secondaryActionColor
        val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        val resolvedColor = if (enabled) activeColor else disabledColor
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            enabled = enabled,
            shape = CircleShape,
            border = BorderStroke(if (isPrimary) 3.dp else 2.dp, resolvedColor),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = buttonBgColor,
                contentColor = activeColor,
                disabledContainerColor = buttonBgColor,
                disabledContentColor = disabledColor
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            icon()
        }
    }

    @Composable
    fun TimeArea(modifier: Modifier = Modifier) {
        if (canEditDuration) {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DurationPicker(
                    durationSec = step?.durationSec ?: 0,
                    onDurationChange = { vm.setDuration(it) },
                    useSegmentedDisplay = useSegmentedDisplay
                )
            }
        } else {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                ro.maris.tools.cooktimer.ui.run.CircularSegmentedProgress(
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
            }
        }
    }

    @Composable
    fun ControlPanel(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

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
                if (canGoBack) {
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
                        when {
                            state.isStopwatchRunning -> {
                                ActionButton(
                                    onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.pause() },
                                    icon = { Icon(Icons.Default.Pause, "Pause") },
                                    isPrimary = true,
                                    size = 80.dp
                                )
                            }
                            state.isReadyToAdvance -> {
                                ActionButton(
                                    onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.reset() },
                                    icon = { Icon(Icons.Default.Refresh, "Restart") },
                                    isPrimary = true,
                                    size = 80.dp
                                )
                            }
                            else -> {
                                ActionButton(
                                    onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.start() },
                                    icon = { Icon(Icons.Default.PlayArrow, "Start") },
                                    isPrimary = true,
                                    enabled = hasDurationSelected,
                                    size = 80.dp
                                )
                            }
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

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = state.presetName, style = MaterialTheme.typography.titleMedium)

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
                        TimeArea()
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.8f)
                            .fillMaxHeight()
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!notesText.isNullOrBlank()) {
                            Text(
                                text = notesText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.widthIn(max = 320.dp)
                            )
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    TimeArea()
                }

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
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
