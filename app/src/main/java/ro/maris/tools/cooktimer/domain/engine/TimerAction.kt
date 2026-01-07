package ro.maris.tools.cooktimer.domain.engine

sealed class TimerAction {
    data class Tick(val nowEpochSec: Long) : TimerAction()

    data class LoadPreset(val presetId: String) : TimerAction() // optional, placeholder

    object StartPressed : TimerAction()
    object PausePressed : TimerAction()
    object ResumePressed : TimerAction()
    object NextPressed : TimerAction()
    object BackPressed : TimerAction()
}
