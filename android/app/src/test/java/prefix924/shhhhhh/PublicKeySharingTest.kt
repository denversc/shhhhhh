package prefix924.shhhhhh

import android.content.ClipData
import android.content.ClipboardManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import prefix924.shhhhhh.crypto.CryptoEngine
import prefix924.shhhhhh.crypto.CryptoKeyPair

class PublicKeySharingTest :
  FunSpec({
    beforeSpec { mockkStatic(ClipData::class) }

    afterSpec { unmockkStatic(ClipData::class) }

    test(
      "publicKeyBase64Url produces exactly 43 unpadded Base64URL characters without padding or whitespace"
    ) {
      val keyPair = CryptoEngine.generateKeyPair()
      val pubKey = keyPair.publicKeyBase64Url

      pubKey shouldHaveLength 43
      pubKey shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
      pubKey shouldNotContain "="
      pubKey shouldNotContain " "
      pubKey shouldNotContain "\n"
      pubKey shouldNotContain "\r"
    }

    test("publicKeyBase64Url decodes back to original 32-byte public key") {
      val keyPair = CryptoEngine.generateKeyPair()
      val pubKey = keyPair.publicKeyBase64Url

      val decoded = CryptoEngine.decodePublicKeyBase64Url(pubKey)
      decoded shouldBe keyPair.publicKey
      decoded.size shouldBe 32
    }

    test("publicKeyBase64Url formats zero and 0xFF byte arrays correctly") {
      val zeros = ByteArray(32) { 0 }
      val zeroPair = CryptoKeyPair(privateKey = zeros, publicKey = zeros)
      zeroPair.publicKeyBase64Url shouldBe "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      zeroPair.publicKeyBase64Url shouldHaveLength 43

      val allOnes = ByteArray(32) { 0xFF.toByte() }
      val onesPair = CryptoKeyPair(privateKey = allOnes, publicKey = allOnes)
      onesPair.publicKeyBase64Url shouldBe "__________________________________________8"
      onesPair.publicKeyBase64Url shouldHaveLength 43
    }

    test(
      "copyPublicKeyToClipboard copies ONLY the raw 43-character Base64URL string without extra text or template wrappers"
    ) {
      val keyPair = CryptoEngine.generateKeyPair()
      val clipboardManager = mockk<ClipboardManager>(relaxed = true)

      val labelSlot = slot<CharSequence>()
      val textSlot = slot<CharSequence>()
      val mockClip = mockk<ClipData>()

      every { ClipData.newPlainText(capture(labelSlot), capture(textSlot)) } returns mockClip

      var feedbackMessage: String? = null
      val returnedKey =
        copyPublicKeyToClipboard(
          clipboardManager = clipboardManager,
          keyPair = keyPair,
          onFeedback = { feedbackMessage = it },
        )

      // Verifies return value
      returnedKey shouldBe keyPair.publicKeyBase64Url
      returnedKey shouldHaveLength 43

      // Verifies label and exact clipboard content
      labelSlot.captured.toString() shouldBe "shhhhhh_public_key"
      val copiedText = textSlot.captured.toString()
      copiedText shouldBe keyPair.publicKeyBase64Url
      copiedText shouldHaveLength 43

      // Verifies no template wrappers or extra text were added
      copiedText shouldNotContain "Key: "
      copiedText shouldNotContain "Public Key: "
      copiedText shouldNotContain "My Public Key: "
      copiedText shouldNotContain "\""
      copiedText shouldNotContain "'"

      // Verifies clipboard interaction
      verify(exactly = 1) { clipboardManager.setPrimaryClip(mockClip) }

      // Verifies feedback callback was invoked
      feedbackMessage shouldBe "Copied public key"
    }

    test(
      "copyPublicKeyToClipboard overloaded with raw string copies verbatim without modification"
    ) {
      val rawKey = "3kL9_xK89mZ2Q1pA0BvL81kLz0P82kL9xK89mZ2Qabc"
      rawKey shouldHaveLength 43

      val clipboardManager = mockk<ClipboardManager>(relaxed = true)
      val labelSlot = slot<CharSequence>()
      val textSlot = slot<CharSequence>()
      val mockClip = mockk<ClipData>()

      every { ClipData.newPlainText(capture(labelSlot), capture(textSlot)) } returns mockClip

      var feedbackMessage: String? = null
      val result =
        copyPublicKeyToClipboard(
          clipboardManager = clipboardManager,
          publicKeyBase64Url = rawKey,
          onFeedback = { feedbackMessage = it },
        )

      result shouldBe rawKey
      labelSlot.captured.toString() shouldBe "shhhhhh_public_key"
      textSlot.captured.toString() shouldBe rawKey
      feedbackMessage shouldBe "Copied public key"
      verify(exactly = 1) { clipboardManager.setPrimaryClip(mockClip) }
    }

    test("copyPublicKeyToClipboard works with default feedback parameter") {
      val keyPair = CryptoEngine.generateKeyPair()
      val clipboardManager = mockk<ClipboardManager>(relaxed = true)
      val mockClip = mockk<ClipData>()

      every { ClipData.newPlainText(any(), any()) } returns mockClip

      val result = copyPublicKeyToClipboard(clipboardManager = clipboardManager, keyPair = keyPair)

      result shouldBe keyPair.publicKeyBase64Url
      verify(exactly = 1) { clipboardManager.setPrimaryClip(mockClip) }
    }

    test(
      "copied public key can be received by another party to encrypt a message that owner decrypts"
    ) {
      val ownerKeyPair = CryptoEngine.generateKeyPair()
      val clipboardManager = mockk<ClipboardManager>(relaxed = true)
      val textSlot = slot<CharSequence>()
      val mockClip = mockk<ClipData>()

      every { ClipData.newPlainText(any(), capture(textSlot)) } returns mockClip

      copyPublicKeyToClipboard(clipboardManager, ownerKeyPair)

      // Friend receives the copied raw key string over text messenger
      val friendReceivedKeyString = textSlot.captured.toString()
      val friendDecodedPublicKey = CryptoEngine.decodePublicKeyBase64Url(friendReceivedKeyString)
      friendDecodedPublicKey shouldBe ownerKeyPair.publicKey

      // Friend encrypts a message for the owner
      val secretMessage = "Meet me at the rendezvous point at 9:00 PM!"
      val ciphertext =
        CryptoEngine.encrypt(plaintext = secretMessage, recipientPublicKey = friendDecodedPublicKey)

      // Owner receives ciphertext and decrypts with their private key
      val decryptedText =
        CryptoEngine.decryptToString(
          sealedBoxBase64 = ciphertext,
          recipientPrivateKey = ownerKeyPair.privateKey,
        )
      decryptedText shouldBe secretMessage
    }

    test("multiple generated key pairs all produce distinct 43-character public keys") {
      val keys = (1..50).map { CryptoEngine.generateKeyPair().publicKeyBase64Url }
      keys.toSet().size shouldBe 50
      keys.forEach { key ->
        key shouldHaveLength 43
        key shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
      }
    }
  })
