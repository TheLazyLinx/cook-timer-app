package ro.maris.tools.cooktimer.domain.engine

import ro.maris.tools.cooktimer.domain.model.Lap
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
            TimerAction.LapPressed -> onLap(state)
            is TimerAction.LoadPreset -> state // not used in Phase 1
        }
    }

    private fun onLap(state: RunState): RunState {
        // Lap only valid if stopwatch is running or paused (for recording split)
        // Or if we are in RUNNING state (for preset timer laps - but usually stopwatch)
        // Requirement is "stopwatch should have a laps feature".
        
        // Check if we are in a stopwatch mode
        val isStopwatch = state.activeStep?.durationSec == null
        if (!isStopwatch) return state // Ignore lap for timed steps for now, unless requested.

        // Calculate current total elapsed time
        val currentElapsed = state.stopwatchElapsedSec ?: 0
        if (currentElapsed == 0 && state.laps.isEmpty()) return state // Don't lap at 0
        
        // Calculate lap duration. 
        // Total elapsed - sum of previous laps?
        // Actually, if we store splitTimeSec in Lap, lapTime = splitTime - prevSplitTime.
        val lastSplit = state.laps.lastOrNull()?.splitTimeSec ?: 0
        val lapDuration = currentElapsed - lastSplit

        val newLap = Lap(
            index = state.laps.size + 1,
            splitTimeSec = currentElapsed,
            lapTimeSec = lapDuration
        )
        
        return state.copy(
            laps = state.laps + newLap
        )
    }

    private fun onStart(state: RunState, nowEpochSec: Long): RunState {
        if (state.stepCompleted) {
            return onNext(state)
        }
        val step = state.activeStep ?: return state
        val dur = step.durationSec
        return if (dur == null) {
            if (state.isStopwatchRunning) {
                state.copy(nowEpochSec = nowEpochSec)
            } else {
                state.copy(
                    phase = Phase.READY,
                    endsAtEpochSec = null,
                    pausedAtEpochSec = null,
                    stopwatchStartedAtEpochSec = nowEpochSec,
                    stopwatchAccumulatedSec = 0,
                    stepCompleted = false,
                    nowEpochSec = nowEpochSec
                )
            }
        } else {
            state.copy(
                phase = Phase.RUNNING,
                endsAtEpochSec = nowEpochSec + dur,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                stopwatchAccumulatedSec = 0,
                stepCompleted = false,
                nowEpochSec = nowEpochSec
            )
        }
    }

    private fun onPause(state: RunState, nowEpochSec: Long): RunState {
        return if (state.phase == Phase.RUNNING) {
            val endsAt = checkNotNull(state.endsAtEpochSec) { "endsAtEpochSec required to pause running step" }
            state.copy(
                phase = Phase.PAUSED,
                endsAtEpochSec = endsAt,
                pausedAtEpochSec = nowEpochSec,
                stopwatchStartedAtEpochSec = null,
                stepCompleted = false,
                nowEpochSec = nowEpochSec
            )
        } else if (state.phase == Phase.READY && state.isStopwatchRunning) {
            val startedAt = checkNotNull(state.stopwatchStartedAtEpochSec)
            val elapsed = (nowEpochSec - startedAt).toInt().coerceAtLeast(0)
            state.copy(
                phase = Phase.PAUSED,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                stopwatchAccumulatedSec = state.stopwatchAccumulatedSec + elapsed,
                stepCompleted = false,
                nowEpochSec = nowEpochSec
            )
        } else {
            state
        }
    }

    private fun onResume(state: RunState, nowEpochSec: Long): RunState {
        if (state.phase != Phase.PAUSED) return state
        val step = state.activeStep ?: return state
        val dur = step.durationSec
        return if (dur != null) {
            val endsAt = checkNotNull(state.endsAtEpochSec) { "endsAtEpochSec required to resume paused step" }
            val pausedAt = checkNotNull(state.pausedAtEpochSec) { "pausedAtEpochSec required to resume paused step" }
            val pausedDuration = (nowEpochSec - pausedAt).coerceAtLeast(0)
            state.copy(
                phase = Phase.RUNNING,
                endsAtEpochSec = endsAt + pausedDuration,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                stepCompleted = false,
                nowEpochSec = nowEpochSec
            )
        } else {
            state.copy(
                phase = Phase.READY,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = nowEpochSec,
                stepCompleted = false,
                nowEpochSec = nowEpochSec
            )
        }
    }

    private fun onNext(state: RunState): RunState {
        var current = state
        while (true) {
            val step = current.activeStep ?: return current
            if (step.durationSec == null && current.isStopwatchRunning) {
                val startedAt = current.stopwatchStartedAtEpochSec
                val elapsed = if (startedAt != null) {
                    (current.nowEpochSec - startedAt).toInt().coerceAtLeast(0)
                } else {
                    0
                }
                val accumulated = current.stopwatchAccumulatedSec + elapsed
                if (step.startMode == StartMode.MANUAL) {
                    return current.copy(
                        phase = Phase.READY,
                        endsAtEpochSec = null,
                        pausedAtEpochSec = null,
                        stopwatchStartedAtEpochSec = null,
                        stopwatchAccumulatedSec = accumulated,
                        stepCompleted = true
                    )
                } else {
                    current = current.copy(
                        phase = Phase.READY,
                        endsAtEpochSec = null,
                        pausedAtEpochSec = null,
                        stopwatchStartedAtEpochSec = null,
                        stopwatchAccumulatedSec = accumulated,
                        stepCompleted = false
                    )
                    continue
                }
            }
            val nextIndex = current.activeStepIndex + 1
            if (nextIndex >= current.steps.size) {
                return current.copy(
                    phase = Phase.COMPLETED,
                    endsAtEpochSec = null,
                    pausedAtEpochSec = null,
                    stopwatchStartedAtEpochSec = null,
                    stopwatchAccumulatedSec = 0,
                    stepCompleted = false
                )
            }
            return current.copy(
                activeStepIndex = nextIndex,
                phase = Phase.READY,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                stopwatchAccumulatedSec = 0,
                stepCompleted = false
            )
        }
    }

    private fun onBack(state: RunState): RunState {
        val prevIndex = (state.activeStepIndex - 1).coerceAtLeast(0)
        if (prevIndex == state.activeStepIndex) return state
        return state.copy(
            activeStepIndex = prevIndex,
            phase = Phase.READY,
            endsAtEpochSec = null,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null,
            stopwatchAccumulatedSec = 0,
            stepCompleted = false
        )
    }

    private fun onTick(state: RunState, nowEpochSec: Long): RunState {
        // Optimization: If the timer is not in a state that evolves with time,
        // do not return a new state object. This prevents unnecessary UI recompositions.
        val isActive = state.phase == Phase.RUNNING || state.isStopwatchRunning
        if (!isActive) {
            return state
        }
        
        val step = state.activeStep ?: return state
        val dur = step.durationSec

        // The phase logic below handles transitions, but if we are just ticking time...
        if (state.phase == Phase.READY) {
            // READY but isStopwatchRunning must be true (checked by isActive)
             return state.copy(
                nowEpochSec = nowEpochSec
            )
        }

        if (dur == null) {
            // Manual step running stopwatch
             return state.copy(
                nowEpochSec = nowEpochSec
            )
        }

        val endsAt = checkNotNull(state.endsAtEpochSec) { "endsAtEpochSec required to run a timed step" }
        val remaining = (endsAt - nowEpochSec).toInt()
        val updated = state.copy(nowEpochSec = nowEpochSec)

        if (remaining > 0) return updated

        if (step.startMode == StartMode.MANUAL) {
            return updated.copy(
                phase = Phase.READY,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                stopwatchAccumulatedSec = 0,
                stepCompleted = true
            )
        }

        val nextIndex = state.activeStepIndex + 1
        if (nextIndex >= state.steps.size) {
            return updated.copy(
                phase = Phase.COMPLETED,
                endsAtEpochSec = null,
                pausedAtEpochSec = null,
                stopwatchStartedAtEpochSec = null,
                stopwatchAccumulatedSec = 0,
                stepCompleted = false
            )
        }

        val nextStep = state.steps[nextIndex]
        val entry = RunState.entryFor(nextStep, nowEpochSec)

        return updated.copy(
            activeStepIndex = nextIndex,
            phase = entry.phase,
            endsAtEpochSec = entry.endsAtEpochSec,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null,
            stopwatchAccumulatedSec = 0,
            stepCompleted = false
        )
    }

    private fun onReset(state: RunState, nowEpochSec: Long): RunState {
        return state.copy(
            activeStepIndex = 0,
            phase = Phase.READY,
            endsAtEpochSec = null,
            pausedAtEpochSec = null,
            stopwatchStartedAtEpochSec = null,
            stopwatchAccumulatedSec = 0,
            laps = emptyList(),
            stepCompleted = false,
            nowEpochSec = nowEpochSec
        )
    }
}
