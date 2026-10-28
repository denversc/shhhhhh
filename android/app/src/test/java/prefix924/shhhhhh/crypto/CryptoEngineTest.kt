package prefix924.shhhhhh.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException

class CryptoEngineTest : FunSpec({

    test("key pair generation produces valid 32-byte keys and 43-character public key") {
        val keyPair = CryptoEngine.generateKeyPair()

        keyPair.privateKey.size shouldBe 32
        keyPair.publicKey.size shouldBe 32

        val pubBase64 = keyPair.publicKeyBase64Url
        pubBase64.length shouldBe 43
        pubBase64.matches(Regex("[A-Za-z0-9_-]{43}")) shouldBe true

        // Base64URL round trip
        val decodedPub = CryptoEngine.decodePublicKeyBase64Url(pubBase64)
        decodedPub shouldBe keyPair.publicKey

        val encodedPub = CryptoEngine.encodePublicKeyBase64Url(decodedPub)
        encodedPub shouldBe pubBase64
    }

    test("derivePublicKey matches generated public key from private key scalar") {
        val keyPair = CryptoEngine.generateKeyPair()
        val derivedPub = CryptoEngine.derivePublicKey(keyPair.privateKey)

        derivedPub shouldBe keyPair.publicKey

        val recreatedPair = CryptoEngine.createKeyPairFromPrivateKey(keyPair.privateKey)
        recreatedPair.publicKey shouldBe keyPair.publicKey
        recreatedPair.privateKey shouldBe keyPair.privateKey
        recreatedPair shouldBe keyPair
    }

    test("subsequent key generation invocations produce distinct keys") {
        val kp1 = CryptoEngine.generateKeyPair()
        val kp2 = CryptoEngine.generateKeyPair()

        kp1.privateKey shouldNotBe kp2.privateKey
        kp1.publicKey shouldNotBe kp2.publicKey
        kp1 shouldNotBe kp2
    }

    test("HKDF-SHA256 matches RFC 5869 Test Case 1") {
        // Hex helper
        fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.replace(" ", "")
            val bytes = ByteArray(cleanHex.length / 2)
            for (i in bytes.indices) {
                val index = i * 2
                bytes[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
            }
            return bytes
        }

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val expectedPrk = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
        val expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

        val prk = CryptoEngine.hkdfExtract(salt, ikm)
        bytesToHex(prk) shouldBe expectedPrk

        val okm = CryptoEngine.hkdfExpand(prk, info, 42)
        bytesToHex(okm) shouldBe expectedOkm
    }

    test("encryption and decryption round-trip with normal strings") {
        val alice = CryptoEngine.generateKeyPair()
        val bob = CryptoEngine.generateKeyPair()

        val messages = listOf(
            "BankPassword123!",
            "Hello, world! 🔐 ChaCha20-Poly1305 + X25519",
            "Multi-line\nsecret\r\nmessage with tabs\tand symbols: !@#$%^&*()_+~`",
            "A".repeat(10_000)
        )

        for (message in messages) {
            // Alice encrypts for Bob
            val ciphertext = CryptoEngine.encrypt(message, bob.publicKey)
            (ciphertext.length >= 80) shouldBe true
            ciphertext.matches(CryptoEngine.CANDIDATE_REGEX) shouldBe true

            // Bob decrypts with Bob's private key
            val decrypted = CryptoEngine.decryptToString(ciphertext, bob.privateKey)
            decrypted shouldBe message
        }
    }

    test("encryption and decryption of empty string produces exactly 80 chars Base64URL") {
        val alice = CryptoEngine.generateKeyPair()

        val emptyCiphertext = CryptoEngine.encrypt("", alice.publicKey)
        emptyCiphertext.length shouldBe 80
        emptyCiphertext.matches(CryptoEngine.CANDIDATE_REGEX) shouldBe true

        val decrypted = CryptoEngine.decryptToString(emptyCiphertext, alice.privateKey)
        decrypted shouldBe ""

        val decryptedBytes = CryptoEngine.decrypt(emptyCiphertext, alice.privateKey)
        decryptedBytes.size shouldBe 0
    }

    test("MockK: SecureRandom collaborator is invoked for nonce and key generation") {
        val mockRandom = mockk<SecureRandom>(relaxed = true)
        val alice = CryptoEngine.generateKeyPair(secureRandom = mockRandom)

        // Verify SecureRandom was accessed during key generation
        verify(atLeast = 1) { mockRandom.nextBytes(any()) }

        // Test encryption with mockRandom
        val encryptRandom = mockk<SecureRandom>(relaxed = true)
        every { encryptRandom.nextBytes(any()) } answers {
            val buf = firstArg<ByteArray>()
            buf.fill(0x3A)
        }

        val ciphertext = CryptoEngine.encrypt("Secret", alice.publicKey, secureRandom = encryptRandom)
        (ciphertext.length >= 80) shouldBe true
        verify(atLeast = 1) { encryptRandom.nextBytes(any()) }
    }

    test("authentication failure: decrypting with different private key throws AEADBadTagException") {
        val alice = CryptoEngine.generateKeyPair()
        val eve = CryptoEngine.generateKeyPair()

        val ciphertext = CryptoEngine.encrypt("Confidential Payload", alice.publicKey)

        shouldThrow<AEADBadTagException> {
            CryptoEngine.decrypt(ciphertext, eve.privateKey)
        }
    }

    test("tampered ciphertext (flipped bit) fails authentication") {
        val alice = CryptoEngine.generateKeyPair()
        val ciphertext = CryptoEngine.encrypt("Sensitive Document", alice.publicKey)
        val rawPayload = Base64.getUrlDecoder().decode(ciphertext)

        // Test tampering at various positions: ephemeral key, nonce, ciphertext body, MAC tag
        val testIndices = listOf(0, 15, 31, 32, 40, 43, 44, rawPayload.size / 2, rawPayload.size - 1)

        for (idx in testIndices) {
            val tampered = rawPayload.clone()
            tampered[idx] = (tampered[idx].toInt() xor 0x01).toByte()
            val tamperedBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered)

            shouldThrow<Exception> {
                CryptoEngine.decrypt(tamperedBase64, alice.privateKey)
            }
        }
    }

    test("truncated or invalid sealed box payload throws IllegalArgumentException") {
        val alice = CryptoEngine.generateKeyPair()

        // Less than 60 bytes
        val shortBytes = ByteArray(59)
        val shortBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(shortBytes)

        shouldThrow<IllegalArgumentException> {
            CryptoEngine.decrypt(shortBase64, alice.privateKey)
        }

        // Invalid base64
        shouldThrow<IllegalArgumentException> {
            CryptoEngine.decrypt("invalid-base64-symbols-!#%*&^~@", alice.privateKey)
        }
    }

    test("candidate scanning: returns NoCandidates when input has no candidate strings") {
        val alice = CryptoEngine.generateKeyPair()

        CryptoEngine.decryptTextWithCandidates("", alice.privateKey) shouldBe DecryptResult.NoCandidates
        CryptoEngine.decryptTextWithCandidates("Hello, this is just normal text.", alice.privateKey) shouldBe DecryptResult.NoCandidates

        // String with 79 characters (one less than threshold)
        val shortToken = "A".repeat(79)
        CryptoEngine.decryptTextWithCandidates("Token: $shortToken", alice.privateKey) shouldBe DecryptResult.NoCandidates
    }

    test("candidate scanning: returns AuthFailed when candidates exist but fail decryption") {
        val alice = CryptoEngine.generateKeyPair()
        val bob = CryptoEngine.generateKeyPair()

        // Encrypted for Bob, but decrypted with Alice's key
        val bobCiphertext = CryptoEngine.encrypt("Bob's secret", bob.publicKey)
        val inputText = "Hey Alice, here is Bob's message: $bobCiphertext please read it."

        val result = CryptoEngine.decryptTextWithCandidates(inputText, alice.privateKey)
        result shouldBe DecryptResult.AuthFailed
    }

    test("candidate scanning: successfully decrypts single candidate in-place") {
        val alice = CryptoEngine.generateKeyPair()
        val secret = "BankPassword123!"
        val ciphertext = CryptoEngine.encrypt(secret, alice.publicKey)

        val inputText = "Here is the bank password: $ciphertext let me know when you are done!"
        val result = CryptoEngine.decryptTextWithCandidates(inputText, alice.privateKey)

        result shouldBe DecryptResult.Success(
            text = "Here is the bank password: $secret let me know when you are done!",
            secrets = listOf(secret)
        )
    }

    test("candidate scanning: successfully decrypts multiple candidates in-place in conversational text") {
        val alice = CryptoEngine.generateKeyPair()
        val secret1 = "WifiPassword456"
        val secret2 = "VaultPIN789"

        val ct1 = CryptoEngine.encrypt(secret1, alice.publicKey)
        val ct2 = CryptoEngine.encrypt(secret2, alice.publicKey)

        val inputText = "Hello! Wifi is $ct1 and vault PIN is $ct2. Enjoy!"
        val result = CryptoEngine.decryptTextWithCandidates(inputText, alice.privateKey)

        result shouldBe DecryptResult.Success(
            text = "Hello! Wifi is $secret1 and vault PIN is $secret2. Enjoy!",
            secrets = listOf(secret1, secret2)
        )
    }

    test("candidate scanning: mixed valid and invalid candidates leaves failed candidates intact") {
        val alice = CryptoEngine.generateKeyPair()
        val bob = CryptoEngine.generateKeyPair()

        val aliceSecret = "SecretForAlice"
        val bobSecret = "SecretForBob"

        val aliceCt = CryptoEngine.encrypt(aliceSecret, alice.publicKey)
        val bobCt = CryptoEngine.encrypt(bobSecret, bob.publicKey)

        val inputText = "Part 1: $aliceCt and Part 2: $bobCt"
        val result = CryptoEngine.decryptTextWithCandidates(inputText, alice.privateKey)

        result shouldBe DecryptResult.Success(
            text = "Part 1: $aliceSecret and Part 2: $bobCt",
            secrets = listOf(aliceSecret)
        )
    }
})
