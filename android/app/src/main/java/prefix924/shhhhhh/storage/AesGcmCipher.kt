package prefix924.shhhhhh.storage

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Clean AES-GCM cipher utility for encrypting sensitive cryptographic key scalars at rest. Uses
 * standard JCE AES/GCM/NoPadding with a fresh 12-byte random IV per encryption.
 */
object AesGcmCipher {
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  const val GCM_IV_LENGTH_BYTES = 12
  const val GCM_TAG_LENGTH_BITS = 128
  const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8

  /**
   * Encrypts plaintext bytes using the provided [SecretKey].
   *
   * Note: AndroidKeyStore keys require randomized encryption by default and reject caller-provided
   * IVs during encryption. The cipher is therefore initialized without caller-supplied parameters
   * so that the underlying security provider (or secure hardware / TEE) generates a fresh random
   * IV.
   *
   * @return `IV (12 bytes) + Ciphertext + Poly/GCM Auth Tag (16 bytes)`
   */
  fun encrypt(
    plaintext: ByteArray,
    secretKey: SecretKey,
    secureRandom: SecureRandom? = null,
  ): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    if (secureRandom != null) {
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, secureRandom)
    } else {
      cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    }
    val iv = cipher.iv ?: cipher.parameters?.getParameterSpec(GCMParameterSpec::class.java)?.iv
    val ciphertextWithTag = cipher.doFinal(plaintext)
    val finalIv = iv ?: cipher.iv ?: error("Cipher IV must not be null after encryption")
    require(finalIv.size == GCM_IV_LENGTH_BYTES) {
      "Expected IV length $GCM_IV_LENGTH_BYTES, but got ${finalIv.size}"
    }
    return finalIv + ciphertextWithTag
  }

  /**
   * Decrypts combined `IV + Ciphertext + Tag` bytes using the provided [SecretKey].
   *
   * @throws javax.crypto.AEADBadTagException if the ciphertext or tag was tampered with.
   * @throws IllegalArgumentException if the combined byte array is smaller than the minimum
   *   overhead.
   */
  fun decrypt(combined: ByteArray, secretKey: SecretKey): ByteArray {
    val minLength = GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES
    require(combined.size >= minLength) {
      "Ciphertext buffer size (${combined.size}) is smaller than minimum overhead ($minLength bytes)"
    }
    val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
    val ciphertextWithTag = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
    return cipher.doFinal(ciphertextWithTag)
  }
}
