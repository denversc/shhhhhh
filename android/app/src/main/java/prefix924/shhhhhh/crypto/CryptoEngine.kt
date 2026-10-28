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
     * No Base64URL string of 166 or more characters was found in the input text.
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
    ) : DecryptResult {
        var ranges: List<IntRange> = emptyList()
            internal set
    }

    companion object {
        val NO_CANDIDATES: DecryptResult get() = NoCandidates
        val AUTH_FAILED: DecryptResult get() = AuthFailed
    }
}

/**
 * Native sealed box cryptography engine for shhhhhh v2 (Floored PADME).
 *
 * Implements:
 * - Clean-break protocol version byte: 0x02
 * - X25519 Diffie-Hellman key agreement (RFC 7748)
 * - HKDF-SHA256 key derivation (RFC 5869) with domain separator "shhhhhh:sealedbox:v2"
 * - ChaCha20-Poly1305 AEAD authenticated encryption (RFC 8439)
 * - Floored PADME length-hiding bucketing algorithm (RFC 9543 with minFloor = 128)
 * - Length-prefixed inner buffer framing: [2-byte BE uint16 len] + [plaintext] + [CSPRNG padding]
 * - Sealed Box v2 format:
 *     0x02 || ephemeralPublicKey[32] || nonce[12] || ciphertext[B] || poly1305Tag[16]
 * - Additional Authenticated Data (AAD):
 *     0x02 || ephemeralPublicKey[32] || nonce[12] (45 bytes)
 * - Fixed overhead: 61 bytes (45-byte header + 16-byte tag)
 * - Minimum wire payload: 189 bytes (128-byte inner buffer + 61 bytes overhead) -> 252 unpadded Base64URL chars
 * - Candidate scanning regex: [A-Za-z0-9_-]{166,} with fast rejection on byte 0 != 0x02
 *
 * Uses portable ASN.1 PKCS#8 and X.509 encodings for 100% cross-platform compatibility
 * between Android Conscrypt and JVM SunEC providers.
 */
object CryptoEngine {
    const val PROTOCOL_VERSION: Byte = 0x02
    const val KEY_SIZE_BYTES = 32
    const val NONCE_SIZE_BYTES = 12
    const val TAG_SIZE_BYTES = 16
    const val HEADER_SIZE_BYTES = 1 + KEY_SIZE_BYTES + NONCE_SIZE_BYTES // 45 bytes
    const val FIXED_OVERHEAD_BYTES = HEADER_SIZE_BYTES + TAG_SIZE_BYTES  // 61 bytes
    const val OVERHEAD_BYTES = FIXED_OVERHEAD_BYTES // 61 bytes
    const val DEFAULT_MIN_FLOOR = 128
    const val MIN_CIPHERTEXT_LENGTH_CHARS = 166

    val DOMAIN_SEPARATOR: ByteArray = "shhhhhh:sealedbox:v2".toByteArray(Charsets.UTF_8)
    val CANDIDATE_REGEX: Regex = Regex("[A-Za-z0-9_-]{166,}")

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
     * Floored PADME length-hiding bucketing algorithm (RFC 9543 with Minimum Floor).
     *
     * Computes the target inner buffer size for a given unpadded framed length (N + 2).
     *
     * @param unpaddedLength Unpadded framed length (|P| + 2).
     * @param minFloor Minimum inner buffer size floor (default 128 bytes).
     * @return Target padded buffer size in bytes.
     */
    fun padLengthPadme(unpaddedLength: Int, minFloor: Int = DEFAULT_MIN_FLOOR): Int {
        require(unpaddedLength >= 0) { "Length must be non-negative" }
        val effective = maxOf(unpaddedLength, minFloor)
        if (effective <= 0) return 0
        val e = 31 - Integer.numberOfLeadingZeros(effective)
        val s = 32 - Integer.numberOfLeadingZeros(e)
        val z = e - s
        if (z <= 0) return effective
        val mask = (1 shl z) - 1
        val padded = (effective + mask) and mask.inv()
        return maxOf(padded, minFloor)
    }

