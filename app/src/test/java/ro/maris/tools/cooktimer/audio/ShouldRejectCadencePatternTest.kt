package ro.maris.tools.cooktimer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldRejectCadencePatternTest {

    @Test
    fun `not suppressed never rejects`() {
        assertFalse(shouldRejectCadencePattern(
            isSuppressed = false, isGestureFamily = true, firstTapLike = true,
            sessionSimilarity = null, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = false
        ))
    }

    @Test
    fun `non-gesture family never rejects`() {
        assertFalse(shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = false, firstTapLike = true,
            sessionSimilarity = null, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = false
        ))
    }

    @Test
    fun `not first tap like never rejects`() {
        assertFalse(shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = true, firstTapLike = false,
            sessionSimilarity = null, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = false
        ))
    }

    @Test
    fun `null session similarity treated as zero`() {
        assertTrue(shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = true, firstTapLike = true,
            sessionSimilarity = null, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = false
        ))
    }

    @Test
    fun `session similarity exactly at threshold bypasses`() {
        assertFalse(shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = true, firstTapLike = true,
            sessionSimilarity = 0.78f, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = false
        ))
    }

    @Test
    fun `session similarity just below threshold does not bypass`() {
        assertTrue(shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = true, firstTapLike = true,
            sessionSimilarity = 0.779f, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = false
        ))
    }

    @Test
    fun `accepted signature bypass alone prevents rejection`() {
        assertFalse(shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = true, firstTapLike = true,
            sessionSimilarity = 0.10f, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = true
        ))
    }

    @Test
    fun `both bypasses together prevent rejection`() {
        assertFalse(shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = true, firstTapLike = true,
            sessionSimilarity = 0.90f, sessionBypassThreshold = 0.78f, acceptedSignatureBypass = true
        ))
    }
}
