package ro.maris.tools.cooktimer.domain.model

data class RunState(
    val presetId: String,
    val steps: List<Step>,
    val activeStepIndex: Int,
    val phase: Phase,
    val remainingSec: Int,
    val startedAtEpochSec: Long?,   // when current timed step started/resumed
) {
    val activeStep: Step? get() = steps.getOrNull(activeStepIndex)
    val nextStep: Step? get() = steps.getOrNull(activeStepIndex + 1)

    companion object {
        fun initial(preset: Preset, nowEpochSec: Long): RunState {
            require(preset.steps.isNotEmpty()) { "Preset must have at least one step" }
            val step0 = preset.steps[0]
            val entry = entryFor(step0, nowEpochSec)
            return RunState(
                presetId = preset.id,
                steps = preset.steps,
                activeStepIndex = 0,
                phase = entry.phase,
                remainingSec = entry.remainingSec,
                startedAtEpochSec = entry.startedAtEpochSec
            )
        }

        fun entryFor(step: Step, nowEpochSec: Long): Entry {
            return if (step.durationSec == null || step.startMode == StartMode.MANUAL) {
                Entry(Phase.READY, remainingSec = step.durationSec ?: 0, startedAtEpochSec = null)
            } else {
                Entry(Phase.RUNNING, remainingSec = step.durationSec, startedAtEpochSec = nowEpochSec)
            }
        }
    }

    data class Entry(
        val phase: Phase,
        val remainingSec: Int,
        val startedAtEpochSec: Long?
    )
}
