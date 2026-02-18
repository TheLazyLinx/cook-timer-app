package ro.maris.tools.cooktimer.ui.sound

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent

class SoundTapGestureRecognizerTest {

    private fun event(
        ts: Long,
        centroid: Float,
        tilt: Float,
        flatness: Float,
        zcr: Float = 0.35f,
        durationMs: Int = 70
    ): SoundDetectionEvent {
        return SoundDetectionEvent(
            timestampMs = ts,
            durationMs = durationMs,
            strength = 0.15f,
            peak = 0.30f,
            type = "Snap (Mid)",
            centroid = centroid,
            tilt = tilt,
            zcr = zcr,
            flatness = flatness,
            confidence = 0.9f
        )
    }

    @Test
    fun `consistent_pair_resolves_as_double`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 60L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 100L, centroid = 3200f, tilt = 0.10f, flatness = 0.42f))
        recognizer.onTap(event(ts = 130L, centroid = 3350f, tilt = 0.20f, flatness = 0.46f))

        delay(70L)

        assertEquals(1, results.size)
        assertEquals(2, results[0].tapCount)
    }

    @Test
    fun `mismatched_pair_resolves_as_two_singles`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 60L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 200L, centroid = 3200f, tilt = 0.10f, flatness = 0.40f))
        recognizer.onTap(event(ts = 230L, centroid = 5200f, tilt = 3.20f, flatness = 0.10f))

        delay(20L)

        assertEquals(2, results.size)
        assertTrue(results.all { it.tapCount == 1 })
        assertTrue(results.all { it.unpairedReason?.contains("Acoustic mismatch") == true })
    }

    @Test
    fun `third mismatched tap_resolves_double_then_single`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 60L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 300L, centroid = 3300f, tilt = 0.15f, flatness = 0.43f))
        recognizer.onTap(event(ts = 330L, centroid = 3420f, tilt = 0.25f, flatness = 0.45f))
        recognizer.onTap(event(ts = 360L, centroid = 5200f, tilt = 2.50f, flatness = 0.10f))

        delay(80L)

        assertEquals(2, results.size)
        assertEquals(2, results[0].tapCount)
        assertEquals(1, results[1].tapCount)
    }

    @Test
    fun `third shorter but acoustically similar_resolves_as_triple`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 80L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 1000L, centroid = 3550f, tilt = -0.10f, flatness = 0.66f, zcr = 0.39f, durationMs = 110))
        recognizer.onTap(event(ts = 1035L, centroid = 3490f, tilt = -0.16f, flatness = 0.68f, zcr = 0.37f, durationMs = 105))
        recognizer.onTap(event(ts = 1070L, centroid = 3525f, tilt = -0.12f, flatness = 0.64f, zcr = 0.38f, durationMs = 50))

        delay(20L)

        assertEquals(1, results.size)
        assertEquals(3, results[0].tapCount)
    }

    @Test
    fun `resonant_pair_resolves_as_two_singles`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 60L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 400L, centroid = 3360f, tilt = -0.30f, flatness = 0.33f, durationMs = 130))
        recognizer.onTap(event(ts = 435L, centroid = 3440f, tilt = -0.25f, flatness = 0.31f, durationMs = 125))

        delay(20L)

        assertEquals(2, results.size)
        assertTrue(results.all { it.tapCount == 1 })
        assertTrue(results.all { it.unpairedReason?.contains("Resonant impact-like sequence") == true })
    }

    @Test
    fun `mixed_duration resonant_triple_resolves_as_singles`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 80L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 500L, centroid = 3480f, tilt = 0.04f, flatness = 0.25f, durationMs = 145))
        recognizer.onTap(event(ts = 535L, centroid = 3645f, tilt = 0.14f, flatness = 0.42f, durationMs = 80))
        recognizer.onTap(event(ts = 570L, centroid = 3650f, tilt = 0.11f, flatness = 0.36f, durationMs = 125))

        delay(20L)

        assertEquals(3, results.size)
        assertTrue(results.all { it.tapCount == 1 })
        assertTrue(results.all { it.unpairedReason?.contains("Resonant impact-like sequence") == true })
    }

    @Test
    fun `long bright low_flat pair_resolves_as_singles`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 80L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 700L, centroid = 4044f, tilt = 0.28f, flatness = 0.31f, durationMs = 160))
        recognizer.onTap(event(ts = 735L, centroid = 4608f, tilt = 0.49f, flatness = 0.44f, durationMs = 120))

        delay(20L)

        assertEquals(2, results.size)
        assertTrue(results.all { it.tapCount == 1 })
        assertTrue(results.all { it.unpairedReason?.contains("Resonant impact-like sequence") == true })
    }

    @Test
    fun `resonant plus crisp companion pair_resolves_as_singles`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 80L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 800L, centroid = 4449f, tilt = 0.34f, flatness = 0.551f, durationMs = 65))
        recognizer.onTap(event(ts = 835L, centroid = 3222f, tilt = -0.24f, flatness = 0.458f, durationMs = 85))

        delay(20L)

        assertEquals(2, results.size)
        assertTrue(results.all { it.tapCount == 1 })
        assertTrue(results.all { it.unpairedReason?.contains("Resonant impact-like sequence") == true })
    }

    @Test
    fun `high ringing pair_resolves_as_singles`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 80L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 900L, centroid = 4907f, tilt = 0.45f, flatness = 0.503f, zcr = 0.56f, durationMs = 65))
        recognizer.onTap(event(ts = 935L, centroid = 4838f, tilt = 0.41f, flatness = 0.592f, zcr = 0.54f, durationMs = 75))

        delay(20L)

        assertEquals(2, results.size)
        assertTrue(results.all { it.tapCount == 1 })
        assertTrue(results.all { it.unpairedReason?.contains("Resonant impact-like sequence") == true })
    }

    @Test
    fun `periodic cadence stream suppresses first tap and yields singles`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 50L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 1000L, centroid = 3520f, tilt = 0.08f, flatness = 0.50f, zcr = 0.42f, durationMs = 90))
        delay(70L)
        recognizer.onTap(event(ts = 1240L, centroid = 3505f, tilt = 0.12f, flatness = 0.49f, zcr = 0.41f, durationMs = 88))
        delay(70L)
        recognizer.onTap(event(ts = 1480L, centroid = 3530f, tilt = 0.10f, flatness = 0.51f, zcr = 0.43f, durationMs = 92))
        delay(70L)
        recognizer.onTap(event(ts = 1720L, centroid = 3518f, tilt = 0.09f, flatness = 0.50f, zcr = 0.42f, durationMs = 90))
        delay(70L)

        assertEquals(4, results.size)
        assertTrue(results.all { it.tapCount == 1 })
        assertTrue(results.last().unpairedReason?.contains("Periodic cadence-like sequence") == true)
    }

    @Test
    fun `periodic suppression does not drop in progress group`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 1200L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 180L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 2000L, centroid = 3560f, tilt = 0.10f, flatness = 0.50f, zcr = 0.42f, durationMs = 90))
        recognizer.onTap(event(ts = 2240L, centroid = 3550f, tilt = 0.11f, flatness = 0.51f, zcr = 0.41f, durationMs = 92))
        recognizer.onTap(event(ts = 2480L, centroid = 3540f, tilt = 0.09f, flatness = 0.49f, zcr = 0.43f, durationMs = 150))
        recognizer.onTap(event(ts = 2720L, centroid = 3530f, tilt = 0.10f, flatness = 0.50f, zcr = 0.42f, durationMs = 148))
        delay(220L)

        assertTrue(results.none { it.unpairedReason?.contains("Periodic cadence-like sequence") == true })
    }

    @Test
    fun `genuine triple does not trigger cadence suppression`() = runBlocking {
        val results = mutableListOf<SoundTapGroupingResult>()
        val recognizer = SoundTapGestureRecognizer(
            scope = this,
            windowMs = 80L,
            minInterTapGapMs = 5L,
            tripleGraceMs = 40L
        ) { result ->
            results += result
        }

        recognizer.onTap(event(ts = 3000L, centroid = 3600f, tilt = 0.10f, flatness = 0.64f, zcr = 0.42f, durationMs = 70))
        recognizer.onTap(event(ts = 3030L, centroid = 3640f, tilt = 0.15f, flatness = 0.66f, zcr = 0.41f, durationMs = 72))
        recognizer.onTap(event(ts = 3065L, centroid = 3625f, tilt = 0.12f, flatness = 0.63f, zcr = 0.40f, durationMs = 68))
        delay(20L)

        assertEquals(1, results.size)
        assertEquals(3, results[0].tapCount)
        assertTrue(results.none { it.unpairedReason?.contains("Periodic cadence-like sequence") == true })
    }
}
