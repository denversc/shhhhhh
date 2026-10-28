package prefix924.shhhhhh.crypto

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Representation of a Curve25519 / X25519 key pair with 32-byte raw keys.
 */
data class CryptoKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    /**
     * Unpadded Base64URL representation of the 32-byte raw public key (43 characters).
     */
    val publicKeyBase64Url: String
        get() = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CryptoKeyPair) return false
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Result of scanning and decrypting text with candidate ciphertext substrings.
 */
sealed interface DecryptResult {
    /**
     * No Base64URL string of 80 or more characters was found in the input text.
     */
    data object NoCandidates : DecryptResult

    /**
     * One or more ciphertext candidate strings were found, but none could be
     * decrypted with the recipient's private key.
     */
    data object AuthFailed : DecryptResult

    /**
     * Decryption succeeded for one or more candidate strings.
     *
     * @param text The input text with valid ciphertexts replaced in-place by their decrypted plaintexts.
     * @param secrets All decrypted secret strings in their order of appearance in the text.
     */
    data class Success(
        val text: String,
        val secrets: List<String>
    ) : DecryptResult

    companion object {
        val NO_CANDIDATES: DecryptResult get() = NoCandidates
        val AUTH_FAILED: DecryptResult get() = AuthFailed
    }
}

/**
 * Native sealed box cryptography engine for shhhhhh.
 *
 * Implements:
 * - X25519 Diffie-Hellman key agreement (RFC 7748)
 * - HKDF-SHA256 key derivation (RFC 5869)
 * - ChaCha20-Poly1305 AEAD authenticated encryption (RFC 8439)
 * - Sealed Box format: ephemeralPublicKey[32] + nonce[12] + ciphertextWithTag[N + 16]
 * - Unpadded Base64URL wire serialization
 *
 * Uses portable ASN.1 PKCS#8 and X.509 encodings for 100% cross-platform compatibility
 * between Android Conscrypt and JVM SunEC providers.
 */
object CryptoEngine {
    const val KEY_SIZE_BYTES = 32
    const val NONCE_SIZE_BYTES = 12
    const val TAG_SIZE_BYTES = 16
    const val OVERHEAD_BYTES = KEY_SIZE_BYTES + NONCE_SIZE_BYTES + TAG_SIZE_BYTES // 60 bytes
    const val MIN_CIPHERTEXT_LENGTH_CHARS = 80 // 60 bytes in unpadded Base64URL

    val DOMAIN_SEPARATOR: ByteArray = "shhhhhh:sealedbox:v1".toByteArray(Charsets.UTF_8)
    val CANDIDATE_REGEX: Regex = Regex("[A-Za-z0-9_-]{80,}")

    // ASN.1 PKCS#8 prefix for X25519 PrivateKeyInfo (RFC 8410 OID 1.3.101.110)
    // 30 2e 02 01 00 30 05 06 03 2b 65 6e 04 22 04 20 followed by 32 raw private key bytes
    private val PKCS8_X25519_PREFIX = byteArrayOf(
        0x30.toByte(), 0x2e.toByte(), 0x02.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x30.toByte(), 0x05.toByte(), 0x06.toByte(), 0x03.toByte(), 0x2b.toByte(),
        0x65.toByte(), 0x6e.toByte(), 0x04.toByte(), 0x22.toByte(), 0x04.toByte(),
        0x20.toByte()
    )

    // ASN.1 X.509 SubjectPublicKeyInfo prefix for X25519 (RFC 8410 OID 1.3.101.110)
    // 30 2a 30 05 06 03 2b 65 6e 03 21 00 followed by 32 raw public key bytes
    private val X509_X25519_PREFIX = byteArrayOf(
        0x30.toByte(), 0x2a.toByte(), 0x30.toByte(), 0x05.toByte(), 0x06.toByte(),
        0x03.toByte(), 0x2b.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x03.toByte(),
        0x21.toByte(), 0x00.toByte()
    )

    // RFC 7748 Curve25519 base point u = 9 (little-endian 32 bytes: 0x09 followed by 31 zeros)
    private val BASE_POINT_BYTES = ByteArray(KEY_SIZE_BYTES).apply { this[0] = 9 }

