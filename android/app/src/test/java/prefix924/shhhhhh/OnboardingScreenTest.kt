package prefix924.shhhhhh

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingScreenTest {

    private class FixedZeroSecureRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0)
        }
    }

    @Test
    fun entropyAccumulator_produces32ByteSeedAndDependsOnUserSamples() {
        val acc1 = EntropyAccumulator()
        acc1.addSample(x = 12f, y = 34f, dx = 1f, dy = 2f, timestampNanos = 100L)

        val acc2 = EntropyAccumulator()
        acc2.addSample(x = 12f, y = 34f, dx = 1f, dy = 2f, timestampNanos = 101L)

        val seed1 = acc1.generateCombinedSeed(FixedZeroSecureRandom())
        val seed2 = acc2.generateCombinedSeed(FixedZeroSecureRandom())

        assertEquals(32, seed1.size)
        assertEquals(32, seed2.size)
        assertFalse(seed1.contentEquals(seed2))
    }

    @Test
    fun entropyProgressIncrements_matchTapAndScribbleTargets() {
        // 77-80 discrete taps reach 100%
        assertTrue(80 * TAP_ENTROPY_INCREMENT >= 1.0f)
        assertTrue(77 * TAP_ENTROPY_INCREMENT >= 1.0f)
        assertTrue(76 * TAP_ENTROPY_INCREMENT < 1.0f)

        // ~580 drag samples after initial pointer-down reach 100%
        assertTrue(TAP_ENTROPY_INCREMENT + 580 * MOVE_ENTROPY_INCREMENT < 1.0f)
        assertTrue(TAP_ENTROPY_INCREMENT + 581 * MOVE_ENTROPY_INCREMENT >= 1.0f)
    }
}
