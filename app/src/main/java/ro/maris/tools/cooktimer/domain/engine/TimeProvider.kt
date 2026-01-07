package ro.maris.tools.cooktimer.domain.engine

fun interface TimeProvider {
    fun nowEpochSec(): Long
}