    private val BASE_POINT_PUBLIC_KEY: PublicKey by lazy {
        rawBytesToPublicKey(BASE_POINT_BYTES)
    }

    /**
     * Generates a fresh X25519 key pair using the specified CSPRNG.
     * In RFC 7748, any 32 random bytes form a valid X25519 private key scalar.
     * The public key is derived via scalar multiplication with base point u = 9.
     */
    fun generateKeyPair(secureRandom: SecureRandom = SecureRandom()): CryptoKeyPair {
        val rawPriv = ByteArray(KEY_SIZE_BYTES)
        secureRandom.nextBytes(rawPriv)
        val pub = derivePublicKey(rawPriv)
        return CryptoKeyPair(privateKey = rawPriv, publicKey = pub)
    }

    /**
     * Derives the 32-byte raw X25519 public key from a 32-byte private key scalar
     * via scalar multiplication with the Curve25519 base point (u = 9).
     */
    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE_BYTES) {
            "Private key must be $KEY_SIZE_BYTES bytes, got ${privateKey.size}"
        }
        val privKey = rawBytesToPrivateKey(privateKey)
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privKey)
        ka.doPhase(BASE_POINT_PUBLIC_KEY, true)
        return ka.generateSecret()
    }

    /**
     * Reconstitutes a CryptoKeyPair from a 32-byte raw private key scalar.
     */
    fun createKeyPairFromPrivateKey(privateKey: ByteArray): CryptoKeyPair {
        return CryptoKeyPair(
            privateKey = privateKey,
            publicKey = derivePublicKey(privateKey)
        )
    }

    /**
     * Extracts 32 raw little-endian bytes from an X25519 PublicKey.
     */
    fun publicKeyToRawBytes(publicKey: PublicKey): ByteArray {
        val encoded = requireNotNull(publicKey.encoded) { "Public key encoding is null" }
        require(encoded.size >= KEY_SIZE_BYTES) { "Encoded public key too short: ${encoded.size}" }
        return encoded.copyOfRange(encoded.size - KEY_SIZE_BYTES, encoded.size)
    }

    /**
     * Extracts 32 raw bytes from an X25519 PrivateKey.
     */
    fun privateKeyToRawBytes(privateKey: PrivateKey): ByteArray {
        val encoded = requireNotNull(privateKey.encoded) { "Private key encoding is null" }
        require(encoded.size >= KEY_SIZE_BYTES) { "Encoded private key too short: ${encoded.size}" }
        return encoded.copyOfRange(encoded.size - KEY_SIZE_BYTES, encoded.size)
    }

    /**
     * Reconstructs an X25519 PublicKey from 32 raw little-endian bytes (RFC 7748)
     * using standard ASN.1 X.509 SubjectPublicKeyInfo encoding.
     */
    fun rawBytesToPublicKey(rawBytes: ByteArray): PublicKey {
        require(rawBytes.size == KEY_SIZE_BYTES) {
            "X25519 public key must be exactly $KEY_SIZE_BYTES bytes, got ${rawBytes.size}"
        }
        val x509Bytes = ByteArray(X509_X25519_PREFIX.size + KEY_SIZE_BYTES)
        System.arraycopy(X509_X25519_PREFIX, 0, x509Bytes, 0, X509_X25519_PREFIX.size)
        System.arraycopy(rawBytes, 0, x509Bytes, X509_X25519_PREFIX.size, KEY_SIZE_BYTES)
        val keyFactory = KeyFactory.getInstance("X25519")
        return keyFactory.generatePublic(X509EncodedKeySpec(x509Bytes))
    }

    /**
     * Reconstructs an X25519 PrivateKey from 32 raw bytes scalar
     * using standard ASN.1 PKCS#8 PrivateKeyInfo encoding.
     */
    fun rawBytesToPrivateKey(rawBytes: ByteArray): PrivateKey {
        require(rawBytes.size == KEY_SIZE_BYTES) {
            "X25519 private key must be exactly $KEY_SIZE_BYTES bytes, got ${rawBytes.size}"
        }
        val pkcs8Bytes = ByteArray(PKCS8_X25519_PREFIX.size + KEY_SIZE_BYTES)
        System.arraycopy(PKCS8_X25519_PREFIX, 0, pkcs8Bytes, 0, PKCS8_X25519_PREFIX.size)
        System.arraycopy(rawBytes, 0, pkcs8Bytes, PKCS8_X25519_PREFIX.size, KEY_SIZE_BYTES)
        val keyFactory = KeyFactory.getInstance("X25519")
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
    }

    /**
     * Encodes 32 raw public key bytes to a 43-character unpadded Base64URL string.
     */
    fun encodePublicKeyBase64Url(publicKey: ByteArray): String {
        require(publicKey.size == KEY_SIZE_BYTES) {
            "X25519 public key must be $KEY_SIZE_BYTES bytes, got ${publicKey.size}"
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)
    }

    /**
     * Decodes a 43-character unpadded Base64URL string to 32 raw public key bytes.
     */
    fun decodePublicKeyBase64Url(base64Url: String): ByteArray {
        val bytes = Base64.getUrlDecoder().decode(base64Url)
        require(bytes.size == KEY_SIZE_BYTES) {
            "Decoded public key must be $KEY_SIZE_BYTES bytes, got ${bytes.size}"
        }
        return bytes
    }

    /**
     * HKDF Extract step (RFC 5869): PRK = HMAC-SHA256(salt, IKM).
     */
    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF Expand step (RFC 5869): OKM = HKDF-Expand(PRK, info, L).
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF output length: $length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            mac.update(t)
            if (info != null && info.isNotEmpty()) {
                mac.update(info)
            }
            mac.update(counter)
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    /**
     * HKDF-SHA256 Extract-and-Expand (RFC 5869).
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray?, length: Int = 32): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, length)
    }

    /**
     * Encrypts binary plaintext using Sealed Box format.
     *
     * @param plaintext Raw bytes to encrypt (can be empty).
     * @param recipientPublicKey 32-byte raw recipient X25519 public key.
     * @param secureRandom CSPRNG instance for ephemeral key and nonce generation.
     * @return Unpadded Base64URL string (at least 80 characters).
     */
    fun encrypt(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        secureRandom: SecureRandom = SecureRandom()
    ): String {
        require(recipientPublicKey.size == KEY_SIZE_BYTES) {
            "Recipient public key must be $KEY_SIZE_BYTES bytes, got ${recipientPublicKey.size}"
        }

        val ephemeralKeyPair = generateKeyPair(secureRandom)
        val ephemeralPrivateKey = rawBytesToPrivateKey(ephemeralKeyPair.privateKey)
        val recipientKey = rawBytesToPublicKey(recipientPublicKey)

        val ka = KeyAgreement.getInstance("X25519")
        ka.init(ephemeralPrivateKey)
        ka.doPhase(recipientKey, true)
        val sharedSecret = ka.generateSecret()

        val derivedKey = hkdfSha256(
            ikm = sharedSecret,
            salt = ephemeralKeyPair.publicKey,
            info = DOMAIN_SEPARATOR,
            length = 32
        )

        val nonce = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "ChaCha20"), IvParameterSpec(nonce))
        val ciphertextWithTag = cipher.doFinal(plaintext)

        val payload = ByteArray(KEY_SIZE_BYTES + NONCE_SIZE_BYTES + ciphertextWithTag.size)
        System.arraycopy(ephemeralKeyPair.publicKey, 0, payload, 0, KEY_SIZE_BYTES)
        System.arraycopy(nonce, 0, payload, KEY_SIZE_BYTES, NONCE_SIZE_BYTES)
        System.arraycopy(ciphertextWithTag, 0, payload, KEY_SIZE_BYTES + NONCE_SIZE_BYTES, ciphertextWithTag.size)

        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    /**
     * Encrypts UTF-8 string plaintext into unpadded Base64URL sealed box.
     */
    fun encrypt(
        plaintext: String,
        recipientPublicKey: ByteArray,
        secureRandom: SecureRandom = SecureRandom()
    ): String {
        return encrypt(plaintext.toByteArray(Charsets.UTF_8), recipientPublicKey, secureRandom)
    }

    /**
     * Decrypts an unpadded Base64URL sealed box ciphertext.
     *
     * @param sealedBoxBase64 Unpadded Base64URL string of at least 80 characters.
     * @param recipientPrivateKey 32-byte raw recipient X25519 private key scalar.
     * @return Decrypted plaintext bytes.
     * @throws AEADBadTagException If authentication tag verification fails.
     * @throws IllegalArgumentException If payload format or sizes are invalid.
     */
    fun decrypt(
        sealedBoxBase64: String,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        require(recipientPrivateKey.size == KEY_SIZE_BYTES) {
            "Recipient private key must be $KEY_SIZE_BYTES bytes, got ${recipientPrivateKey.size}"
        }

        val payload = Base64.getUrlDecoder().decode(sealedBoxBase64)
        if (payload.size < OVERHEAD_BYTES) {
            throw IllegalArgumentException(
                "Payload size (${payload.size}) is smaller than minimum overhead ($OVERHEAD_BYTES bytes)"
            )
        }

        val ephemeralPubBytes = payload.copyOfRange(0, KEY_SIZE_BYTES)
        val nonce = payload.copyOfRange(KEY_SIZE_BYTES, KEY_SIZE_BYTES + NONCE_SIZE_BYTES)
        val ciphertextWithTag = payload.copyOfRange(KEY_SIZE_BYTES + NONCE_SIZE_BYTES, payload.size)

        val ephemeralKey = rawBytesToPublicKey(ephemeralPubBytes)
        val recipientPriv = rawBytesToPrivateKey(recipientPrivateKey)

        val ka = KeyAgreement.getInstance("X25519")
        ka.init(recipientPriv)
        ka.doPhase(ephemeralKey, true)
        val sharedSecret = ka.generateSecret()

        val derivedKey = hkdfSha256(
            ikm = sharedSecret,
            salt = ephemeralPubBytes,
            info = DOMAIN_SEPARATOR,
            length = 32
        )

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "ChaCha20"), IvParameterSpec(nonce))
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * Decrypts an unpadded Base64URL sealed box ciphertext and decodes UTF-8 string.
     */
    fun decryptToString(
        sealedBoxBase64: String,
        recipientPrivateKey: ByteArray
    ): String {
        val decryptedBytes = decrypt(sealedBoxBase64, recipientPrivateKey)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Scans input text for Base64URL ciphertext candidates (>= 80 chars),
     * attempts sealed box decryption for each, and replaces successful candidates in-place.
     *
     * @param inputText Raw text potentially containing ciphertext substrings.
     * @param recipientPrivateKey 32-byte raw recipient X25519 private key scalar.
     * @return DecryptResult indicating NO_CANDIDATES, AUTH_FAILED, or SUCCESS(text, secrets).
     */
    fun decryptTextWithCandidates(
        inputText: String,
        recipientPrivateKey: ByteArray
    ): DecryptResult {
        val matches = CANDIDATE_REGEX.findAll(inputText).toList()
        if (matches.isEmpty()) {
            return DecryptResult.NoCandidates
        }

        val secrets = mutableListOf<String>()
        val successfulMatches = mutableListOf<Pair<MatchResult, String>>()

        for (match in matches) {
            try {
                val decrypted = decryptToString(match.value, recipientPrivateKey)
                successfulMatches.add(match to decrypted)
                secrets.add(decrypted)
            } catch (_: Exception) {
                // Ciphertext candidate failed decryption; leave unchanged
            }
        }

        if (successfulMatches.isEmpty()) {
            return DecryptResult.AuthFailed
        }

        var updatedText = inputText
        for ((match, decrypted) in successfulMatches.asReversed()) {
            updatedText = updatedText.replaceRange(match.range, decrypted)
        }

        return DecryptResult.Success(text = updatedText, secrets = secrets)
    }
}
