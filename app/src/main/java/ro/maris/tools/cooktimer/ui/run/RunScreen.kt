package ro.maris.tools.cooktimer.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.StartMode

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

    val step = state.activeStep
    val next = state.nextStep

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) {
            Text(text = "Presets")
        }
        Text(text = "Preset: (running)", style = MaterialTheme.typography.titleMedium)
        Text(text = step?.title ?: "—", style = MaterialTheme.typography.headlineMedium)

        if (!step?.notes.isNullOrBlank()) {
            Text(text = step!!.notes!!, style = MaterialTheme.typography.bodyMedium)
        }

        Text(text = "Phase: ${state.phase}", style = MaterialTheme.typography.bodyMedium)

        if (state.phase == Phase.RUNNING) {
            Text(
                text = "Remaining: ${formatTime(requireNotNull(state.remainingSec))}",
                style = MaterialTheme.typography.headlineLarge
            )
        } else if (state.isStopwatchRunning) {
            Text(
                text = "Elapsed: ${formatTime(requireNotNull(state.stopwatchElapsedSec))}",
                style = MaterialTheme.typography.headlineLarge
            )
        } else if (state.phase == Phase.PAUSED) {
            val label = if (step?.durationSec != null) {
                "Remaining"
            } else {
                "Elapsed"
            }
            val timeValue = state.pausedRemainingSec ?: state.stopwatchElapsedSec ?: 0
            Text(
                text = "$label: ${formatTime(timeValue)}",
                style = MaterialTheme.typography.headlineLarge
            )
        } else if (state.isReadyToAdvance && state.stopwatchElapsedSec != null) {
            Text(
                text = "Elapsed: ${formatTime(requireNotNull(state.stopwatchElapsedSec))}",
                style = MaterialTheme.typography.headlineLarge
            )
        } else {
            val label = when (state.phase) {
                Phase.READY -> "Ready"
                Phase.PAUSED -> "Paused"
                Phase.COMPLETED -> "Completed"
                Phase.RUNNING -> "Running"
            }
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }

        HorizontalDivider()

        Text(
            text = "Next: ${next?.title ?: "—"}",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::back) { Text("Back") }
            val isLastStep = next == null
            val showRestart = isLastStep && (state.isReadyToAdvance || state.phase == Phase.COMPLETED)
            val nextLabel = if (showRestart) "Restart" else if (isLastStep) "Stop" else "Next"
            val nextAction = if (showRestart) vm::reset else vm::next
            OutlinedButton(onClick = nextAction) { Text(nextLabel) }
        }

        when (state.phase) {
            Phase.READY -> {
                val isLastStep = next == null
                val action = when {
                    state.isReadyToAdvance -> if (isLastStep) vm::reset else vm::next
                    state.isStopwatchRunning -> vm::next
                    step?.startMode == StartMode.MANUAL || step?.durationSec == null -> vm::start
                    else -> vm::start
                }
                val label = when {
                    state.isReadyToAdvance -> if (isLastStep) "Restart" else "Next"
                    state.isStopwatchRunning -> if (isLastStep) "Stop" else "Next"
                    step?.startMode == StartMode.MANUAL || step?.durationSec == null -> "Start"
                    else -> "Start"
                }
                Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
                if (state.isStopwatchRunning) {
                    OutlinedButton(onClick = vm::pause, modifier = Modifier.fillMaxWidth()) {
                        Text("Pause")
                    }
                }
            }
            Phase.RUNNING -> {
                val isLastStep = next == null
                val label = if (isLastStep) "Stop" else "Next"
                Button(onClick = vm::next, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
                OutlinedButton(onClick = vm::pause, modifier = Modifier.fillMaxWidth()) {
                    Text("Pause")
                }
            }
            Phase.PAUSED -> {
                val isLastStep = next == null
                val label = if (isLastStep) "Stop" else "Next"
                Button(onClick = vm::next, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
                OutlinedButton(onClick = vm::resume, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume")
                }
            }
            Phase.COMPLETED -> Button(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) {
                Text("Restart")
            }
        }
    }
}

private fun formatTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
