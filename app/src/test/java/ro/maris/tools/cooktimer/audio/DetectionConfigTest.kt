package ro.maris.tools.cooktimer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionConfigTest {

    private val config = DetectionConfig()

    // ── framesForMs / durationForFrames round-trip ──

    @Test
    fun `framesForMs returns 1 for zero`() {
        assertEquals(1, config.framesForMs(0))
    }

    @Test
    fun `framesForMs rounds up partial hops`() {
        // hopMs = 5; 7ms -> ceil(7/5) = 2
        assertEquals(2, config.framesForMs(7))
    }

    @Test
    fun `framesForMs exact multiple`() {
        assertEquals(10, config.framesForMs(50))
    }

    @Test
    fun `durationForFrames inverse of framesForMs for exact multiples`() {
        val ms = 100
        val frames = config.framesForMs(ms)
        assertEquals(ms, config.durationForFrames(frames))
    }

    @Test
    fun `durationForFrames for zero frames`() {
        assertEquals(0, config.durationForFrames(0))
    }

    // ── Derived sample counts ──

    @Test
    fun `windowSamples matches sampleRate and fftWindowMs`() {
        assertEquals(config.sampleRate * config.fftWindowMs / 1000, config.windowSamples)
    }

    @Test
    fun `hopSamples matches sampleRate and hopMs`() {
        assertEquals(config.sampleRate * config.hopMs / 1000, config.hopSamples)
    }

    // ── Threshold invariants ──

    @Test
    fun `quietModeMinRms is less than baseMinRms`() {
        assertTrue(config.quietModeMinRms < config.baseMinRms)
    }

    @Test
    fun `followUp confidence threshold is less than firstTap threshold`() {
        assertTrue(config.followUpTapConfidenceThreshold < config.firstTapConfidenceThreshold)
    }

    // ── Profiles ──

    @Test
    fun `profiles list is not empty`() {
        assertTrue(config.profiles.isNotEmpty())
    }

    @Test
    fun `all profiles have valid duration ranges`() {
        for (p in config.profiles) {
            assertTrue("${p.name}: minDur > maxDur", p.minDurMs <= p.maxDurMs)
            assertTrue("${p.name}: minDur < 0", p.minDurMs >= 0)
        }
    }

    @Test
    fun `all profiles have valid zcr ranges`() {
        for (p in config.profiles) {
            assertTrue("${p.name}: minZcr > maxZcr", p.minZcr <= p.maxZcr)
        }
    }

    @Test
    fun `all profiles have valid centroid ranges`() {
        for (p in config.profiles) {
            assertTrue("${p.name}: minCentroid > maxCentroid", p.minCentroid <= p.maxCentroid)
        }
    }

    @Test
    fun `all profiles have valid tilt ranges`() {
        for (p in config.profiles) {
            assertTrue("${p.name}: minTilt > maxTilt", p.minTilt <= p.maxTilt)
        }
    }

    @Test
    fun `profile names are unique`() {
        val names = config.profiles.map { it.name }
        assertEquals(names.distinct().size, names.size)
    }

    // ── Custom config overrides ──

    @Test
    fun `custom sampleRate propagates to windowSamples`() {
        val custom = DetectionConfig(sampleRate = 8000)
        assertEquals(8000 * 10 / 1000, custom.windowSamples)
    }

    @Test
    fun `custom hopMs propagates to framesForMs`() {
        val custom = DetectionConfig(hopMs = 10)
        assertEquals(5, custom.framesForMs(50))
    }
}
