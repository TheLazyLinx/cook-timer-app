package ro.maris.tools.cooktimer.ui.presets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.ui.theme.SoftRed

@Composable
fun PresetsListScreen(
    vm: PresetsListViewModel,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onRun: (String) -> Unit,
    onEdit: (String?) -> Unit
) {
    BackHandler(enabled = true) {
        // Consume back press on the presets root screen.
    }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDuplicate by remember { mutableStateOf<Preset?>(null) }
    val accentButtonColors = ButtonDefaults.buttonColors(
        containerColor = SoftRed,
        contentColor = Color.White
    )
    val disabledButtonColors = ButtonDefaults.buttonColors(
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val toggleLabel = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode"

    if (pendingDuplicate != null) {
        AlertDialog(
            onDismissRequest = { pendingDuplicate = null },
            title = { Text("Replace custom preset?") },
            text = { Text("Free version supports 1 custom preset. Continue to replace it?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val preset = pendingDuplicate
                        pendingDuplicate = null
                        if (preset != null) {
                            vm.duplicatePreset(
                                source = preset,
                                replaceExisting = true
                            ) { newId ->
                                onEdit(newId)
                            }
                        }
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDuplicate = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                if (state.showUpgradeHint) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Unlock more presets by purchasing the app.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = vm::dismissUpgradeHint) {
                            Text("Upgrade Later")
                        }
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
                    onDuplicate = {
                        if (state.isCustomLimitReached) {
                            pendingDuplicate = preset
                        } else {
                            vm.duplicatePreset(source = preset, replaceExisting = false) { newId ->
                                onEdit(newId)
                            }
                        }
                    },
                    onEdit = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Built-in presets can't be edited.")
                        }
                    },
                    onDelete = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Built-in presets can't be deleted.")
                        }
                    }
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                SectionHeader("My Presets")
            }
            if (state.customPresets.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Create a preset to save step timers and run them hands-free.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { vm.onNewPresetClick { onEdit(null) } },
                            modifier = Modifier.fillMaxWidth(),
                            colors = accentButtonColors
                        ) {
                            Text("New Preset")
                        }
                    }
                }
            }
            items(state.customPresets, key = { it.id }) { preset ->
                PresetRow(
                    preset = preset,
                    onRun = { onRun(preset.id) },
                    onDuplicate = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Free version supports 1 custom preset.")
                        }
                    },
                    onEdit = { onEdit(preset.id) },
                    onDelete = { vm.deletePreset(preset) }
                )
            }
            if (state.customPresets.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            colors = disabledButtonColors
                        ) {
                            Text("New Preset")
                        }
                        Text(
                            text = "Free version supports 1 custom preset.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Delete your existing preset to create a new one.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onToggleTheme,
                    modifier = Modifier.fillMaxWidth(),
                    colors = accentButtonColors
                ) {
                    Text(toggleLabel)
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
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        ListItem(
            headlineContent = { Text(text = preset.name, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                Text(text = presetSummary(preset), style = MaterialTheme.typography.bodySmall)
            },
            leadingContent = {
                Icon(imageVector = Icons.Outlined.Timer, contentDescription = null)
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit) {
                        Icon(imageVector = Icons.Outlined.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = onDuplicate) {
                        Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = "Duplicate")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRun)
        )
    }
}

private fun presetSummary(preset: Preset): String {
    val stepCount = preset.steps.size
    val stepLabel = if (stepCount == 1) "1 step" else "$stepCount steps"
    val timedSec = preset.steps.sumOf { it.durationSec ?: 0 }
    if (timedSec <= 0) {
        return stepLabel
    }
    val minutes = (timedSec + 59) / 60
    val base = "$stepLabel • $minutes min"
    val hasUntimed = preset.steps.any { it.durationSec == null }
    return if (hasUntimed) "$base + Extra time" else base
}
