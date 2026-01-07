package ro.maris.tools.cooktimer.domain.model

data class Step(
    val id: String,
    val title: String,
    val durationSec: Int?,          // null = gate step (no timer)
    val startMode: StartMode,
    val notes: String? = null
)
