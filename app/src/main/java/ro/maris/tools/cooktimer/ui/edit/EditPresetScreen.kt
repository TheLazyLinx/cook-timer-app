package ro.maris.tools.cooktimer.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import ro.maris.tools.cooktimer.audio.LocalSfxPlayer
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.audio.sfxClick
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.ui.theme.LocalAccentColor

@Composable
fun EditPresetScreen(
    vm: EditPresetViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val state by vm.state.collectAsState()
    val sfx = LocalSfxPlayer.current
    val accentButtonColors = androidx.compose.material3.ButtonDefaults.buttonColors(
        containerColor = LocalAccentColor.current,
        contentColor = Color.White
    )
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onSavedWithSfx = {
        sfx.play(SfxId.PresetSaved)
        onSaved()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                focusManager.clearFocus(force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = sfxClick(sfx) { vm.save(onSavedWithSfx) },
                        modifier = Modifier.weight(1f),
                        colors = accentButtonColors
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = sfxClick(sfx) {
                            focusManager.clearFocus(force = true)
                            vm.addStep()
                            sfx.play(SfxId.StepAdded)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add Step")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusManager.clearFocus() }
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = sfxClick(sfx, SfxId.NavPresets) { onBack() },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) { Text("Back") }
                Text(
                    text = "Preset Editor",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            val warningMessage = state.warningMessage
            if (warningMessage != null) {
                Text(
                    text = warningMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::updateName,
                label = { Text("Preset name") },
                isError = state.nameError,
                modifier = Modifier.fillMaxWidth()
            )

            Text(text = "Steps", style = MaterialTheme.typography.titleMedium)

            val listState = rememberLazyListState()
            val firstVisibleId by remember(state.steps, listState) {
                derivedStateOf { state.steps.getOrNull(listState.firstVisibleItemIndex)?.id }
            }
            
            // Scroll to newly added step to ensure it's composed before interaction
            LaunchedEffect(state.steps.size) {
                if (state.steps.isNotEmpty()) {
                    listState.animateScrollToItem(state.steps.size - 1)
                }
            }
            
            LazyColumn(
                modifier = Modifier.weight(1f, fill = true),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.steps, key = { it.id }) { step ->
                    StepEditorCard(
                        step = step,
                        accentButtonColors = accentButtonColors,
                        isPrimaryVisible = step.id == firstVisibleId,
                        isOnlyStep = state.steps.size == 1,
                        showTitleError = state.stepTitleErrors.contains(step.id),
                        onTitleChange = { vm.updateStepTitle(step.id, it) },
                        onNotesChange = { vm.updateStepNotes(step.id, it) },
                        onDurationToggle = {
                            focusManager.clearFocus(force = true)
                            sfx.play(SfxId.ButtonPress)
                            vm.toggleDuration(step.id, it)
                        },
                        onDurationChange = { vm.updateDuration(step.id, it) },
                        onDurationBlur = { vm.sanitizeDuration(step.id) },
                        onStartModeChange = {
                            sfx.play(SfxId.ButtonPress)
                            vm.updateStartMode(step.id, it)
                        },
                        onMoveUp = sfxClick(sfx) {
                            focusManager.clearFocus(force = true)
                            vm.moveStepUp(step.id)
                        },
                        onMoveDown = sfxClick(sfx) {
                            focusManager.clearFocus(force = true)
                            vm.moveStepDown(step.id)
                        },
                        onDelete = sfxClick(sfx) {
                            focusManager.clearFocus(force = true)
                            vm.removeStep(step.id)
                        },
                        onAutoParse = { sfx.play(SfxId.AutoParse) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepEditorCard(
    step: EditStepItem,
    accentButtonColors: androidx.compose.material3.ButtonColors,
    isPrimaryVisible: Boolean,
    isOnlyStep: Boolean,
    showTitleError: Boolean,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDurationToggle: (Boolean) -> Unit,
    onDurationChange: (String) -> Unit,
    onDurationBlur: () -> Unit,
    onStartModeChange: (StartMode) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onAutoParse: () -> Unit
) {
    val durationSecValue = step.durationSec.toIntOrNull() ?: 0
    val durationMinutes = if (step.durationSec.isBlank()) "" else (durationSecValue / 60).toString()
    val durationSeconds = if (step.durationSec.isBlank()) "" else (durationSecValue % 60).toString()

    val cardModifier = if (isOnlyStep) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().heightIn(min = 220.dp, max = if (isPrimaryVisible) 420.dp else 320.dp)
    }
    Card(modifier = cardModifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isOnlyStep) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = step.title,
                onValueChange = onTitleChange,
                label = { Text("Title", style = MaterialTheme.typography.labelLarge) },
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                isError = showTitleError,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Timed step",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = step.durationEnabled,
                    onCheckedChange = onDurationToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LocalAccentColor.current,
                        checkedTrackColor = LocalAccentColor.current.copy(alpha = 0.4f),
                        checkedBorderColor = LocalAccentColor.current
                    )
                )
            }
            if (step.durationEnabled) {
                Text(text = "Duration", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = durationMinutes,
                        onValueChange = { value ->
                            val cleaned = value.filter { it.isDigit() }.take(3)
                            if (cleaned != value) {
                                onAutoParse()
                            }
                            val minutes = cleaned.toIntOrNull() ?: 0
                            val seconds = durationSeconds.toIntOrNull() ?: 0
                            val total = minutes * 60 + seconds
                            val next = if (cleaned.isBlank() && durationSeconds.isBlank()) "" else total.toString()
                            onDurationChange(next)
                        },
                        label = { Text("Min") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    onDurationBlur()
                                }
                            }
                    )
                    OutlinedTextField(
                        value = durationSeconds,
                        onValueChange = { value ->
                            val minutes = durationMinutes.toIntOrNull() ?: 0
                            val cleaned = value.filter { it.isDigit() }.take(2)
                            if (cleaned != value) {
                                onAutoParse()
                            }
                            val seconds = cleaned.toIntOrNull() ?: 0
                            val total = minutes * 60 + seconds
                            val next = if (durationMinutes.isBlank() && cleaned.isBlank()) "" else total.toString()
                            onDurationChange(next)
                        },
                        label = { Text("Sec") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    onDurationBlur()
                                }
                            }
                    )
                }
            }

            Text(text = "Start mode", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step.startMode == StartMode.AUTO) {
                    Button(
                        onClick = { onStartModeChange(StartMode.AUTO) },
                        colors = accentButtonColors
                    ) {
                        Text("Auto")
                    }
                    OutlinedButton(onClick = { onStartModeChange(StartMode.MANUAL) }) {
                        Text("Manual")
                    }
                } else {
                    OutlinedButton(onClick = { onStartModeChange(StartMode.AUTO) }) {
                        Text("Auto")
                    }
                    Button(
                        onClick = { onStartModeChange(StartMode.MANUAL) },
                        colors = accentButtonColors
                    ) {
                        Text("Manual")
                    }
                }
            }

            OutlinedTextField(
                value = step.notes,
                onValueChange = onNotesChange,
                label = { Text("Notes (optional)", style = MaterialTheme.typography.labelLarge) },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = if (step.notes.isBlank()) 1 else 3,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMoveUp) { Text("Move Up") }
                TextButton(onClick = onMoveDown) { Text("Move Down") }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
