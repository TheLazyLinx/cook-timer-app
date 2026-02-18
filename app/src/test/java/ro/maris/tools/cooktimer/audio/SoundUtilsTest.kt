package ro.maris.tools.cooktimer.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SoundUtilsTest {

    @Test
    fun `noise_like_signal_has_higher_flatness_than_tone`() {
        val sampleRate = 16000
        val size = 512

        val tone = FloatArray(size) { i ->
            (sin(2.0 * PI * 1000.0 * i / sampleRate) * 0.7).toFloat()
        }
        val random = Random(1234)
        val noise = FloatArray(size) {
            (random.nextFloat() * 2f - 1f) * 0.7f
        }

        val toneFeatures = SoundUtils.calculateSpectralFeatures(tone, sampleRate)
        val noiseFeatures = SoundUtils.calculateSpectralFeatures(noise, sampleRate)

        assertTrue(
            "Expected noise flatness (${noiseFeatures.flatness}) > tone flatness (${toneFeatures.flatness})",
            noiseFeatures.flatness > toneFeatures.flatness
        )
    }
}
