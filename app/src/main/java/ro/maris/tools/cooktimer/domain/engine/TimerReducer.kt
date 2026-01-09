package ro.maris.tools.cooktimer.domain.engine

import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.model.StartMode

object TimerReducer {

    fun reduce(state: RunState, action: TimerAction): RunState {
        return when (action) {
            is TimerAction.Tick -> onTick(state, action.nowEpochSec)
            is TimerAction.StartPressed -> onStart(state, action.nowEpochSec)
            is TimerAction.PausePressed -> onPause(state, action.nowEpochSec)
            is TimerAction.ResumePressed -> onResume(state, action.nowEpochSec)
            TimerAction.NextPressed -> onNext(state)
            TimerAction.BackPressed -> onBack(state)
            is TimerAction.ResetPressed -> onReset(state, action.nowEpochSec)
            is TimerAction.LoadPreset -> state // not used in Phase 1
        }
    }

    private fun onStart(state: RunState, nowEpochSec: Long): RunState {
        val step = state.activeStep ?: return state
        val dur = step.durationSec
        return if (dur == null || step.startMode == StartMode.MANUAL) {
            if (state.isStopwatchRunning) {
                state.copy(nowEpochSec = nowEpochSec)
            } else {
                state.copy(
                    phase = Phase.READY,
                    endsAtEpochSec = null,
                    pausedAtEpochSec = null,
                    stopwatchStartedAtEpochSec = nowEpochSec,
                    nowEpochSec = nowEpochSec
                )
            }
        } else {
            state.copy(
                phase = Phase.RUNNING,
                endsAtEpochSec = nowEpochSec + dur,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                nowEpochSec = nowEpochSec
            )
        }
    }

    private fun onPause(state: RunState, nowEpochSec: Long): RunState {
        if (state.phase != Phase.RUNNING) return state
        val endsAt = checkNotNull(state.endsAtEpochSec) { "endsAtEpochSec required to pause running step" }
        return state.copy(
            phase = Phase.PAUSED,
            endsAtEpochSec = endsAt,
            pausedAtEpochSec = nowEpochSec,
            stopwatchStartedAtEpochSec = null,
            nowEpochSec = nowEpochSec
        )
    }

    private fun onResume(state: RunState, nowEpochSec: Long): RunState {
        if (state.phase != Phase.PAUSED) return state
        val endsAt = checkNotNull(state.endsAtEpochSec) { "endsAtEpochSec required to resume paused step" }
        val pausedAt = checkNotNull(state.pausedAtEpochSec) { "pausedAtEpochSec required to resume paused step" }
        val pausedDuration = (nowEpochSec - pausedAt).coerceAtLeast(0)
        return state.copy(
            phase = Phase.RUNNING,
            endsAtEpochSec = endsAt + pausedDuration,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null,
            nowEpochSec = nowEpochSec
        )
    }

    private fun onNext(state: RunState): RunState {
        val nextIndex = state.activeStepIndex + 1
        if (nextIndex >= state.steps.size) {
            return state.copy(
                phase = Phase.COMPLETED,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null
            )
        }
        return state.copy(
            activeStepIndex = nextIndex,
            phase = Phase.READY,
            endsAtEpochSec = null,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null
        )
    }

    private fun onBack(state: RunState): RunState {
        val prevIndex = (state.activeStepIndex - 1).coerceAtLeast(0)
        if (prevIndex == state.activeStepIndex) return state
        return state.copy(
            activeStepIndex = prevIndex,
            phase = Phase.READY,
            endsAtEpochSec = null,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null
        )
    }

    private fun onTick(state: RunState, nowEpochSec: Long): RunState {
        val step = state.activeStep ?: return state
        val dur = step.durationSec
        if (state.phase == Phase.COMPLETED) {
            return state.copy(nowEpochSec = nowEpochSec)
        }

        if (state.phase == Phase.READY) {
            return state.copy(
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = state.stopwatchStartedAtEpochSec,
                nowEpochSec = nowEpochSec
            )
        }

        if (dur == null) {
            return state.copy(
                phase = Phase.READY,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                nowEpochSec = nowEpochSec
            )
        }

        if (state.phase == Phase.PAUSED) {
            return state.copy(nowEpochSec = nowEpochSec)
        }

        val endsAt = checkNotNull(state.endsAtEpochSec) { "endsAtEpochSec required to run a timed step" }
        val remaining = (endsAt - nowEpochSec).toInt()
        val updated = state.copy(nowEpochSec = nowEpochSec)

        if (remaining > 0) return updated

        val nextIndex = state.activeStepIndex + 1
        if (nextIndex >= state.steps.size) {
            return updated.copy(
                phase = Phase.COMPLETED,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null
            )
        }

        val nextStep = state.steps[nextIndex]
        val entry = RunState.entryFor(nextStep, nowEpochSec)

        return updated.copy(
            activeStepIndex = nextIndex,
            phase = entry.phase,
            endsAtEpochSec = entry.endsAtEpochSec,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null
        )
    }

    private fun onReset(state: RunState, nowEpochSec: Long): RunState {
        return state.copy(
            activeStepIndex = 0,
            phase = Phase.READY,
            endsAtEpochSec = null,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null,
            nowEpochSec = nowEpochSec
        )
    }
}
