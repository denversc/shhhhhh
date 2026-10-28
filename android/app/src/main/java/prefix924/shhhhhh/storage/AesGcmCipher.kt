package prefix924.shhhhhh.storage

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Clean AES-GCM cipher utility for encrypting sensitive cryptographic key scalars at rest.
 * Uses standard JCE AES/GCM/NoPadding with a fresh 12-byte random IV per encryption.
 */
object AesGcmCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8

    /**
     * Encrypts plaintext bytes using the provided [SecretKey].
     *
     * @return `IV (12 bytes) + Ciphertext + Poly/GCM Auth Tag (16 bytes)`
     */
    fun encrypt(
        plaintext: ByteArray,
        secretKey: SecretKey,
        secureRandom: SecureRandom = SecureRandom()
    ): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertextWithTag = cipher.doFinal(plaintext)
        return iv + ciphertextWithTag
    }

    /**
     * Decrypts combined `IV + Ciphertext + Tag` bytes using the provided [SecretKey].
     *
     * @throws javax.crypto.AEADBadTagException if the ciphertext or tag was tampered with.
     * @throws IllegalArgumentException if the combined byte array is smaller than the minimum overhead.
     */
    fun decrypt(
        combined: ByteArray,
        secretKey: SecretKey
    ): ByteArray {
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
