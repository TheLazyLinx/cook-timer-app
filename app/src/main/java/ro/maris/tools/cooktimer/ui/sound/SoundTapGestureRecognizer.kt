package ro.maris.tools.cooktimer.ui.sound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SoundTapGroupingResult(
    val groupId: Long,
    val tapCount: Int,
    val tapTimestamps: List<Long>,
    val unpairedReason: String? = null
)

class SoundTapGestureRecognizer(
    private val scope: CoroutineScope,
    private val windowMs: Long = 1200L,
    private val minInterTapGapMs: Long = 90L,
    private val tripleGraceMs: Long = 800L,
    private val onResolved: (SoundTapGroupingResult) -> Unit
) {
    private val tapTimestamps = ArrayDeque<Long>()
    private var resolveJob: Job? = null
    private var lastSeenTimestampMs: Long = Long.MIN_VALUE
    private var lastAcceptedTapTimestampMs: Long = Long.MIN_VALUE
    private var nextGroupId = 1L

    private fun consumeGroup(tapCount: Int, unpairedReason: String?) {
        val timestamps = tapTimestamps.toList()
        tapTimestamps.clear()
        val groupId = nextGroupId++
        onResolved(
            SoundTapGroupingResult(
                groupId = groupId,
                tapCount = tapCount,
                tapTimestamps = timestamps,
                unpairedReason = unpairedReason
            )
        )
    }

    fun onTap(timestampMs: Long) {
        if (timestampMs <= lastSeenTimestampMs) return
        lastSeenTimestampMs = timestampMs

        if (lastAcceptedTapTimestampMs != Long.MIN_VALUE &&
            timestampMs - lastAcceptedTapTimestampMs < minInterTapGapMs
        ) {
            val groupId = nextGroupId++
            onResolved(
                SoundTapGroupingResult(
                    groupId = groupId,
                    tapCount = 1,
                    tapTimestamps = listOf(timestampMs),
                    unpairedReason = "Too close to prior tap (<${minInterTapGapMs}ms), treated as duplicate"
                )
            )
            return
        }
        lastAcceptedTapTimestampMs = timestampMs

        tapTimestamps.addLast(timestampMs)
        while (tapTimestamps.isNotEmpty() && timestampMs - tapTimestamps.first() > windowMs) {
            tapTimestamps.removeFirst()
        }

        // Triple tap should win immediately to avoid being swallowed by extra delays.
        if (tapTimestamps.size >= 3) {
            resolveJob?.cancel()
            consumeGroup(
                tapCount = 3,
                unpairedReason = null
            )
            return
        }

        resolveJob?.cancel()
        resolveJob = scope.launch {
            // If we have 2 taps, wait only a short grace period for a possible 3rd tap.
            val delayMs = if (tapTimestamps.size >= 2) tripleGraceMs else windowMs
            delay(delayMs)
            val tapCount = tapTimestamps.size
            val reason = when (tapCount) {
                1 -> "No 2nd tap within ${windowMs}ms window"
                2 -> "No 3rd tap within ${tripleGraceMs}ms after double"
                else -> null
            }
            consumeGroup(
                tapCount = tapCount,
                unpairedReason = reason
            )
        }
    }

    fun clear() {
        resolveJob?.cancel()
        resolveJob = null
        tapTimestamps.clear()
        lastAcceptedTapTimestampMs = Long.MIN_VALUE
    }
}
