package ro.maris.tools.cooktimer.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.ui.theme.SoftRed

@Composable
fun EditPresetScreen(
    vm: EditPresetViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val state by vm.state.collectAsState()
    val accentButtonColors = androidx.compose.material3.ButtonDefaults.buttonColors(
        containerColor = SoftRed,
        contentColor = Color.White
    )
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
                        onClick = { vm.save(onSaved) },
                        modifier = Modifier.weight(1f),
                        colors = accentButtonColors
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = vm::addStep,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add Step")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onBack,
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
            val firstVisibleId by derivedStateOf {
                state.steps.getOrNull(listState.firstVisibleItemIndex)?.id
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
                        onDurationToggle = { vm.toggleDuration(step.id, it) },
                        onDurationChange = { vm.updateDuration(step.id, it) },
                        onStartModeChange = { vm.updateStartMode(step.id, it) },
                        onMoveUp = { vm.moveStepUp(step.id) },
                        onMoveDown = { vm.moveStepDown(step.id) },
                        onDelete = { vm.removeStep(step.id) }
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
    onStartModeChange: (StartMode) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val durationSecValue = step.durationSec.toIntOrNull() ?: 0
    val durationMinutes = (durationSecValue / 60).toString()
    val durationSeconds = (durationSecValue % 60).toString()

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
                        checkedThumbColor = SoftRed,
                        checkedTrackColor = SoftRed.copy(alpha = 0.4f),
                        checkedBorderColor = SoftRed
                    )
                )
            }
            if (step.durationEnabled) {
                Text(text = "Duration", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = durationMinutes,
                        onValueChange = { value ->
                            val minutes = value.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                            val seconds = durationSeconds.toIntOrNull() ?: 0
                            onDurationChange((minutes * 60 + seconds).toString())
                        },
                        label = { Text("Min") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = durationSeconds,
                        onValueChange = { value ->
                            val minutes = durationMinutes.toIntOrNull() ?: 0
                            val seconds = value.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0
                            onDurationChange((minutes * 60 + seconds).toString())
                        },
                        label = { Text("Sec") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
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
