package ro.maris.tools.cooktimer.domain.model

data class Lap(
    val index: Int,
    val splitTimeSec: Int,    // Total time elapsed when lap was pressed
    val lapTimeSec: Int       // Duration of just this lap
)
