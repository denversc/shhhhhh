//
//  CryptoEngine.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation
import Security

/// Representation of a Curve25519 / X25519 key pair with 32-byte raw keys.
public struct CryptoKeyPair: Equatable {
  public let privateKey: Curve25519.KeyAgreement.PrivateKey
  public let publicKey: Curve25519.KeyAgreement.PublicKey

  /// Unpadded Base64URL representation of the 32-byte raw public key (43 characters).
  public var publicKeyBase64Url: String {
    CryptoEngine.base64UrlEncode(publicKey.rawRepresentation)
  }

  /// Unpadded Base64URL representation of the 32-byte raw private key (43 characters).
  public var privateKeyBase64Url: String {
    CryptoEngine.base64UrlEncode(privateKey.rawRepresentation)
  }

  public init(privateKey: Curve25519.KeyAgreement.PrivateKey) {
    self.privateKey = privateKey
    self.publicKey = privateKey.publicKey
  }

  public init(
    privateKey: Curve25519.KeyAgreement.PrivateKey, publicKey: Curve25519.KeyAgreement.PublicKey
  ) {
    self.privateKey = privateKey
    self.publicKey = publicKey
  }

  /// Reconstitutes a CryptoKeyPair from 32 raw private key bytes.
  public init(rawRepresentation: Data) throws {
    guard rawRepresentation.count == CryptoEngine.keySizeBytes else {
      throw CryptoError.invalidKeySize(
        expected: CryptoEngine.keySizeBytes, actual: rawRepresentation.count)
    }
    let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: rawRepresentation)
    self.init(privateKey: priv)
  }

  public static func == (lhs: CryptoKeyPair, rhs: CryptoKeyPair) -> Bool {
    lhs.privateKey.rawRepresentation == rhs.privateKey.rawRepresentation
      && lhs.publicKey.rawRepresentation == rhs.publicKey.rawRepresentation
  }
}

/// Result of scanning and decrypting text with candidate ciphertext substrings.
public enum DecryptResult: Equatable {
  /// No Base64URL string of 166 or more characters was found in the input text.
  case noCandidates

  /// One or more ciphertext candidate strings were found, but none could be
  /// decrypted with the recipient's private key.
  case authFailed

  /// Decryption succeeded for one or more candidate strings.
  ///
  /// - Parameters:
  ///   - text: The input text with valid ciphertexts replaced in-place by their decrypted plaintexts.
  ///   - secrets: All decrypted secret strings in their order of appearance in the text.
  ///   - ranges: Exact character ranges of each decrypted secret within the updated text.
  case success(text: String, secrets: [String], ranges: [Range<String.Index>] = [])

  public var text: String? {
    if case .success(let text, _, _) = self { return text }
    return nil
  }

  public var secrets: [String]? {
    if case .success(_, let secrets, _) = self { return secrets }
    return nil
  }

  public var ranges: [Range<String.Index>]? {
    if case .success(_, _, let ranges) = self { return ranges }
    return nil
  }

  public static func == (lhs: DecryptResult, rhs: DecryptResult) -> Bool {
    switch (lhs, rhs) {
    case (.noCandidates, .noCandidates):
      return true
    case (.authFailed, .authFailed):
      return true
    case (
      .success(let lText, let lSecrets, let lRanges), .success(let rText, let rSecrets, let rRanges)
    ):
      if lText != rText || lSecrets != rSecrets {
        return false
      }
      if !lRanges.isEmpty && !rRanges.isEmpty {
        return lRanges == rRanges
      }
      return true
    default:
      return false
    }
  }
}

/// Errors thrown by CryptoEngine operations.
public enum CryptoError: LocalizedError, Equatable {
  case invalidKeySize(expected: Int, actual: Int)
  case invalidPayload(String)
  case authenticationFailed
  case invalidBase64
  case invalidStringData

  public var errorDescription: String? {
    switch self {
    case .invalidKeySize(let expected, let actual):
      return "Invalid key size: expected \(expected) bytes, got \(actual) bytes"
    case .invalidPayload(let message):
      return "Invalid sealed box payload: \(message)"
    case .authenticationFailed:
      return "Authentication failed: invalid tag or corrupted ciphertext"
    case .invalidBase64:
      return "Invalid Base64URL string"
    case .invalidStringData:
      return "Decrypted data is not valid UTF-8 string"
    }
  }
}

