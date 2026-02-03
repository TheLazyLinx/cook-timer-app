package ro.maris.tools.cooktimer.domain.model

data class RunState(
    val presetId: String,
    val presetName: String,
    val steps: List<Step>,
    val activeStepIndex: Int,
    val phase: Phase,
    val endsAtEpochSec: Long?, // non-null when timing a running/paused step
    val pausedAtEpochSec: Long?, // non-null only while paused
    val stopwatchStartedAtEpochSec: Long?, // non-null when stopwatch running
    val stopwatchAccumulatedSec: Int, // accumulated when stopwatch is paused
    val laps: List<Lap> = emptyList(),
    val stepCompleted: Boolean, // true when a step finished and awaits manual advance
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
    val pausedRemainingSec: Int?
        get() {
            if (phase != Phase.PAUSED) return null
            val endsAt = endsAtEpochSec ?: return null
            val pausedAt = pausedAtEpochSec ?: return null
            val remaining = (endsAt - pausedAt).toInt()
            return remaining.coerceAtLeast(0)
        }
    val isReadyToAdvance: Boolean
        get() = phase == Phase.READY && stepCompleted
    val stopwatchElapsedSec: Int?
        get() {
        val startedAt = stopwatchStartedAtEpochSec
        val runningElapsed = if (startedAt != null) {
            (nowEpochSec - startedAt).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val total = stopwatchAccumulatedSec + runningElapsed
        return if (total == 0 && startedAt == null) null else total
    }

    companion object {
        fun initial(preset: Preset, nowEpochSec: Long): RunState {
            return initialFromSteps(preset.id, preset.name, preset.steps, nowEpochSec)
        }

        fun initialFromSteps(presetId: String, presetName: String, steps: List<Step>, nowEpochSec: Long): RunState {
            require(steps.isNotEmpty()) { "Preset must have at least one step" }
            val entry = Entry(Phase.READY, endsAtEpochSec = null)
            return RunState(
                presetId = presetId,
                presetName = presetName,
                steps = steps,
                activeStepIndex = 0,
                phase = entry.phase,
                endsAtEpochSec = entry.endsAtEpochSec,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                stopwatchAccumulatedSec = 0,
                laps = emptyList(),
                stepCompleted = false,
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
