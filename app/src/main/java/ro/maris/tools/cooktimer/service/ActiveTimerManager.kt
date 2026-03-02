package ro.maris.tools.cooktimer.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ro.maris.tools.cooktimer.domain.model.Phase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight holder that ViewModels update whenever their timer state changes.
 * Also responsible for starting / stopping [TimerForegroundService].
 */
@Singleton
class ActiveTimerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow<ActiveTimerInfo?>(null)
    val state: StateFlow<ActiveTimerInfo?> = _state.asStateFlow()

    /** Identifier of the ViewModel that currently "owns" the service. */
    private var activeOwnerId: String? = null

    /**
     * Called by a ViewModel every time its engine state changes.
     * Pass `null` to indicate the timer is no longer active (reset / completed).
     */
    fun update(ownerId: String, info: ActiveTimerInfo?) {
        if (info != null) {
            activeOwnerId = ownerId
            _state.value = info
            sendUpdate(info)
        } else if (activeOwnerId == ownerId) {
            activeOwnerId = null
            _state.value = null
            sendStop()
        }
    }

    /**
     * Called from [ViewModel.onCleared]. Only stops the service if the timer is
     * NOT actively running — a running/paused timer must survive ViewModel death.
     */
    fun unregister(ownerId: String, timerStillActive: Boolean) {
        if (activeOwnerId != ownerId) return
        if (!timerStillActive) {
            activeOwnerId = null
            _state.value = null
            sendStop()
        }
        // If timer is still active the service keeps running independently.
    }

    // --- Intent helpers ---

    private fun sendUpdate(info: ActiveTimerInfo) {
        val intent = Intent(context, TimerForegroundService::class.java).apply {
            action = TimerForegroundService.ACTION_UPDATE
            putExtra(TimerForegroundService.EXTRA_LABEL, info.label)
            putExtra(TimerForegroundService.EXTRA_PHASE, info.phase.name)
            putExtra(TimerForegroundService.EXTRA_ENDS_AT, info.endsAtEpochSec ?: 0L)
            putExtra(TimerForegroundService.EXTRA_PAUSED_REMAINING, info.pausedRemainingSec ?: 0)
            putExtra(TimerForegroundService.EXTRA_IS_STOPWATCH, info.isStopwatch)
            putExtra(TimerForegroundService.EXTRA_SW_STARTED_AT, info.swStartedAtEpochSec ?: 0L)
            putExtra(TimerForegroundService.EXTRA_SW_ACCUMULATED, info.swAccumulatedSec)
            putExtra(TimerForegroundService.EXTRA_STEP_INDEX, info.stepIndex)
            putExtra(TimerForegroundService.EXTRA_TOTAL_STEPS, info.totalSteps)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun sendStop() {
        context.stopService(Intent(context, TimerForegroundService::class.java))
    }
}

/**
 * Snapshot of the current timer state sent to the foreground service.
 * All absolute timestamps use [System.currentTimeMillis] / 1000 (epoch seconds).
 */
data class ActiveTimerInfo(
    val label: String,
    val phase: Phase,
    /** Non-null for a running countdown timer. */
    val endsAtEpochSec: Long?,
    /** Non-null when paused: the remaining seconds frozen at pause time. */
    val pausedRemainingSec: Int?,
    /** True if this is a stopwatch (counts up, no completion). */
    val isStopwatch: Boolean,
    /** Epoch-second when the stopwatch started counting (null when not running). */
    val swStartedAtEpochSec: Long?,
    /** Accumulated seconds from previous stopwatch runs before the latest resume. */
    val swAccumulatedSec: Int,
    val stepIndex: Int,
    val totalSteps: Int
)
