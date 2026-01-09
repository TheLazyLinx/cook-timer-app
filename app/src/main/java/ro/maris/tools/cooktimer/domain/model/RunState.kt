package ro.maris.tools.cooktimer.domain.model

data class RunState(
    val presetId: String,
    val steps: List<Step>,
    val activeStepIndex: Int,
    val phase: Phase,
    val endsAtEpochSec: Long?, // non-null when timing a running/paused step
    val pausedAtEpochSec: Long?, // non-null only while paused
    val stopwatchStartedAtEpochSec: Long?, // non-null when stopwatch running
    val nowEpochSec: Long
) {
    val activeStep: Step? get() = steps.getOrNull(activeStepIndex)
    val nextStep: Step? get() = steps.getOrNull(activeStepIndex + 1)
    val isStopwatchRunning: Boolean get() = stopwatchStartedAtEpochSec != null
    val remainingSec: Int?
        get() {
            if (phase != Phase.RUNNING) return null
            val endsAt = endsAtEpochSec ?: return null
            val remaining = (endsAt - nowEpochSec).toInt()
            return remaining.coerceAtLeast(0)
        }
    val stopwatchElapsedSec: Int?
        get() {
            val startedAt = stopwatchStartedAtEpochSec ?: return null
            val elapsed = (nowEpochSec - startedAt).toInt()
            return elapsed.coerceAtLeast(0)
        }

    companion object {
        fun initial(preset: Preset, nowEpochSec: Long): RunState {
            return initialFromSteps(preset.id, preset.steps, nowEpochSec)
        }

        fun initialFromSteps(presetId: String, steps: List<Step>, nowEpochSec: Long): RunState {
            require(steps.isNotEmpty()) { "Preset must have at least one step" }
            val step0 = steps[0]
            val entry = entryFor(step0, nowEpochSec)
            return RunState(
                presetId = presetId,
                steps = steps,
                activeStepIndex = 0,
                phase = entry.phase,
                endsAtEpochSec = entry.endsAtEpochSec,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                nowEpochSec = nowEpochSec
            )
        }

        fun entryFor(step: Step, nowEpochSec: Long): Entry {
            return if (step.durationSec == null || step.startMode == StartMode.MANUAL) {
                Entry(Phase.READY, endsAtEpochSec = null)
            } else {
                Entry(Phase.RUNNING, endsAtEpochSec = nowEpochSec + step.durationSec)
            }
        }
    }

    data class Entry(
        val phase: Phase,
        val endsAtEpochSec: Long?
    )
}
