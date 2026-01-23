package ro.maris.tools.cooktimer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

data class SpectralFeatures(
    val centroid: Float,
    val tilt: Float // Slope of the log-power spectrum
)

object SoundUtils {
    
    fun calculateSpectralFeatures(buffer: FloatArray, sampleRate: Int): SpectralFeatures {
        val n = nextPowerOf2(buffer.size)
        val real = FloatArray(n)
        val imag = FloatArray(n)

        // Copy and zero-pad
        for (i in buffer.indices) {
            // Apply Hanning window
            val multiplier = 0.5 * (1 - cos(2 * PI * i / (buffer.size - 1)))
            real[i] = (buffer[i] * multiplier).toFloat()
        }

        fft(real, imag)

        var sumMag = 0.0
        var sumWeightedMag = 0.0
        val binSize = sampleRate.toDouble() / n
        
        // Variables for Tilt (Linear Regression variables)
        // We track log(f) and log(magnitude)
        // Slope = (N * sum(xy) - sum(x)*sum(y)) / (N * sum(x^2) - sum(x)^2)
        var sumX = 0.0 // sum(logF)
        var sumY = 0.0 // sum(logMag)
        var sumXY = 0.0
        var sumXX = 0.0
        var count = 0

        // Use up to Nyquist frequency
        // Start from bin 1 to avoid log(0) at DC
        for (i in 0 until n / 2) {
            val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
            val freq = i * binSize
            
            // Centroid Accumulation
            sumMag += mag
            sumWeightedMag += freq * mag
            
            // Tilt Accumulation (only distinct frequencies > 0)
            if (i > 0 && mag > 0.000001) {
                val logF = ln(freq)
                val logM = ln(mag.toDouble())
                
                sumX += logF
                sumY += logM
                sumXY += logF * logM
                sumXX += logF * logF
                count++
            }
        }

        val centroid = if (sumMag > 0.001) (sumWeightedMag / sumMag).toFloat() else 0f
        
        var tilt = 0f
        if (count > 1) {
             val denominator = count * sumXX - sumX * sumX
             if (abs(denominator) > 0.000001) {
                 tilt = ((count * sumXY - sumX * sumY) / denominator).toFloat()
             }
        }

        return SpectralFeatures(
            centroid = centroid.coerceIn(0f, sampleRate / 2f),
            tilt = tilt
        )
    }

    /**
     * Calculates the Spectral Centroid (center of mass of the spectrum) in Hz.
     * Legacy wrapper for backward compatibility if needed, but perfer calculateSpectralFeatures
     */
    fun calculateSpectralCentroid(buffer: FloatArray, sampleRate: Int): Float {
        return calculateSpectralFeatures(buffer, sampleRate).centroid
    }

    private fun nextPowerOf2(n: Int): Int {
        var count = 0
        var num = n
        if (n > 0 && (n and (n - 1) == 0)) return n
        while (num != 0) {
            num = num shr 1
            count += 1
        }
        return 1 shl count
    }

    // Iterative Radix-2 FFT
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        val levels = (kotlin.math.log2(n.toFloat())).toInt()

        // Bit reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        // Butterfly operations
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val step = 2 * PI / size
            for (i in 0 until n step size) {
                for (k in 0 until halfSize) {
                    val angle = k * step
                    val cosA = cos(angle).toFloat()
                    val sinA = (-sin(angle)).toFloat() // Negative for forward FFT

                    val tr = cosA * real[i + k + halfSize] - sinA * imag[i + k + halfSize]
                    val ti = sinA * real[i + k + halfSize] + cosA * imag[i + k + halfSize]

                    real[i + k + halfSize] = real[i + k] - tr
                    imag[i + k + halfSize] = imag[i + k] - ti
                    real[i + k] += tr
                    imag[i + k] += ti
                }
            }
            size *= 2
        }
    }
}
