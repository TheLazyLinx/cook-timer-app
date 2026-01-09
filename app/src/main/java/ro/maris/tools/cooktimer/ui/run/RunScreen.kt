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
fun RunScreen(vm: RunViewModel) {
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
        } else {
            val label = when (state.phase) {
                Phase.READY -> "Ready"
                Phase.PAUSED -> "Paused"
                Phase.COMPLETED -> "Completed"
                Phase.RUNNING -> "Running"
            }
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }

        Divider()

        Text(
            text = "Next: ${next?.title ?: "—"}",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::back) { Text("Back") }
            OutlinedButton(onClick = vm::next) { Text("Next") }
        }

        when (state.phase) {
            Phase.READY -> {
                val isManual = step?.startMode == StartMode.MANUAL || step?.durationSec == null
                val action = if (isManual && state.isStopwatchRunning) vm::next else vm::start
                val label = if (isManual && state.isStopwatchRunning) "Next" else "Start"
                Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            }
            Phase.RUNNING -> Button(onClick = vm::pause, modifier = Modifier.fillMaxWidth()) {
                Text("Pause")
            }
            Phase.PAUSED -> Button(onClick = vm::resume, modifier = Modifier.fillMaxWidth()) {
                Text("Resume")
            }
            Phase.COMPLETED -> Button(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

private fun formatTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
