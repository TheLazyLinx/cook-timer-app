package ro.maris.tools.cooktimer.ui.stopwatch

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.maris.tools.cooktimer.audio.LocalSfxPlayer
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.audio.sfxClick
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.ui.run.NumberDisplay
import ro.maris.tools.cooktimer.ui.run.RetroArcSoundIndicator
import ro.maris.tools.cooktimer.ui.run.StarterHelpOverlay
import ro.maris.tools.cooktimer.ui.theme.LocalAccentColor

@Composable
fun StopwatchScreen(
    vm: StopwatchViewModel,
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
    val historyState by vm.historyState.collectAsState()
    val isPremium by vm.isPremium.collectAsState()
    val useSegmentedDisplay = settings?.segmentedDisplayEnabled ?: true
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showHelp by remember { mutableStateOf(false) }
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Stopwatch", "History")

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
    val notesText = step?.notes

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
    } else if (state.isStopwatchRunning || state.stopwatchElapsedSec != null) {
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
            ro.maris.tools.cooktimer.ui.run.CircularSegmentedProgress(
                progress = progress,
                modifier = Modifier.size(240.dp),
                activeColor = LocalAccentColor.current
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NumberDisplay(
                        text = timerValue,
                        useSegmentedDisplay = useSegmentedDisplay,
                        modifier = Modifier.width(180.dp),
                        segmentColor = LocalAccentColor.current,
                        textStyle = MaterialTheme.typography.displayLarge,
                        textColor = LocalAccentColor.current
                    )

                    if (state.laps.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        NumberDisplay(
                            text = state.laps.size.toString().padStart(2, '0'),
                            useSegmentedDisplay = useSegmentedDisplay,
                            modifier = Modifier
                                .size(width = 50.dp, height = 30.dp),
                            segmentColor = LocalAccentColor.current,
                            textStyle = MaterialTheme.typography.titleMedium,
                            textColor = LocalAccentColor.current
                        )
                        Text(
                            text = "Laps",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalAccentColor.current
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

    @Composable
    fun Controls(modifier: Modifier = Modifier) {
        val isRunning = state.phase == Phase.RUNNING || (state.phase == Phase.READY && state.isStopwatchRunning)
        val mainActionColor = LocalAccentColor.current
        val secondaryActionColor = MaterialTheme.colorScheme.primary
        val buttonBgColor = MaterialTheme.colorScheme.surface

        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                if (isRunning) {
                    OutlinedButton(
                        onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.lap() },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        border = BorderStroke(2.dp, secondaryActionColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = buttonBgColor,
                            contentColor = secondaryActionColor
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Lap", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Spacer(Modifier.size(64.dp))
                }

                val (centerIcon, centerAction) = when {
                    isRunning -> Icons.Filled.Pause to { vm.pause() }
                    state.phase == Phase.PAUSED -> Icons.Filled.PlayArrow to { vm.resume() }
                    state.phase == Phase.COMPLETED -> Icons.Filled.Refresh to { vm.reset() }
                    else -> Icons.Filled.PlayArrow to { vm.start() }
                }

                OutlinedButton(
                    onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { centerAction() },
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    border = BorderStroke(3.dp, mainActionColor),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = buttonBgColor,
                        contentColor = mainActionColor
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = centerIcon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }

                val showReset = isRunning || state.phase == Phase.PAUSED
                if (showReset) {
                    OutlinedButton(
                        onClick = sfxClick(sfx, SfxId.RunPrimaryNext) { vm.reset() },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        border = BorderStroke(2.dp, secondaryActionColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = buttonBgColor,
                            contentColor = secondaryActionColor
                        ),
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

    @Composable
    fun ColumnScope.LapList(modifier: Modifier = Modifier) {
        if (state.laps.isNotEmpty()) {
            HorizontalDivider()
            LazyColumn(
                modifier = modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.laps.reversed()) { lap ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
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
    }

    @Composable
    fun ColumnScope.HistoryTab(modifier: Modifier = Modifier) {
        val recent = historyState.recent
        val saved = historyState.saved

        if (recent == null && saved == null) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No history yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Most recent (auto-managed)",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.saveRecentHistory() },
                        enabled = recent != null
                    ) {
                        Text("Save")
                    }
                }
                if (recent == null) {
                    Text(
                        text = "No recent session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Total: ${formatTime(recent.totalElapsedSec)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Laps: ${recent.laps.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isPremium) {
                        if (recent.laps.isEmpty()) {
                            Text(
                                text = "No laps recorded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            recent.laps.reversed().forEach { lap ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
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
                        Text(
                            text = "Upgrade to view lap-by-lap history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                HorizontalDivider()
                Text(
                    text = "Saved",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = sfxClick(sfx, SfxId.RunNavRow) { vm.clearSavedHistory() },
                        enabled = saved != null
                    ) {
                        Text("Clear")
                    }
                }
                if (saved == null) {
                    Text(
                        text = "No saved session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Total: ${formatTime(saved.totalElapsedSec)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Laps: ${saved.laps.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isPremium) {
                        if (saved.laps.isEmpty()) {
                            Text(
                                text = "No laps recorded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            saved.laps.reversed().forEach { lap ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
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
                        Text(
                            text = "Upgrade to view lap-by-lap history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            if (selectedTabIndex == 0) {
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
                            if (state.laps.isNotEmpty()) {
                                val latestLap = state.laps.last()
                                Text(
                                    text = "Latest lap: ${formatTime(latestLap.lapTimeSec)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RetroArcSoundIndicator(
                                state = detectorState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                isPaused = state.phase == Phase.PAUSED
                            )
                            Controls()
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
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

                    LapList()
                    Controls()
                }
            } else {
                HistoryTab()
            }
        }

        if (showHelp && selectedTabIndex == 0) {
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
