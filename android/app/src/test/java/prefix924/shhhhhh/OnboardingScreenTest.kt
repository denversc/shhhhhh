package prefix924.shhhhhh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.SecureRandom
import java.util.Base64
import prefix924.shhhhhh.crypto.CryptoEngine

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

    test("generating key pair from accumulated entropy seed produces valid keys") {
        val acc = EntropyAccumulator()
        acc.addSample(x = 45f, y = 90f, dx = 2f, dy = 3f, timestampNanos = 123456789L)
        val seed = acc.generateCombinedSeed()
        seed.size shouldBe 32

        val keyPair = CryptoEngine.generateKeyPair(SecureRandom(seed))
        keyPair.privateKey.size shouldBe 32
        keyPair.publicKey.size shouldBe 32
        keyPair.publicKeyBase64Url.length shouldBe 43

        // Able to encrypt and decrypt
        val ciphertext = CryptoEngine.encrypt("hello entropy", keyPair.publicKey)
        val decrypted = CryptoEngine.decryptToString(ciphertext, keyPair.privateKey)
        decrypted shouldBe "hello entropy"
    }

    test("restoreKeyPairFromBackup reconstructs from 43-character Base64URL string") {
        val originalKeyPair = CryptoEngine.generateKeyPair()
        val privateKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(originalKeyPair.privateKey)
        privateKeyBase64Url.length shouldBe 43

        val restored = restoreKeyPairFromBackup(privateKeyBase64Url)
        restored.privateKey shouldBe originalKeyPair.privateKey
        restored.publicKey shouldBe originalKeyPair.publicKey
        restored shouldBe originalKeyPair
    }

    test("restoreKeyPairFromBackup deterministically generates key pair for arbitrary text") {
        val text1 = "correct horse battery staple"
        val text2 = "shhhhhh paper backup test 2026"

        val kp1a = restoreKeyPairFromBackup(text1)
        val kp1b = restoreKeyPairFromBackup(text1)
        val kp2 = restoreKeyPairFromBackup(text2)

        // Deterministic: same text yields same key pair
        kp1a shouldBe kp1b
        kp1a.privateKey.size shouldBe 32
        kp1a.publicKey.size shouldBe 32

        // Different text yields different key pair
        kp1a shouldNotBe kp2

        // Functioning key pair: can encrypt and decrypt
        val secret = "Secret message for restored key"
        val ciphertext = CryptoEngine.encrypt(secret, kp1a.publicKey)
        val decrypted = CryptoEngine.decryptToString(ciphertext, kp1a.privateKey)
        decrypted shouldBe secret
    }

    test("restoreKeyPairFromBackup throws on blank input") {
        shouldThrow<IllegalArgumentException> {
            restoreKeyPairFromBackup("")
        }
        shouldThrow<IllegalArgumentException> {
            restoreKeyPairFromBackup("   \n\t  ")
        }
    }

    test("CryptoKeyPairSaver saves and restores key pair correctly") {
        val original = CryptoEngine.generateKeyPair()
        val scope = mockkSaverScope()
        @Suppress("UNCHECKED_CAST")
        val saved = with(CryptoKeyPairSaver) { scope.save(original) } as List<String>
        saved.size shouldBe 2

        val restored = CryptoKeyPairSaver.restore(saved)
        restored shouldBe original

        // Null key pair round trip: listSaver returns null when list is empty
        val savedNull = with(CryptoKeyPairSaver) { scope.save(null) }
        savedNull shouldBe null
        val restoredEmpty = CryptoKeyPairSaver.restore(emptyList<Any>())
        restoredEmpty shouldBe null
    }
})

private fun mockkSaverScope() = object : androidx.compose.runtime.saveable.SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}
