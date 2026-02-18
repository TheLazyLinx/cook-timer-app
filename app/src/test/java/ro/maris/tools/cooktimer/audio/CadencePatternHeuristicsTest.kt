package ro.maris.tools.cooktimer.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadencePatternHeuristicsTest {

    private fun probe(
        ts: Long,
        durationMs: Int = 90,
        centroid: Float = 3600f,
        tilt: Float = 0.10f,
        flatness: Float = 0.52f,
        zcr: Float = 0.42f
    ): CadenceProbe {
        return CadenceProbe(
            timestampMs = ts,
            durationMs = durationMs,
            centroid = centroid,
            tilt = tilt,
            flatness = flatness,
            zcr = zcr
        )
    }

    @Test
    fun `regular similar cadence triggers pattern`() {
        val history = listOf(
            probe(1000L),
            probe(1260L),
            probe(1520L),
            probe(1780L)
        )

        val stats = CadencePatternHeuristics.detectPattern(history, nowMs = 1780L)

        assertNotNull(stats)
        assertTrue((stats?.eventCount ?: 0) >= 4)
    }

    @Test
    fun `irregular intervals do not trigger pattern`() {
        val history = listOf(
            probe(1000L),
            probe(1180L),
            probe(1620L),
            probe(2000L)
        )

        val stats = CadencePatternHeuristics.detectPattern(history, nowMs = 2000L)

        assertNull(stats)
    }

    @Test
    fun `large spectral spread does not trigger pattern`() {
        val history = listOf(
            probe(1000L, centroid = 3200f, tilt = 0.00f, flatness = 0.42f, zcr = 0.35f),
            probe(1260L, centroid = 4700f, tilt = 0.55f, flatness = 0.72f, zcr = 0.65f),
            probe(1520L, centroid = 3300f, tilt = -0.18f, flatness = 0.40f, zcr = 0.28f),
            probe(1780L, centroid = 4550f, tilt = 0.48f, flatness = 0.68f, zcr = 0.60f)
        )

        val stats = CadencePatternHeuristics.detectPattern(history, nowMs = 1780L)

        assertNull(stats)
    }

    @Test
    fun `history pruning expires old cadence evidence`() {
        val history = ArrayDeque<CadenceProbe>()
        history.addLast(probe(1000L))
        history.addLast(probe(1260L))
        history.addLast(probe(1520L))
        history.addLast(probe(1780L))

        CadencePatternHeuristics.pruneHistory(
            history = history,
            nowMs = 3300L,
            windowMs = CadencePatternHeuristics.DEFAULT_WINDOW_MS
        )

        assertTrue(history.isEmpty())
    }

    @Test
    fun `first tap like classification follows tight followup window`() {
        assertTrue(
            CadencePatternHeuristics.isFirstTapLike(
                candidateAgeMs = 900L,
                acceptedAgeMs = 700L,
                followupWindowMs = 420L
            )
        )

        assertFalse(
            CadencePatternHeuristics.isFirstTapLike(
                candidateAgeMs = 260L,
                acceptedAgeMs = 900L,
                followupWindowMs = 420L
            )
        )
    }

    @Test
    fun `suppression active until deadline then expires`() {
        assertTrue(CadencePatternHeuristics.isSuppressed(nowMs = 5000L, suppressUntilMs = 6200L))
        assertFalse(CadencePatternHeuristics.isSuppressed(nowMs = 6200L, suppressUntilMs = 6200L))
    }

    @Test
    fun `session similarity bypass prevents cadence reject`() {
        val rejectedWithoutBypass = shouldRejectCadencePattern(
            isSuppressed = true,
            isGestureFamily = true,
            firstTapLike = true,
            sessionSimilarity = 0.62f,
            sessionBypassThreshold = 0.78f,
            acceptedSignatureBypass = false
        )
        val bypassedBySession = shouldRejectCadencePattern(
            isSuppressed = true,
            isGestureFamily = true,
            firstTapLike = true,
            sessionSimilarity = 0.82f,
            sessionBypassThreshold = 0.78f,
            acceptedSignatureBypass = false
        )
        val bypassedByAcceptedSignature = shouldRejectCadencePattern(
            isSuppressed = true,
            isGestureFamily = true,
            firstTapLike = true,
            sessionSimilarity = 0.40f,
            sessionBypassThreshold = 0.78f,
            acceptedSignatureBypass = true
        )

        assertTrue(rejectedWithoutBypass)
        assertFalse(bypassedBySession)
        assertFalse(bypassedByAcceptedSignature)
    }
}