    /**
     * Encodes plaintext into a length-prefixed framed inner buffer with CSPRNG random padding.
     *
     * Inner buffer format:
     * - Bytes 0..1: Big-Endian uint16 plaintext length N (0 <= N <= 65533)
     * - Bytes 2..2+N-1: Plaintext bytes
     * - Bytes 2+N..targetSize-1: CSPRNG random padding bytes
     *
     * @param plaintext Raw plaintext bytes.
     * @param targetSize Target inner buffer size in bytes.
     * @param secureRandom CSPRNG instance for generating padding bytes.
     * @return Framed inner buffer byte array of length [targetSize].
     */
    fun encodeFramedPayload(plaintext: ByteArray, targetSize: Int, secureRandom: SecureRandom): ByteArray {
        val pLen = plaintext.size
        require(pLen <= 65533) { "Plaintext exceeds maximum limit of 65533 bytes, got $pLen" }
        require(targetSize >= pLen + 2) { "Target size ($targetSize) smaller than framed payload (${pLen + 2})" }

        val buffer = ByteArray(targetSize)
        buffer[0] = ((pLen ushr 8) and 0xFF).toByte()
        buffer[1] = (pLen and 0xFF).toByte()
        if (pLen > 0) {
            System.arraycopy(plaintext, 0, buffer, 2, pLen)
        }
        val padCount = targetSize - 2 - pLen
        if (padCount > 0) {
            val padBytes = ByteArray(padCount)
            secureRandom.nextBytes(padBytes)
            System.arraycopy(padBytes, 0, buffer, 2 + pLen, padCount)
        }
        return buffer
    }

    /**
     * Decodes a decrypted framed inner buffer, verifying the 2-byte Big-Endian length prefix
     * and extracting the original plaintext bytes.
     *
     * @param decryptedBytes Decrypted inner buffer.
     * @return Extracted plaintext bytes.
     * @throws AEADBadTagException If buffer is shorter than 2 bytes or length prefix exceeds bounds.
     */
    fun decodeFramedPayload(decryptedBytes: ByteArray): ByteArray {
        if (decryptedBytes.size < 2) {
            throw AEADBadTagException("Decrypted buffer too short")
        }
        val declaredLen = ((decryptedBytes[0].toInt() and 0xFF) shl 8) or
                (decryptedBytes[1].toInt() and 0xFF)
        if (declaredLen < 0 || declaredLen > decryptedBytes.size - 2) {
            throw AEADBadTagException("Length prefix exceeds buffer bounds")
        }
        return decryptedBytes.copyOfRange(2, 2 + declaredLen)
    }

    /**
     * Encrypts binary plaintext using Floored PADME Sealed Box v2 format with a custom floor.
     *
     * @param plaintext Raw bytes to encrypt (0 <= length <= 65533).
     * @param recipientPublicKey 32-byte raw recipient X25519 public key.
     * @param minFloor Minimum inner buffer floor.
     * @param secureRandom CSPRNG instance for ephemeral key, nonce, and padding generation.
     * @return Unpadded Base64URL string.
     */
    fun encrypt(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        minFloor: Int,
        secureRandom: SecureRandom = SecureRandom()
    ): String {
        require(recipientPublicKey.size == KEY_SIZE_BYTES) {
            "Recipient public key must be $KEY_SIZE_BYTES bytes, got ${recipientPublicKey.size}"
        }

        val unpaddedLen = plaintext.size + 2
        val targetSize = padLengthPadme(unpaddedLen, minFloor)
        val innerBuffer = encodeFramedPayload(plaintext, targetSize, secureRandom)

        val ephemeralKeyPair = generateKeyPair(secureRandom)
        val nonce = ByteArray(NONCE_SIZE_BYTES).also { secureRandom.nextBytes(it) }

        val ephemeralPrivKey = rawBytesToPrivateKey(ephemeralKeyPair.privateKey)
        val recipientPubKey = rawBytesToPublicKey(recipientPublicKey)
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(ephemeralPrivKey)
        ka.doPhase(recipientPubKey, true)
        val sharedSecret = ka.generateSecret()

        val derivedKey = hkdfSha256(
            ikm = sharedSecret,
            salt = ephemeralKeyPair.publicKey,
            info = DOMAIN_SEPARATOR,
            length = 32
        )

        val aad = ByteArray(HEADER_SIZE_BYTES)
        aad[0] = PROTOCOL_VERSION
        System.arraycopy(ephemeralKeyPair.publicKey, 0, aad, 1, KEY_SIZE_BYTES)
        System.arraycopy(nonce, 0, aad, 1 + KEY_SIZE_BYTES, NONCE_SIZE_BYTES)

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        val ciphertextWithTag = cipher.doFinal(innerBuffer)

        val wirePayload = ByteArray(HEADER_SIZE_BYTES + ciphertextWithTag.size)
        System.arraycopy(aad, 0, wirePayload, 0, HEADER_SIZE_BYTES)
        System.arraycopy(ciphertextWithTag, 0, wirePayload, HEADER_SIZE_BYTES, ciphertextWithTag.size)

        return Base64.getUrlEncoder().withoutPadding().encodeToString(wirePayload)
    }