/// Native sealed box cryptography engine for shhhhhh on iOS (v2 Floored PADME).
///
/// Implements:
/// - X25519 Diffie-Hellman key agreement (RFC 7748) via Apple CryptoKit
/// - HKDF-SHA256 key derivation (RFC 5869) with domain separator "shhhhhh:sealedbox:v2"
/// - ChaCha20-Poly1305 AEAD authenticated encryption (RFC 8439)
/// - Floored PADME length-hiding bucketing (RFC 9543 with minimum floor = 128 bytes)
/// - Inner framing: [uint16_be length] || plaintext || CSPRNG random padding
/// - Outer envelope: version(0x02) || ephemeralPublicKey[32] || nonce[12] || ciphertext[B] || tag[16]
/// - Authenticated Additional Data (AAD): version(0x02) || ephemeralPublicKey[32] || nonce[12] (45 bytes)
/// - Unpadded Base64URL wire serialization
public enum CryptoEngine {
  public static let protocolVersion: UInt8 = 0x02
  public static let keySizeBytes = 32
  public static let nonceSizeBytes = 12
  public static let tagSizeBytes = 16
  public static let headerSizeBytes = 1 + keySizeBytes + nonceSizeBytes  // 45 bytes
  public static let fixedOverheadBytes = headerSizeBytes + tagSizeBytes  // 61 bytes
  public static let overheadBytes = fixedOverheadBytes  // 61 bytes (compatibility alias)
  public static let minCiphertextLengthChars = 166
  public static let defaultMinFloor = 128

  public static let domainSeparator = "shhhhhh:sealedbox:v2".data(using: .utf8)!
  public static let candidateRegex = try! NSRegularExpression(pattern: "[A-Za-z0-9_-]{166,}")

  // MARK: - Key Generation & Derivation

  /// Generates a fresh X25519 key pair.
  public static func generateKeyPair() -> CryptoKeyPair {
    let priv = Curve25519.KeyAgreement.PrivateKey()
    return CryptoKeyPair(privateKey: priv)
  }

