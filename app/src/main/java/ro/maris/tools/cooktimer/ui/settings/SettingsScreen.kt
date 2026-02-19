package ro.maris.tools.cooktimer.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onSoundwave: () -> Unit,
    onDiagnostics: () -> Unit = {}
) {
    val settings by vm.settings.collectAsState()
    val isPremium by vm.isPremium.collectAsState()
    val focusManager = LocalFocusManager.current
    val activity = LocalContext.current as? Activity
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("General", "Audio & Haptics")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (selectedTabIndex == 0) {
                // Theme Section
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Theme",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Choose your preferred theme",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        ThemeModeDropdown(
                            selectedMode = settings.themeMode,
                            onModeSelected = { vm.setThemeMode(it) }
                        )
                        
                        Text(
                            text = when (settings.themeMode) {
                                ThemeMode.SYSTEM -> "Follows your device's theme settings"
                                ThemeMode.LIGHT -> "Always use light theme"
                                ThemeMode.DARK -> "Always use dark theme"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Display Section
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Display",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Use standard numbers instead of segmented digits",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Disable Segmented Display")
                            Switch(
                                checked = !settings.segmentedDisplayEnabled,
                                onCheckedChange = { checked ->
                                    vm.setSegmentedDisplayEnabled(!checked)
                                }
                            )
                        }
                    }
                }

                // Premium Section
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Premium",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (isPremium) {
                                "Premium is active. Thanks for supporting the app."
                            } else {
                                "Unlock unlimited presets, rich stopwatch history, and gesture mapping."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { vm.startPremiumPurchase(activity) },
                            enabled = !isPremium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isPremium) "Premium Active" else "Go Premium")
                        }
                    }
                }
            } else {
                // Sound Effects Section
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Sound Effects",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Play audio feedback for timer events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Enable sound effects")
                            Switch(
                                checked = settings.soundEffectsEnabled,
                                onCheckedChange = { vm.setSoundEffectsEnabled(it) }
                            )
                        }
                    }
                }

                // Haptics Section
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Haptics",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Vibrate on timer events and interactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Enable haptics")
                            Switch(
                                checked = settings.hapticsEnabled,
                                onCheckedChange = { vm.setHapticsEnabled(it) }
                            )
                        }
                    }
                }

                // Sound Detection Section
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Sound Gesture Control",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Control runs using sound tap patterns",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Enable sound detection")
                            Switch(
                                checked = settings.soundDetectionEnabled,
                                onCheckedChange = { vm.setSoundDetectionEnabled(it) }
                            )
                        }

                        if (settings.soundDetectionEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Free mode",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Double tap: Start / Pause / Resume",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Triple tap function: Stopwatch = Lap, Preset Run = Parse",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Gesture triggers: Snap and Clap only (Knock is reserved for a later paid release).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Gesture Mapping Section
                if (settings.soundDetectionEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Gesture Mapping",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (isPremium) {
                                    "Customize double and triple tap actions."
                                } else {
                                    "Premium feature. Double tap = Start/Pause/Resume. Triple tap = Lap/Parse."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            GestureActionDropdown(
                                label = "Double tap",
                                selected = settings.doubleTapAction,
                                enabled = isPremium,
                                onActionSelected = { vm.setDoubleTapAction(it) }
                            )

                            GestureActionDropdown(
                                label = "Triple tap",
                                selected = settings.tripleTapAction,
                                enabled = isPremium,
                                onActionSelected = { vm.setTripleTapAction(it) }
                            )

                            if (!isPremium) {
                                Button(
                                    onClick = { vm.startPremiumPurchase(activity) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Go Premium")
                                }
                            }
                        }
                    }
                }
                
                // Sound Test Section
                if (settings.soundDetectionEnabled) {
                    SoundTestSection(vm = vm, onSoundwave = onSoundwave, onDiagnostics = onDiagnostics)
                }

                // Informational Card
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "About Sound Gestures",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Sound gesture control listens for short loud taps. Free mode uses fixed behavior: Double tap starts/pauses/resumes, Triple tap triggers function (Lap in Stopwatch, Parse in Preset Run). Microphone permission is required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeDropdown(
    selectedMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = isExpanded,
        onExpandedChange = { isExpanded = it }
    ) {
        OutlinedTextField(
            value = selectedMode.toDisplayString(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Theme Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        
        ExposedDropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false }
        ) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.toDisplayString()) },
                    onClick = {
                        onModeSelected(mode)
                        isExpanded = false
                    }
                )
            }
        }
    }
}


