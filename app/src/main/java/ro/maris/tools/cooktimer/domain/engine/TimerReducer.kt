package ro.maris.tools.cooktimer.domain.engine

import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.RunState

object TimerReducer {

    fun reduce(state: RunState, action: TimerAction): RunState {
        return when (action) {
            is TimerAction.Tick -> onTick(state, action.nowEpochSec)
            TimerAction.StartPressed -> onStart(state)
            TimerAction.PausePressed -> onPause(state)
            TimerAction.ResumePressed -> onResume(state)
            TimerAction.NextPressed -> onNext(state)
            TimerAction.BackPressed -> onBack(state)
            is TimerAction.LoadPreset -> state // not used in Phase 1
        }
    }

    private fun onStart(state: RunState): RunState {
        val step = state.activeStep ?: return state
        val dur = step.durationSec
        return if (dur == null) {
            // Gate step: Start doesn't do much; user can Next when ready
            state.copy(phase = Phase.READY, remainingSec = 0, startedAtEpochSec = null)
        } else {
            state.copy(phase = Phase.RUNNING, remainingSec = dur, startedAtEpochSec = null /* set by engine with Tick */)
        }
    }

    private fun onPause(state: RunState): RunState {
        return if (state.phase == Phase.RUNNING) state.copy(phase = Phase.PAUSED) else state
    }

    private fun onResume(state: RunState): RunState {
        return if (state.phase == Phase.PAUSED) state.copy(phase = Phase.RUNNING, startedAtEpochSec = null) else state
    }

    private fun onNext(state: RunState): RunState {
        val nextIndex = state.activeStepIndex + 1
        if (nextIndex >= state.steps.size) {
            return state.copy(phase = Phase.COMPLETED, remainingSec = 0, startedAtEpochSec = null)
        }
        // Engine will compute correct entry using "enter step" helper with a Tick carrying time.
        return state.copy(
            activeStepIndex = nextIndex,
            phase = Phase.READY,
            remainingSec = state.steps[nextIndex].durationSec ?: 0,
            startedAtEpochSec = null
        )
    }

    private fun onBack(state: RunState): RunState {
        val prevIndex = (state.activeStepIndex - 1).coerceAtLeast(0)
        if (prevIndex == state.activeStepIndex) return state
        return state.copy(
            activeStepIndex = prevIndex,
            phase = Phase.READY,
            remainingSec = state.steps[prevIndex].durationSec ?: 0,
            startedAtEpochSec = null
        )
    }

    private fun onTick(state: RunState, nowEpochSec: Long): RunState {
        val step = state.activeStep ?: return state
        val dur = step.durationSec

        // If we're in READY, ensure remaining matches configured duration for display.
        if (state.phase == Phase.READY) {
            val remaining = dur ?: 0
            return state.copy(remainingSec = remaining, startedAtEpochSec = null)
        }

        // For RUNNING/PAUSED: if no duration, treat as READY.
        if (dur == null) {
            return state.copy(phase = Phase.READY, remainingSec = 0, startedAtEpochSec = null)
        }

        // PAUSED: keep remaining static (do not change).
        if (state.phase == Phase.PAUSED) {
            return state
        }

        // RUNNING:
        val startedAt = state.startedAtEpochSec ?: nowEpochSec
        val elapsed = (nowEpochSec - startedAt).toInt().coerceAtLeast(0)
        val remaining = (state.remainingSec - elapsed).coerceAtLeast(0)

        // Update startedAt to now, and keep remaining as "remaining at now"
        val updated = state.copy(remainingSec = remaining, startedAtEpochSec = nowEpochSec)

        if (remaining > 0) return updated

        // Step complete → advance
        val nextIndex = state.activeStepIndex + 1
        if (nextIndex >= state.steps.size) {
            return updated.copy(phase = Phase.COMPLETED, startedAtEpochSec = null)
        }

        // Enter next step: auto-run or ready depending on step properties
        val nextStep = state.steps[nextIndex]
        val entry = RunState.entryFor(nextStep, nowEpochSec)

        return updated.copy(
            activeStepIndex = nextIndex,
            phase = entry.phase,
            remainingSec = entry.remainingSec,
            startedAtEpochSec = entry.startedAtEpochSec
        )
    }
}