  /// Derives the 32-byte raw X25519 public key from a 32-byte raw private key scalar.
  public static func derivePublicKey(fromPrivateKey rawPrivateKey: Data) throws -> Data {
    guard rawPrivateKey.count == keySizeBytes else {
      throw CryptoError.invalidKeySize(expected: keySizeBytes, actual: rawPrivateKey.count)
    }
    let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: rawPrivateKey)
    return priv.publicKey.rawRepresentation
  }

  /// Reconstitutes a CryptoKeyPair from a 32-byte raw private key scalar.
  public static func createKeyPair(fromPrivateKey rawPrivateKey: Data) throws -> CryptoKeyPair {
    try CryptoKeyPair(rawRepresentation: rawPrivateKey)
  }

  // MARK: - Base64URL Helpers

  /// Encodes Data to an unpadded Base64URL string.
  public static func base64UrlEncode(_ data: Data) -> String {
    data.base64EncodedString()
      .replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_")
      .trimmingCharacters(in: CharacterSet(charactersIn: "="))
  }

  /// Decodes an unpadded Base64URL string back to Data.
  public static func base64UrlDecode(_ base64Url: String) -> Data? {
    var base64 =
      base64Url
      .replacingOccurrences(of: "-", with: "+")
      .replacingOccurrences(of: "_", with: "/")
    let remainder = base64.count % 4
    if remainder == 1 { return nil }
    if remainder > 0 {
      base64.append(String(repeating: "=", count: 4 - remainder))
    }
    return Data(base64Encoded: base64)
  }

  /// Encodes 32 raw public key bytes to a 43-character unpadded Base64URL string.
  public static func encodePublicKeyBase64Url(_ publicKey: Data) throws -> String {
    guard publicKey.count == keySizeBytes else {
      throw CryptoError.invalidKeySize(expected: keySizeBytes, actual: publicKey.count)
    }
    return base64UrlEncode(publicKey)
  }

  /// Decodes a 43-character unpadded Base64URL string to 32 raw public key bytes.
  public static func decodePublicKeyBase64Url(_ base64Url: String) throws -> Data {
    guard let data = base64UrlDecode(base64Url) else {
      throw CryptoError.invalidBase64
    }
    guard data.count == keySizeBytes else {
      throw CryptoError.invalidKeySize(expected: keySizeBytes, actual: data.count)
    }
    return data
  }

  // MARK: - HKDF-SHA256 (RFC 5869)

  /// HKDF Extract step (RFC 5869): PRK = HMAC-SHA256(salt, IKM).
  public static func hkdfExtract(salt: Data?, ikm: Data) -> Data {
    let actualSalt: Data
    if let salt, !salt.isEmpty {
      actualSalt = salt
    } else {
      actualSalt = Data(repeating: 0, count: 32)
    }
    let key = SymmetricKey(data: actualSalt)
    let mac = HMAC<SHA256>.authenticationCode(for: ikm, using: key)
    return Data(mac)
  }

  /// HKDF Expand step (RFC 5869): OKM = HKDF-Expand(PRK, info, L).
  public static func hkdfExpand(prk: Data, info: Data?, length: Int) throws -> Data {
    guard length >= 1 && length <= 255 * 32 else {
      throw CryptoError.invalidPayload("Invalid HKDF output length: \(length)")
    }
    let key = SymmetricKey(data: prk)
    var okm = Data()
    var t = Data()
    var counter: UInt8 = 1

    while okm.count < length {
      var hmac = HMAC<SHA256>(key: key)
      hmac.update(data: t)
      if let info, !info.isEmpty {
        hmac.update(data: info)
      }
      hmac.update(data: Data([counter]))
      let result = hmac.finalize()
      t = Data(result)
      let toCopy = min(t.count, length - okm.count)
      okm.append(t.prefix(toCopy))
      counter += 1
    }
    return okm
  }

  /// HKDF-SHA256 Extract-and-Expand (RFC 5869).
  public static func hkdfSha256(ikm: Data, salt: Data?, info: Data?, length: Int = 32) throws
    -> Data
  {
    let prk = hkdfExtract(salt: salt, ikm: ikm)
    return try hkdfExpand(prk: prk, info: info, length: length)
  }

  // MARK: - PADME Bucketing Algorithm (RFC 9543 with Minimum Floor)

  /// Computes the target padded buffer size using Floored PADME (RFC 9543).
  ///
  /// - Parameters:
  ///   - unpaddedLength: Length of the framed payload (plaintext length + 2 bytes for length prefix).
  ///   - minFloor: Minimum buffer floor size in bytes (default: 128).
  /// - Returns: Target buffer size in bytes.
  public static func padLengthPadme(unpaddedLength: Int, minFloor: Int = defaultMinFloor) -> Int {
    precondition(unpaddedLength >= 0, "Length must be non-negative")
    let effective = max(unpaddedLength, minFloor)
    let e = Int.bitWidth - 1 - effective.leadingZeroBitCount
    let s = Int.bitWidth - e.leadingZeroBitCount
    let z = e - s
    if z <= 0 { return effective }
    let mask = (1 << z) - 1
    let padded = (effective + mask) & ~mask
    return max(padded, minFloor)
  }

  // MARK: - Envelope Framing

  /// Encodes plaintext into the v2 inner framed buffer format:
  /// M[0..1] = Big-Endian uint16 (plaintext length N, 0 <= N <= 65533)
  /// M[2..2+N-1] = plaintext bytes
  /// M[2+N..B-1] = CSPRNG random padding bytes
  public static func encodeFramedPayload(
    plaintext: Data,
    targetSize: Int,
    paddingBytesOverride: Data? = nil
  ) throws -> Data {
    let pLen = plaintext.count
    guard pLen <= 65533 else {
      throw CryptoError.invalidPayload("Plaintext exceeds maximum 64KB limit")
    }
    guard targetSize >= pLen + 2 else {
      throw CryptoError.invalidPayload("Target size smaller than framed payload")
    }

    var buffer = Data(count: targetSize)
    buffer[0] = UInt8((pLen >> 8) & 0xFF)
    buffer[1] = UInt8(pLen & 0xFF)
    if pLen > 0 {
      buffer.replaceSubrange(2..<(2 + pLen), with: plaintext)
    }

    let padCount = targetSize - 2 - pLen
    if padCount > 0 {
      if let override = paddingBytesOverride {
        guard override.count >= padCount else {
          throw CryptoError.invalidPayload(
            "Padding override too short: expected \(padCount) bytes, got \(override.count)")
        }
        buffer.replaceSubrange((2 + pLen)..<targetSize, with: override.prefix(padCount))
      } else {
        var padBytes = [UInt8](repeating: 0, count: padCount)
        guard SecRandomCopyBytes(kSecRandomDefault, padCount, &padBytes) == errSecSuccess else {
          throw CryptoError.invalidPayload("CSPRNG failure generating padding")
        }
        buffer.replaceSubrange((2 + pLen)..<targetSize, with: padBytes)
      }
    }
    return buffer
  }

  /// Decodes the v2 inner framed buffer and extracts the plaintext bytes.
  public static func decodeFramedPayload(_ decryptedBytes: Data) throws -> Data {
    guard decryptedBytes.count >= 2 else {
      throw CryptoError.authenticationFailed
    }
    let declaredLen = (Int(decryptedBytes[0]) << 8) | Int(decryptedBytes[1])
    guard declaredLen <= decryptedBytes.count - 2 else {
      throw CryptoError.authenticationFailed
    }
    return decryptedBytes.subdata(in: 2..<(2 + declaredLen))
  }

  // MARK: - Encryption

  /// Encrypts binary plaintext into an unpadded Base64URL sealed box with Floored PADME length hiding.
  ///
  /// - Parameters:
  ///   - plaintext: Raw bytes to encrypt.
  ///   - recipientPublicKey: Recipient X25519 public key.
  ///   - ephemeralPrivateKeyOverride: Optional ephemeral private key (useful for deterministic tests).
  ///   - nonceOverride: Optional 12-byte nonce (useful for deterministic tests).
  /// - Returns: Unpadded Base64URL sealed box string (at least 252 characters for minFloor=128).
  public static func encrypt(
    _ plaintext: Data,
    recipientPublicKey: Curve25519.KeyAgreement.PublicKey,
    ephemeralPrivateKeyOverride: Curve25519.KeyAgreement.PrivateKey? = nil,
    nonceOverride: ChaChaPoly.Nonce? = nil
  ) throws -> String {
    try encrypt(
      plaintext,
      recipientPublicKey: recipientPublicKey,
      minFloor: defaultMinFloor,
      ephemeralPrivateKeyOverride: ephemeralPrivateKeyOverride,
      nonceOverride: nonceOverride,
      paddingBytesOverride: nil
    )
  }

  /// Encrypts binary plaintext into an unpadded Base64URL sealed box with custom minFloor and test overrides.
  public static func encrypt(
    _ plaintext: Data,
    recipientPublicKey: Curve25519.KeyAgreement.PublicKey,
    minFloor: Int,
    ephemeralPrivateKeyOverride: Curve25519.KeyAgreement.PrivateKey? = nil,
    nonceOverride: ChaChaPoly.Nonce? = nil,
    paddingBytesOverride: Data? = nil
  ) throws -> String {
    let unpaddedLen = plaintext.count + 2
    let targetSize = padLengthPadme(unpaddedLength: unpaddedLen, minFloor: minFloor)
    let innerBuffer = try encodeFramedPayload(
      plaintext: plaintext,
      targetSize: targetSize,
      paddingBytesOverride: paddingBytesOverride
    )

    let ephemeralPrivateKey = ephemeralPrivateKeyOverride ?? Curve25519.KeyAgreement.PrivateKey()
    let ephemeralPubBytes = ephemeralPrivateKey.publicKey.rawRepresentation

    let sharedSecret = try ephemeralPrivateKey.sharedSecretFromKeyAgreement(
      with: recipientPublicKey)
    let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
      using: SHA256.self,
      salt: ephemeralPubBytes,
      sharedInfo: domainSeparator,
      outputByteCount: 32
    )

    let nonce = nonceOverride ?? ChaChaPoly.Nonce()

    var aad = Data()
    aad.reserveCapacity(headerSizeBytes)
    aad.append(protocolVersion)
    aad.append(ephemeralPubBytes)
    aad.append(contentsOf: nonce)

    let sealedBox = try ChaChaPoly.seal(
      innerBuffer, using: symmetricKey, nonce: nonce, authenticating: aad)

    // Sealed Box v2 Wire Format:
    // protocolVersion (1B) + ephemeralPublicKey (32B) + nonce (12B) + ciphertext (B bytes) + tag (16B)
    var wirePayload = Data()
    wirePayload.reserveCapacity(fixedOverheadBytes + innerBuffer.count)
    wirePayload.append(aad)
    wirePayload.append(sealedBox.ciphertext)
    wirePayload.append(sealedBox.tag)

    return base64UrlEncode(wirePayload)
  }

  /// Encrypts UTF-8 string plaintext into an unpadded Base64URL sealed box.
  public static func encrypt(
    _ plaintext: String,
    recipientPublicKey: Curve25519.KeyAgreement.PublicKey
  ) throws -> String {
    try encrypt(Data(plaintext.utf8), recipientPublicKey: recipientPublicKey)
  }

  /// Encrypts UTF-8 string plaintext into an unpadded Base64URL sealed box with custom minFloor.
  public static func encrypt(
    _ plaintext: String,
    recipientPublicKey: Curve25519.KeyAgreement.PublicKey,
    minFloor: Int
  ) throws -> String {
    try encrypt(
      Data(plaintext.utf8),
      recipientPublicKey: recipientPublicKey,
      minFloor: minFloor,
      ephemeralPrivateKeyOverride: nil,
      nonceOverride: nil,
      paddingBytesOverride: nil
    )
  }

  /// Encrypts binary plaintext using recipient raw public key bytes.
  public static func encrypt(
    _ plaintext: Data,
    recipientPublicKeyData: Data
  ) throws -> String {
    guard recipientPublicKeyData.count == keySizeBytes else {
      throw CryptoError.invalidKeySize(expected: keySizeBytes, actual: recipientPublicKeyData.count)
    }
    let pubKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientPublicKeyData)
    return try encrypt(plaintext, recipientPublicKey: pubKey)
  }

  /// Encrypts UTF-8 string plaintext using recipient raw public key bytes.
  public static func encrypt(
    _ plaintext: String,
    recipientPublicKeyData: Data
  ) throws -> String {
    try encrypt(Data(plaintext.utf8), recipientPublicKeyData: recipientPublicKeyData)
  }

  // MARK: - Decryption

  /// Decrypts an unpadded Base64URL sealed box ciphertext with Floored PADME framing.
  ///
  /// - Parameters:
  ///   - sealedBoxBase64: Unpadded Base64URL string of at least 63 bytes (>= 166 characters).
  ///   - recipientPrivateKey: Recipient X25519 private key.
  /// - Returns: Decrypted plaintext bytes.
  public static func decrypt(
    _ sealedBoxBase64: String,
    recipientPrivateKey: Curve25519.KeyAgreement.PrivateKey
  ) throws -> Data {
    guard let payload = base64UrlDecode(sealedBoxBase64) else {
      throw CryptoError.invalidBase64
    }
    guard payload.count >= fixedOverheadBytes + 2, payload[0] == protocolVersion else {
      throw CryptoError.authenticationFailed
    }

    let aad = payload.prefix(headerSizeBytes)
    let ephemeralPubBytes = payload.subdata(in: 1..<33)
    let nonceBytes = payload.subdata(in: 33..<45)
    let ciphertextWithTag = payload.subdata(in: 45..<payload.count)

    let ephemeralPublicKey: Curve25519.KeyAgreement.PublicKey
    do {
      ephemeralPublicKey = try Curve25519.KeyAgreement.PublicKey(
        rawRepresentation: ephemeralPubBytes)
    } catch {
      throw CryptoError.authenticationFailed
    }

    let sharedSecret: SharedSecret
    do {
      sharedSecret = try recipientPrivateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)
    } catch {
      throw CryptoError.authenticationFailed
    }

    let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
      using: SHA256.self,
      salt: ephemeralPubBytes,
      sharedInfo: domainSeparator,
      outputByteCount: 32
    )

    let innerBuffer: Data
    do {
      let sealedBox = try ChaChaPoly.SealedBox(combined: nonceBytes + ciphertextWithTag)
      innerBuffer = try ChaChaPoly.open(sealedBox, using: symmetricKey, authenticating: aad)
    } catch {
      throw CryptoError.authenticationFailed
    }

    return try decodeFramedPayload(innerBuffer)
  }

  /// Decrypts an unpadded Base64URL sealed box ciphertext and decodes UTF-8 string.
  public static func decryptToString(
    _ sealedBoxBase64: String,
    recipientPrivateKey: Curve25519.KeyAgreement.PrivateKey
  ) throws -> String {
    let decryptedBytes = try decrypt(sealedBoxBase64, recipientPrivateKey: recipientPrivateKey)
    guard let string = String(data: decryptedBytes, encoding: .utf8) else {
      throw CryptoError.invalidStringData
    }
    return string
  }

  /// Decrypts an unpadded Base64URL sealed box ciphertext using 32-byte raw recipient private key.
  public static func decrypt(
    _ sealedBoxBase64: String,
    recipientPrivateKeyData: Data
  ) throws -> Data {
    guard recipientPrivateKeyData.count == keySizeBytes else {
      throw CryptoError.invalidKeySize(
        expected: keySizeBytes, actual: recipientPrivateKeyData.count)
    }
    let privKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: recipientPrivateKeyData)
    return try decrypt(sealedBoxBase64, recipientPrivateKey: privKey)
  }

  /// Decrypts an unpadded Base64URL sealed box ciphertext to String using 32-byte raw recipient private key.
  public static func decryptToString(
    _ sealedBoxBase64: String,
    recipientPrivateKeyData: Data
  ) throws -> String {
    guard recipientPrivateKeyData.count == keySizeBytes else {
      throw CryptoError.invalidKeySize(
        expected: keySizeBytes, actual: recipientPrivateKeyData.count)
    }
    let privKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: recipientPrivateKeyData)
    return try decryptToString(sealedBoxBase64, recipientPrivateKey: privKey)
  }

  // MARK: - Candidate Scanning & Decryption

  /// Scans input text for Base64URL ciphertext candidates (>= 166 chars),
  /// attempts sealed box decryption for each, and replaces successful candidates in-place.
  ///
  /// - Parameters:
  ///   - inputText: Raw text potentially containing ciphertext substrings.
  ///   - recipientPrivateKey: Recipient X25519 private key.
  /// - Returns: DecryptResult indicating .noCandidates, .authFailed, or .success(text, secrets, ranges).
  public static func decryptTextWithCandidates(
    _ inputText: String,
    recipientPrivateKey: Curve25519.KeyAgreement.PrivateKey
  ) -> DecryptResult {
    let nsString = inputText as NSString
    let fullRange = NSRange(location: 0, length: nsString.length)
    let matches = candidateRegex.matches(in: inputText, options: [], range: fullRange)

    if matches.isEmpty {
      return .noCandidates
    }

    var secrets: [String] = []
    var successfulMatches: [(range: Range<String.Index>, decrypted: String)] = []

    for match in matches {
      guard let stringRange = Range(match.range, in: inputText) else { continue }
      let candidate = String(inputText[stringRange])

      // Fast rejection on byte 0 if decoded token byte 0 != 0x02 before performing X25519
      guard let payload = base64UrlDecode(candidate),
        payload.count >= fixedOverheadBytes + 2,
        payload[0] == protocolVersion
      else {
        continue
      }

      do {
        let decrypted = try decryptToString(candidate, recipientPrivateKey: recipientPrivateKey)
        successfulMatches.append((range: stringRange, decrypted: decrypted))
        secrets.append(decrypted)
      } catch {
        // Ciphertext candidate failed decryption; leave unchanged
      }
    }

    if successfulMatches.isEmpty {
      return .authFailed
    }

    var updatedText = ""
    var integerRanges: [Range<Int>] = []
    var currentIndex = inputText.startIndex
    for item in successfulMatches {
      let prefix = inputText[currentIndex..<item.range.lowerBound]
      updatedText.append(contentsOf: prefix)
      let startOffset = updatedText.count
      updatedText.append(item.decrypted)
      let endOffset = updatedText.count
      integerRanges.append(startOffset..<endOffset)
      currentIndex = item.range.upperBound
    }
    updatedText.append(contentsOf: inputText[currentIndex..<inputText.endIndex])

    var stringRanges: [Range<String.Index>] = []
    for intRange in integerRanges {
      let lower = updatedText.index(updatedText.startIndex, offsetBy: intRange.lowerBound)
      let upper = updatedText.index(updatedText.startIndex, offsetBy: intRange.upperBound)
      stringRanges.append(lower..<upper)
    }

    return .success(text: updatedText, secrets: secrets, ranges: stringRanges)
  }

  /// Scans input text for Base64URL ciphertext candidates using 32-byte raw recipient private key.
  public static func decryptTextWithCandidates(
    _ inputText: String,
    recipientPrivateKeyData: Data
  ) -> DecryptResult {
    guard
      let privKey = try? Curve25519.KeyAgreement.PrivateKey(
        rawRepresentation: recipientPrivateKeyData)
    else {
      return .authFailed
    }
    return decryptTextWithCandidates(inputText, recipientPrivateKey: privKey)
  }
}
