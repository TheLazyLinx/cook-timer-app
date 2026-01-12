package ro.maris.tools.cooktimer.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ro.maris.tools.cooktimer.domain.model.StartMode

@Composable
fun EditPresetScreen(
    vm: EditPresetViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val state by vm.state.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Button(onClick = { vm.save(onSaved) }) { Text("Save") }
            }

            Text(
                text = "Preset Editor",
                style = MaterialTheme.typography.titleLarge
            )

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
                modifier = Modifier.fillMaxWidth()
            )

            Text(text = "Steps", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                modifier = Modifier.weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.steps, key = { it.id }) { step ->
                    StepEditorCard(
                        step = step,
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

            OutlinedButton(onClick = vm::addStep, modifier = Modifier.fillMaxWidth()) {
                Text("Add Step")
            }
        }
    }
}

@Composable
private fun StepEditorCard(
    step: EditStepItem,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDurationToggle: (Boolean) -> Unit,
    onDurationChange: (String) -> Unit,
    onStartModeChange: (StartMode) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = step.title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = step.durationEnabled,
                    onCheckedChange = onDurationToggle
                )
                Text(text = "Timed step")
                if (step.durationEnabled) {
                    OutlinedTextField(
                        value = step.durationSec,
                        onValueChange = onDurationChange,
                        label = { Text("Seconds") },
                        modifier = Modifier.width(140.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step.startMode == StartMode.AUTO) {
                    Button(onClick = { onStartModeChange(StartMode.AUTO) }) {
                        Text("Auto")
                    }
                    OutlinedButton(onClick = { onStartModeChange(StartMode.MANUAL) }) {
                        Text("Manual")
                    }
                } else {
                    OutlinedButton(onClick = { onStartModeChange(StartMode.AUTO) }) {
                        Text("Auto")
                    }
                    Button(onClick = { onStartModeChange(StartMode.MANUAL) }) {
                        Text("Manual")
                    }
                }
            }

            OutlinedTextField(
                value = step.notes,
                onValueChange = onNotesChange,
                label = { Text("Notes (optional)") },
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
