package ro.maris.tools.cooktimer.domain.engine

sealed class TimerAction {
    data class Tick(val nowEpochSec: Long) : TimerAction()

    data class LoadPreset(val presetId: String) : TimerAction() // optional, placeholder

    data class StartPressed(val nowEpochSec: Long) : TimerAction()
    data class PausePressed(val nowEpochSec: Long) : TimerAction()
    data class ResumePressed(val nowEpochSec: Long) : TimerAction()
    object NextPressed : TimerAction()
    object BackPressed : TimerAction()
    data class ResetPressed(val nowEpochSec: Long) : TimerAction()
}
