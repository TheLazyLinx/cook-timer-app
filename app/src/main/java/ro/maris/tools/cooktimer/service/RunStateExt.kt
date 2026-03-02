package ro.maris.tools.cooktimer.service

import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.RunState

/**
 * Returns an [ActiveTimerInfo] if the service should be running for this state,
 * or `null` if the timer is idle / completed and the service should stop.
 */
internal fun RunState.toActiveTimerInfoOrNull(): ActiveTimerInfo? {
    // Service not needed for initial idle or completed states
    if (phase == Phase.COMPLETED) return null

    val step = activeStep ?: return null
    val isStopwatch = step.durationSec == null

    val isActive = phase == Phase.RUNNING || phase == Phase.PAUSED || isStopwatchRunning
    if (!isActive) return null

    return ActiveTimerInfo(
        label = step.title.ifBlank { presetName },
        phase = phase,
        endsAtEpochSec = endsAtEpochSec,
        pausedRemainingSec = pausedRemainingSec,
        isStopwatch = isStopwatch,
        swStartedAtEpochSec = stopwatchStartedAtEpochSec,
        swAccumulatedSec = stopwatchAccumulatedSec,
        stepIndex = activeStepIndex,
        totalSteps = steps.size
    )
}
