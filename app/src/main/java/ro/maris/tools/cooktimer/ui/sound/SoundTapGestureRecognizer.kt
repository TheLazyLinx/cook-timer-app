package ro.maris.tools.cooktimer.ui.sound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.CadencePatternHeuristics
import ro.maris.tools.cooktimer.audio.CadenceProbe
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent
import kotlin.math.abs

data class SoundTapGroupingResult(
    val groupId: Long,
    val tapCount: Int,
    val taps: List<SoundDetectionEvent>,
    val unpairedReason: String? = null
) {
    val tapTimestamps: List<Long> get() = taps.map { it.timestampMs }
}

class SoundTapGestureRecognizer(
    private val scope: CoroutineScope,
    private val windowMs: Long = 1200L,
    private val minInterTapGapMs: Long = 90L,
    private val tripleGraceMs: Long = 800L,
    private val onResolved: (SoundTapGroupingResult) -> Unit
) {
    private val resonantSequenceReason = "Resonant impact-like sequence, treated as singles"
    private val periodicSequenceReason = "Periodic cadence-like sequence, treated as singles"
    private val tapEvents = ArrayDeque<SoundDetectionEvent>()
    private val periodicHistory = ArrayDeque<CadenceProbe>()
    private var periodicSuppressUntilMs: Long = 0L
    private var resolveJob: Job? = null
    private var lastSeenTimestampMs: Long = Long.MIN_VALUE
    private var lastAcceptedTapTimestampMs: Long = Long.MIN_VALUE
    private var nextGroupId = 1L

    private fun isGestureTap(event: SoundDetectionEvent): Boolean {
        val family = event.type.substringBefore(" (").trim()
        return family == "Snap" || family == "Clap" || family == "Slap"
    }

    private fun isConsistentPair(a: SoundDetectionEvent, b: SoundDetectionEvent): Boolean {
        val centroidDeltaPct = abs(a.centroid - b.centroid) / ((a.centroid + b.centroid) * 0.5f).coerceAtLeast(500f)
        val tiltDelta = abs(a.tilt - b.tilt)
        val flatnessDelta = abs(a.flatness - b.flatness)
        val durationDeltaPct =
            abs(a.durationMs - b.durationMs).toFloat() / maxOf(a.durationMs, b.durationMs, 1).toFloat()
        return centroidDeltaPct <= 0.40f &&
            tiltDelta <= 1.5f &&
            flatnessDelta <= 0.18f &&
            durationDeltaPct <= 0.45f
    }

    private fun isRelaxedTripleLink(a: SoundDetectionEvent, b: SoundDetectionEvent): Boolean {
        val centroidDeltaPct = abs(a.centroid - b.centroid) / ((a.centroid + b.centroid) * 0.5f).coerceAtLeast(500f)
        val tiltDelta = abs(a.tilt - b.tilt)
        val flatnessDelta = abs(a.flatness - b.flatness)
        val durationDeltaPct =
            abs(a.durationMs - b.durationMs).toFloat() / maxOf(a.durationMs, b.durationMs, 1).toFloat()
        val durationGate = if (isGestureTap(a) && isGestureTap(b)) 0.72f else 0.55f
        return centroidDeltaPct <= 0.50f &&
            tiltDelta <= 1.8f &&
            flatnessDelta <= 0.24f &&
            durationDeltaPct <= durationGate
    }

    private fun isResonantImpactLike(event: SoundDetectionEvent): Boolean {
        val longLowFlatBand =
            event.durationMs in 80..190 &&
                event.centroid in 3000f..4700f &&
                event.zcr in 0.34f..0.56f &&
                event.tilt in -0.60f..0.35f &&
                event.flatness in 0.22f..0.56f
        val brightRingBand =
            event.durationMs in 80..170 &&
                event.centroid in 3900f..4800f &&
                event.zcr in 0.36f..0.62f &&
                event.tilt in 0.18f..0.60f &&
                event.flatness in 0.28f..0.56f
        val longBrightLowFlatBand =
            event.durationMs in 120..190 &&
                event.centroid in 3900f..5000f &&
                event.zcr in 0.40f..0.70f &&
                event.tilt in 0.22f..0.65f &&
                event.flatness in 0.24f..0.50f
        return longLowFlatBand || brightRingBand || longBrightLowFlatBand
    }

    private fun isResonantCompanionLike(event: SoundDetectionEvent): Boolean {
        val broadCompanionBand =
            event.durationMs in 45..105 &&
                event.centroid in 3000f..4700f &&
                event.zcr in 0.34f..0.58f &&
                event.tilt in -0.30f..0.45f &&
                event.flatness in 0.24f..0.62f
        val crispCompanionBand =
            event.durationMs in 45..90 &&
                event.centroid in 4100f..5000f &&
                event.zcr in 0.40f..0.65f &&
                event.tilt in 0.20f..0.60f &&
                event.flatness in 0.36f..0.62f
        return broadCompanionBand || crispCompanionBand
    }

    private fun isHighRingingPairLike(event: SoundDetectionEvent): Boolean {
        return event.durationMs in 50..130 &&
            event.centroid >= 4200f &&
            event.zcr >= 0.42f &&
            event.tilt >= 0.18f &&
            event.flatness in 0.38f..0.70f
    }

    private fun isStrongSnapLike(event: SoundDetectionEvent): Boolean {
        return event.durationMs in 35..95 &&
            event.centroid >= 3600f &&
            event.zcr >= 0.40f &&
            event.tilt >= 0.10f &&
            event.flatness >= 0.60f
    }

    private fun isResonantSequence(events: List<SoundDetectionEvent>): Boolean {
        if (events.size < 2) return false
        val avgDuration = events.map { it.durationMs }.average().toFloat()
        val avgCentroid = events.map { it.centroid }.average().toFloat()
        val avgTilt = events.map { it.tilt }.average().toFloat()
        val avgFlatness = events.map { it.flatness }.average().toFloat()
        val avgZcr = events.map { it.zcr }.average().toFloat()
        val resonantCount = events.count(::isResonantImpactLike)
        val companionCount = events.count(::isResonantCompanionLike)
        val highRingingCount = events.count(::isHighRingingPairLike)
        val highRingingPair = highRingingCount >= 2 && events.count(::isStrongSnapLike) <= 1
        val highPingCluster =
            events.size >= 2 &&
                avgCentroid >= 4450f &&
                avgTilt >= 0.25f &&
                avgZcr >= 0.45f &&
                avgFlatness <= 0.62f
        val hasResonantChain =
            resonantCount >= 2 || (resonantCount >= 1 && companionCount >= 2) || highRingingPair || highPingCluster
        if (!hasResonantChain) return false
        if (events.count(::isStrongSnapLike) >= 2 && resonantCount < 2 && !highPingCluster) return false

        val centroidAvg = events.map { it.centroid }.average().toFloat().coerceAtLeast(600f)
        val centroidSpread =
            (events.maxOf { it.centroid } - events.minOf { it.centroid }) / centroidAvg
        val tiltSpread = events.maxOf { it.tilt } - events.minOf { it.tilt }
        val flatnessSpread = events.maxOf { it.flatness } - events.minOf { it.flatness }
        val chainSpanMs = events.last().timestampMs - events.first().timestampMs
        val strictShapeAligned =
            centroidSpread <= 0.26f &&
                tiltSpread <= 0.55f &&
                flatnessSpread <= 0.22f
        val mixedResonantCompanion =
            resonantCount >= 1 &&
                companionCount >= 2 &&
                centroidSpread <= 0.36f &&
                tiltSpread <= 0.80f &&
                flatnessSpread <= 0.34f &&
                avgFlatness <= 0.56f &&
                avgZcr >= 0.35f &&
                events.maxOf { it.durationMs } >= 80
        val highRingingAligned =
            highRingingPair &&
                centroidSpread <= 0.22f &&
                tiltSpread <= 0.45f &&
                flatnessSpread <= 0.24f
        val highPingAligned =
            highPingCluster &&
                centroidSpread <= 0.34f &&
                tiltSpread <= 0.85f &&
                flatnessSpread <= 0.34f
        val longBrightChain =
            events.size >= 2 &&
                avgDuration >= 105f &&
                avgCentroid >= 3900f &&
                avgTilt >= 0.20f &&
                avgFlatness <= 0.50f &&
                avgZcr >= 0.40f &&
                events.count(::isStrongSnapLike) == 0

        return chainSpanMs <= 750L &&
            (strictShapeAligned || mixedResonantCompanion || highRingingAligned || highPingAligned || longBrightChain)
    }

    private fun emitFrontSingles(count: Int, reason: String) {
        repeat(count.coerceAtLeast(0)) {
            if (tapEvents.isEmpty()) return
            consumeFront(1, 1, reason)
        }
    }

    private fun registerPeriodicProbe(event: SoundDetectionEvent) {
        if (!isGestureTap(event)) return
        if (event.confidence < CadencePatternHeuristics.DEFAULT_PROBE_CONFIDENCE_MIN) return
        if (event.durationMs !in
            CadencePatternHeuristics.DEFAULT_PROBE_DURATION_MIN_MS..CadencePatternHeuristics.DEFAULT_PROBE_DURATION_MAX_MS
        ) return

        CadencePatternHeuristics.pruneHistory(
            history = periodicHistory,
            nowMs = event.timestampMs,
            windowMs = CadencePatternHeuristics.DEFAULT_WINDOW_MS
        )
        periodicHistory.addLast(
            CadenceProbe(
                timestampMs = event.timestampMs,
                durationMs = event.durationMs,
                centroid = event.centroid,
                tilt = event.tilt,
                flatness = event.flatness,
                zcr = event.zcr
            )
        )
        CadencePatternHeuristics.pruneHistory(
            history = periodicHistory,
            nowMs = event.timestampMs,
            windowMs = CadencePatternHeuristics.DEFAULT_WINDOW_MS
        )
        val stats = CadencePatternHeuristics.detectPattern(
            history = periodicHistory.toList(),
            nowMs = event.timestampMs
        )
        if (stats != null) {
            periodicSuppressUntilMs = maxOf(
                periodicSuppressUntilMs,
                event.timestampMs + CadencePatternHeuristics.DEFAULT_SUPPRESSION_MS
            )
        }
    }

    private fun emitGroup(
        taps: List<SoundDetectionEvent>,
        tapCount: Int,
        unpairedReason: String?
    ) {
        if (taps.isEmpty()) return
        val groupId = nextGroupId++
        onResolved(
            SoundTapGroupingResult(
                groupId = groupId,
                tapCount = tapCount,
                taps = taps,
                unpairedReason = unpairedReason
            )
        )
    }

    private fun consumeFront(
        count: Int,
        tapCount: Int,
        reason: String?
    ) {
        if (count <= 0 || tapEvents.isEmpty()) return
        val consumed = mutableListOf<SoundDetectionEvent>()
        repeat(minOf(count, tapEvents.size)) {
            consumed += tapEvents.removeFirst()
        }
        emitGroup(consumed, tapCount, reason)
    }

    private fun resolveImmediateConsistency() {
        val mismatchReason = "Acoustic mismatch between taps, treated as single"
        while (tapEvents.size >= 2) {
            if (tapEvents.size == 2) {
                val first = tapEvents.first()
                val second = tapEvents.elementAt(1)
                if (!isConsistentPair(first, second)) {
                    resolveJob?.cancel()
                    consumeFront(1, 1, mismatchReason)
                    consumeFront(1, 1, mismatchReason)
                } else if (isResonantSequence(listOf(first, second))) {
                    resolveJob?.cancel()
                    emitFrontSingles(2, resonantSequenceReason)
                }
                return
            }

            val first = tapEvents.first()
            val second = tapEvents.elementAt(1)
            val third = tapEvents.elementAt(2)

            val firstSecond = isConsistentPair(first, second)
            val secondThirdRelaxed = isRelaxedTripleLink(second, third)
            val firstThirdRelaxed = isRelaxedTripleLink(first, third)
            val tripleConsistent =
                firstSecond &&
                    secondThirdRelaxed &&
                    firstThirdRelaxed

            when {
                tripleConsistent -> {
                    resolveJob?.cancel()
                    if (isResonantSequence(listOf(first, second, third))) {
                        emitFrontSingles(3, resonantSequenceReason)
                    } else {
                        consumeFront(3, 3, null)
                    }
                }
                firstSecond -> {
                    resolveJob?.cancel()
                    if (isResonantSequence(listOf(first, second))) {
                        emitFrontSingles(2, resonantSequenceReason)
                    } else {
                        consumeFront(2, 2, "3rd tap acoustically inconsistent; resolved as double")
                    }
                    return
                }
                else -> {
                    resolveJob?.cancel()
                    consumeFront(1, 1, mismatchReason)
                }
            }
        }
    }

    private fun scheduleResolve() {
        resolveJob?.cancel()
        if (tapEvents.isEmpty()) return
        resolveJob = scope.launch {
            val delayMs = if (tapEvents.size >= 2) tripleGraceMs else windowMs
            delay(delayMs)
            resolveOnTimeout()
        }
    }

    private fun resolveOnTimeout() {
        val mismatchReason = "Acoustic mismatch between taps, treated as single"
        while (tapEvents.isNotEmpty()) {
            when (tapEvents.size) {
                1 -> {
                    consumeFront(
                        count = 1,
                        tapCount = 1,
                        reason = "No 2nd tap within ${windowMs}ms window"
                    )
                    return
                }
                2 -> {
                    val first = tapEvents.first()
                    val second = tapEvents.elementAt(1)
                    if (!isConsistentPair(first, second)) {
                        consumeFront(1, 1, mismatchReason)
                        consumeFront(1, 1, mismatchReason)
                    } else if (isResonantSequence(listOf(first, second))) {
                        emitFrontSingles(2, resonantSequenceReason)
                    } else {
                        consumeFront(
                            count = 2,
                            tapCount = 2,
                            reason = "No 3rd tap within ${tripleGraceMs}ms after double"
                        )
                    }
                    return
                }
                else -> {
                    val first = tapEvents.first()
                    val second = tapEvents.elementAt(1)
                    val third = tapEvents.elementAt(2)
                    val firstSecond = isConsistentPair(first, second)
                    val secondThirdRelaxed = isRelaxedTripleLink(second, third)
                    val firstThirdRelaxed = isRelaxedTripleLink(first, third)
                    val tripleConsistent =
                        firstSecond &&
                            secondThirdRelaxed &&
                            firstThirdRelaxed
                    when {
                        tripleConsistent -> {
                            if (isResonantSequence(listOf(first, second, third))) {
                                emitFrontSingles(3, resonantSequenceReason)
                            } else {
                                consumeFront(3, 3, null)
                            }
                        }
                        firstSecond -> {
                            if (isResonantSequence(listOf(first, second))) {
                                emitFrontSingles(2, resonantSequenceReason)
                            } else {
                                consumeFront(2, 2, "3rd tap acoustically inconsistent; resolved as double")
                            }
                        }
                        else -> consumeFront(1, 1, mismatchReason)
                    }
                }
            }
        }
    }

    fun onTap(event: SoundDetectionEvent) {
        val timestampMs = event.timestampMs
        if (timestampMs <= lastSeenTimestampMs) return
        lastSeenTimestampMs = timestampMs
        registerPeriodicProbe(event)

        if (lastAcceptedTapTimestampMs != Long.MIN_VALUE &&
            timestampMs - lastAcceptedTapTimestampMs < minInterTapGapMs
        ) {
            val groupId = nextGroupId++
            onResolved(
                SoundTapGroupingResult(
                    groupId = groupId,
                    tapCount = 1,
                    taps = listOf(event),
                    unpairedReason = "Too close to prior tap (<${minInterTapGapMs}ms), treated as duplicate"
                )
            )
            return
        }
        lastAcceptedTapTimestampMs = timestampMs

        val periodicSuppressedFirstTap =
            timestampMs < periodicSuppressUntilMs && tapEvents.isEmpty()
        if (periodicSuppressedFirstTap) {
            val groupId = nextGroupId++
            onResolved(
                SoundTapGroupingResult(
                    groupId = groupId,
                    tapCount = 1,
                    taps = listOf(event),
                    unpairedReason = periodicSequenceReason
                )
            )
            return
        }

        tapEvents.addLast(event)
        while (tapEvents.isNotEmpty() && timestampMs - tapEvents.first().timestampMs > windowMs) {
            tapEvents.removeFirst()
        }

        resolveImmediateConsistency()
        scheduleResolve()
    }

    fun clear() {
        resolveJob?.cancel()
        resolveJob = null
        tapEvents.clear()
        periodicHistory.clear()
        periodicSuppressUntilMs = 0L
        lastAcceptedTapTimestampMs = Long.MIN_VALUE
    }
}