private fun ThemeMode.toDisplayString(): String = when (this) {
    ThemeMode.SYSTEM -> "System Default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureActionDropdown(
    label: String,
    selected: SoundTriggerAction,
    enabled: Boolean,
    onActionSelected: (SoundTriggerAction) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val actions = listOf(
        SoundTriggerAction.TOGGLE_PLAY_PAUSE,
        SoundTriggerAction.START_RESUME,
        SoundTriggerAction.PAUSE,
        SoundTriggerAction.NEXT_STEP,
        SoundTriggerAction.LAP,
        SoundTriggerAction.PARSE,
        SoundTriggerAction.NONE
    )

    ExposedDropdownMenuBox(
        expanded = isExpanded,
        onExpandedChange = { if (enabled) isExpanded = it }
    ) {
        OutlinedTextField(
            value = selected.toDisplayString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false }
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.toDisplayString()) },
                    onClick = {
                        onActionSelected(action)
                        isExpanded = false
                    }
                )
            }
        }
    }
}

private fun SoundTriggerAction.toDisplayString(): String = when (this) {
    SoundTriggerAction.NONE -> "None"
    SoundTriggerAction.START_RESUME -> "Start / Resume"
    SoundTriggerAction.PAUSE -> "Pause"
    SoundTriggerAction.LAP -> "Lap"
    SoundTriggerAction.NEXT_STEP -> "Next step"
    SoundTriggerAction.TOGGLE_PLAY_PAUSE -> "Toggle Play/Pause"
    SoundTriggerAction.PARSE -> "Parse"
}

@Composable
private fun SoundTestSection(vm: SettingsViewModel, onSoundwave: () -> Unit, onDiagnostics: () -> Unit) {
    val detectorState by vm.detectorState.collectAsState()
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(false) }
    var isDetecting by remember { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> permissionGranted = isGranted }

    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Test Sound Detection",
                style = MaterialTheme.typography.titleMedium
            )
            
            Text(
                text = "Test if your device can detect taps (double/triple)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!permissionGranted) {
                Button(
                    onClick = {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Microphone Permission")
                }
            } else {
                if (!isDetecting) {
                    Button(
                        onClick = {
                            val started = vm.startDetection()
                            if (!started) {
                                Toast.makeText(
                                    context,
                                    "Failed to start sound detection",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                isDetecting = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Test")
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Listening... try double taps and triple taps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        // Detection and Continuous lights
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val detectionColor = if (detectorState.isLit) Color.Red else MaterialTheme.colorScheme.surfaceVariant
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(detectionColor, shape = CircleShape)
                                )
                                Text("Detection", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val intensity = (detectorState.currentRms * 20f).coerceIn(0f, 1f)
                                val continuousColor = Color(
                                    red = intensity,
                                    green = 1f - intensity * 0.5f,
                                    blue = 0f
                                )
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(continuousColor, shape = CircleShape)
                                )
                                Text("Listening", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Detections: ${detectorState.count}")
                                Text("Strength: ${"%.3f".format(detectorState.lastStrength)}")
                                Text("Peak: ${"%.3f".format(detectorState.lastPeak)}")
                                Text("Current: ${"%.3f".format(detectorState.currentRms)}")
                            }
                        }
                        
                        // Show RMS values
                        Text(
                            text = "Noise floor: %.3f".format(detectorState.noiseFloor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        // Button to view soundwave
                        Button(
                            onClick = onSoundwave,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Soundwave Visualization")
                        }
                        
                        // Button to open diagnostic screen
                        Button(
                            onClick = onDiagnostics,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sound Diagnostics")
                        }
                    }
                }
            }
        }
    }
}
