package ro.maris.tools.cooktimer.ui.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import ro.maris.tools.cooktimer.domain.model.Preset

@Composable
fun PresetsListScreen(
    vm: PresetsListViewModel,
    onRun: (String) -> Unit,
    onEdit: (String?) -> Unit
) {
    BackHandler(enabled = true) {
        // Consume back press on the presets root screen.
    }
    val state by vm.state.collectAsState()

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = { vm.onNewPresetClick { onEdit(null) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("New Preset")
                }
                if (state.showUpgradeHint) {
                    Text(
                        text = "Unlock more presets by purchasing the app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = vm::dismissUpgradeHint) {
                        Text("Upgrade Later")
                    }
                }
            }
            item {
                SectionHeader("Built-in Presets")
            }
            items(state.builtIns, key = { it.id }) { preset ->
                PresetRow(
                    preset = preset,
                    onRun = { onRun(preset.id) },
                    onDuplicate = null,
                    onEdit = null,
                    onDelete = null
                )
            }
            item {
                SectionHeader("My Presets")
            }
            items(state.customPresets, key = { it.id }) { preset ->
                PresetRow(
                    preset = preset,
                    onRun = { onRun(preset.id) },
                    onDuplicate = null,
                    onEdit = { onEdit(preset.id) },
                    onDelete = { vm.deletePreset(preset) }
                )
            }
            if (state.customPresets.isEmpty()) {
                item {
                    Text(
                        text = "No custom presets yet",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PresetRow(
    preset: Preset,
    onRun: () -> Unit,
    onDuplicate: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val hasActions = onDuplicate != null || onEdit != null || onDelete != null
    var menuExpanded by remember { mutableStateOf(false) }

    Column {
        TextButton(onClick = onRun) {
            Text(text = preset.name, style = MaterialTheme.typography.bodyLarge)
        }
        if (hasActions) {
            TextButton(onClick = { menuExpanded = true }) {
                Text(text = "Actions")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (onDuplicate != null) {
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        }
                    )
                }
                if (onEdit != null) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
