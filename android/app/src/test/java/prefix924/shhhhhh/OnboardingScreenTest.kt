package prefix924.shhhhhh

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.SecureRandom

class OnboardingScreenTest : FunSpec({

    val fixedZeroRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0)
        }
    }

    test("entropyAccumulator produces 32ByteSeed and depends on user samples") {
        val acc1 = EntropyAccumulator()
        acc1.addSample(x = 12f, y = 34f, dx = 1f, dy = 2f, timestampNanos = 100L)

        val acc2 = EntropyAccumulator()
        acc2.addSample(x = 12f, y = 34f, dx = 1f, dy = 2f, timestampNanos = 101L)

        val seed1 = acc1.generateCombinedSeed(fixedZeroRandom)
        val seed2 = acc2.generateCombinedSeed(fixedZeroRandom)

        seed1.size shouldBe 32
        seed2.size shouldBe 32
        seed1.contentEquals(seed2) shouldBe false
    }

    test("entropyProgressIncrements match tap and scribble targets") {
        // 77-80 discrete taps reach 100%
        (80 * TAP_ENTROPY_INCREMENT >= 1.0f) shouldBe true
        (77 * TAP_ENTROPY_INCREMENT >= 1.0f) shouldBe true
        (76 * TAP_ENTROPY_INCREMENT < 1.0f) shouldBe true

        // ~580 drag samples after initial pointer-down reach 100%
        (TAP_ENTROPY_INCREMENT + 580 * MOVE_ENTROPY_INCREMENT < 1.0f) shouldBe true
        (TAP_ENTROPY_INCREMENT + 581 * MOVE_ENTROPY_INCREMENT >= 1.0f) shouldBe true
    }
})