    /**
     * Encrypts binary plaintext using Floored PADME Sealed Box v2 format with default minFloor = 128 bytes.
     *
     * @param plaintext Raw bytes to encrypt (0 <= length <= 65533).
     * @param recipientPublicKey 32-byte raw recipient X25519 public key.
     * @param secureRandom CSPRNG instance for ephemeral key, nonce, and padding generation.
     * @return Unpadded Base64URL string (at least 252 characters for minFloor = 128).
     */
    fun encrypt(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        secureRandom: SecureRandom = SecureRandom()
    ): String = encrypt(plaintext, recipientPublicKey, minFloor = DEFAULT_MIN_FLOOR, secureRandom = secureRandom)

    /**
     * Encrypts UTF-8 string plaintext into unpadded Base64URL sealed box v2 with a custom floor.
     */
    fun encrypt(
        plaintext: String,
        recipientPublicKey: ByteArray,
        minFloor: Int,
        secureRandom: SecureRandom = SecureRandom()
    ): String = encrypt(plaintext.toByteArray(Charsets.UTF_8), recipientPublicKey, minFloor, secureRandom)

    /**
     * Encrypts UTF-8 string plaintext into unpadded Base64URL sealed box v2 with default minFloor = 128 bytes.
     */
    fun encrypt(
        plaintext: String,
        recipientPublicKey: ByteArray,
        secureRandom: SecureRandom = SecureRandom()
    ): String = encrypt(plaintext.toByteArray(Charsets.UTF_8), recipientPublicKey, minFloor = DEFAULT_MIN_FLOOR, secureRandom = secureRandom)

    /**
     * Decrypts an unpadded Base64URL sealed box v2 ciphertext.
     *
     * @param sealedBoxBase64 Unpadded Base64URL string of at least 166 characters.
     * @param recipientPrivateKey 32-byte raw recipient X25519 private key scalar.
     * @return Decrypted plaintext bytes.
     * @throws AEADBadTagException If authentication tag verification fails or protocol version != 0x02.
     * @throws IllegalArgumentException If recipient private key size is invalid, base64 is invalid, or payload size < 63 bytes.
     */
    fun decrypt(
        sealedBoxBase64: String,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        require(recipientPrivateKey.size == KEY_SIZE_BYTES) {
            "Recipient private key must be $KEY_SIZE_BYTES bytes, got ${recipientPrivateKey.size}"
        }

        val payload = try {
            Base64.getUrlDecoder().decode(sealedBoxBase64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64URL string", e)
        }

        if (payload.size < FIXED_OVERHEAD_BYTES + 2) {
            throw IllegalArgumentException(
                "Payload size (${payload.size}) is smaller than minimum overhead (${FIXED_OVERHEAD_BYTES + 2} bytes)"
            )
        }

        if (payload[0] != PROTOCOL_VERSION) {
            throw AEADBadTagException("Unsupported or invalid protocol version: ${payload[0]}")
        }

        val ephemeralPubBytes = payload.copyOfRange(1, 1 + KEY_SIZE_BYTES)
        val nonce = payload.copyOfRange(1 + KEY_SIZE_BYTES, HEADER_SIZE_BYTES)
        val ciphertextWithTag = payload.copyOfRange(HEADER_SIZE_BYTES, payload.size)
        val aad = payload.copyOfRange(0, HEADER_SIZE_BYTES)

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
        cipher.updateAAD(aad)
        val decryptedInner = cipher.doFinal(ciphertextWithTag)

        return decodeFramedPayload(decryptedInner)
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
     * Scans input text for Base64URL ciphertext candidates (>= 166 chars),
     * performs fast rejection on byte 0 != 0x02 before X25519 key agreement,
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
            val candidateStr = match.value
            // Fast rejection check before expensive X25519 scalar multiplication
            val rawToken = try {
                Base64.getUrlDecoder().decode(candidateStr)
            } catch (_: Exception) {
                continue
            }
            if (rawToken.size < FIXED_OVERHEAD_BYTES + 2 || rawToken[0] != PROTOCOL_VERSION) {
                continue
            }

            try {
                val decrypted = decryptToString(candidateStr, recipientPrivateKey)
                successfulMatches.add(match to decrypted)
                secrets.add(decrypted)
            } catch (_: Exception) {
                // Ciphertext candidate failed decryption; leave unchanged
            }
        }

        if (successfulMatches.isEmpty()) {
            return DecryptResult.AuthFailed
        }

        val ranges = mutableListOf<IntRange>()
        val builder = StringBuilder()
        var lastIndex = 0
        for ((match, decrypted) in successfulMatches) {
            builder.append(inputText, lastIndex, match.range.first)
            val start = builder.length
            builder.append(decrypted)
            val end = builder.length
            ranges.add(start until end)
            lastIndex = match.range.last + 1
        }
        builder.append(inputText, lastIndex, inputText.length)
        val updatedText = builder.toString()

        val success = DecryptResult.Success(text = updatedText, secrets = secrets)
        success.ranges = ranges
        return success
    }
}
