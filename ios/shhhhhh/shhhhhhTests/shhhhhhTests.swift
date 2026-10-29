//
//  shhhhhhTests.swift
//  shhhhhhTests
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation
import SwiftUI
import Testing
import UIKit

@testable import shhhhhh

// swift-format-ignore: TypeNamesShouldBeCapitalized
struct shhhhhhTests {

  // MARK: - EntropyAccumulator Tests

  @Test func testEntropyAccumulatorInitialState() async throws {
    let accumulator = EntropyAccumulator()
    #expect(accumulator.getSampleCount() == 0)
  }

  @Test func testEntropyAccumulatorAddSample() async throws {
    let accumulator = EntropyAccumulator()
    accumulator.addSample(x: 10.5, y: 20.25, dx: 1.0, dy: 2.0, timestampNanos: 123_456_789)
    #expect(accumulator.getSampleCount() == 1)

    accumulator.addSample(x: 30.0, y: 40.0, dx: 0.0, dy: 0.0, timestampNanos: 987_654_321)
    #expect(accumulator.getSampleCount() == 2)
  }

  @Test func testEntropyAccumulatorDeterministicSeedWithOverride() async throws {
    let accumulator = EntropyAccumulator()
    accumulator.addSample(x: 1.0, y: 2.0, dx: 0.5, dy: 0.5, timestampNanos: 1000)

    let mockSystemEntropy = [UInt8](repeating: 0x42, count: 32)
    let seed = accumulator.generateCombinedSeed(systemEntropyOverride: mockSystemEntropy)
    #expect(seed.count == 32)

    // Generating again with the same accumulator will produce the same seed for identical override
    let seed2 = accumulator.generateCombinedSeed(systemEntropyOverride: mockSystemEntropy)
    #expect(seed == seed2)
  }

  @Test func testEntropyAccumulatorDefaultCSPRNGGeneratesUniqueSeeds() async throws {
    let accumulator1 = EntropyAccumulator()
    accumulator1.addSample(x: 5.0, y: 10.0, dx: 0.0, dy: 0.0, timestampNanos: 500)
    let seed1 = accumulator1.generateCombinedSeed()

    let accumulator2 = EntropyAccumulator()
    accumulator2.addSample(x: 5.0, y: 10.0, dx: 0.0, dy: 0.0, timestampNanos: 500)
    let seed2 = accumulator2.generateCombinedSeed()

    #expect(seed1.count == 32)
    #expect(seed2.count == 32)
    #expect(seed1 != seed2)
  }

  // MARK: - CryptoEngine Key Pair Tests

  @Test func testCryptoKeyPairGenerationAndBase64UrlRoundTrip() throws {
    let keyPair = CryptoEngine.generateKeyPair()

    #expect(keyPair.privateKey.rawRepresentation.count == 32)
    #expect(keyPair.publicKey.rawRepresentation.count == 32)

    let pubBase64 = keyPair.publicKeyBase64Url
    #expect(pubBase64.count == 43)

    let range = NSRange(location: 0, length: pubBase64.utf16.count)
    let regex = try NSRegularExpression(pattern: "^[A-Za-z0-9_-]{43}$")
    #expect(regex.firstMatch(in: pubBase64, options: [], range: range) != nil)

    // Base64URL round trip
    let decodedPub = try CryptoEngine.decodePublicKeyBase64Url(pubBase64)
    #expect(decodedPub == keyPair.publicKey.rawRepresentation)

    let encodedPub = try CryptoEngine.encodePublicKeyBase64Url(decodedPub)
    #expect(encodedPub == pubBase64)
  }

  @Test func testDerivePublicKeyAndCreateKeyPairFromPrivateKey() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    let derivedPub = try CryptoEngine.derivePublicKey(
      fromPrivateKey: keyPair.privateKey.rawRepresentation)

    #expect(derivedPub == keyPair.publicKey.rawRepresentation)

    let recreatedPair = try CryptoEngine.createKeyPair(
      fromPrivateKey: keyPair.privateKey.rawRepresentation)
    #expect(recreatedPair.publicKey.rawRepresentation == keyPair.publicKey.rawRepresentation)
    #expect(recreatedPair.privateKey.rawRepresentation == keyPair.privateKey.rawRepresentation)
    #expect(recreatedPair == keyPair)
  }

  @Test func testSubsequentKeyGenerationProducesDistinctKeys() throws {
    let kp1 = CryptoEngine.generateKeyPair()
    let kp2 = CryptoEngine.generateKeyPair()

    #expect(kp1.privateKey.rawRepresentation != kp2.privateKey.rawRepresentation)
    #expect(kp1.publicKey.rawRepresentation != kp2.publicKey.rawRepresentation)
    #expect(kp1 != kp2)
  }

  // MARK: - HKDF-SHA256 RFC 5869 Test Vector

  @Test func testHkdfSha256MatchesRfc5869TestCase1() throws {
    let ikm = hexToData("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    let salt = hexToData("000102030405060708090a0b0c")
    let info = hexToData("f0f1f2f3f4f5f6f7f8f9")
    let expectedPrk = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
    let expectedOkm =
      "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

    let prk = CryptoEngine.hkdfExtract(salt: salt, ikm: ikm)
    #expect(dataToHex(prk) == expectedPrk)

    let okm = try CryptoEngine.hkdfExpand(prk: prk, info: info, length: 42)
    #expect(dataToHex(okm) == expectedOkm)

    let directOkm = try CryptoEngine.hkdfSha256(ikm: ikm, salt: salt, info: info, length: 42)
    #expect(dataToHex(directOkm) == expectedOkm)
  }

  // MARK: - Encryption and Decryption Round Trip

  @Test func testEncryptionAndDecryptionRoundTrip() throws {
    let alice = CryptoEngine.generateKeyPair()
    let bob = CryptoEngine.generateKeyPair()

    let messages = [
      "BankPassword123!",
      "Hello, world! 🔐 ChaCha20-Poly1305 + X25519",
      "Multi-line\nsecret\r\nmessage with tabs\tand symbols: !@#$%^&*()_+~`",
      String(repeating: "A", count: 10_000),
    ]

    for message in messages {
      // Alice encrypts for Bob
      let ciphertext = try CryptoEngine.encrypt(message, recipientPublicKey: bob.publicKey)
      #expect(ciphertext.count >= 252)

      let range = NSRange(location: 0, length: ciphertext.utf16.count)
      #expect(
        CryptoEngine.candidateRegex.firstMatch(in: ciphertext, options: [], range: range) != nil)

      // Bob decrypts with Bob's private key
      let decrypted = try CryptoEngine.decryptToString(
        ciphertext, recipientPrivateKey: bob.privateKey)
      #expect(decrypted == message)
    }
  }

  @Test func testEncryptionAndDecryptionOfEmptyStringProduces252Chars() throws {
    let alice = CryptoEngine.generateKeyPair()

    let emptyCiphertext = try CryptoEngine.encrypt("", recipientPublicKey: alice.publicKey)
    #expect(emptyCiphertext.count == 252)

    let rawPayload = try #require(CryptoEngine.base64UrlDecode(emptyCiphertext))
    #expect(rawPayload.count == 189)
    #expect(rawPayload[0] == 0x02)

    let range = NSRange(location: 0, length: emptyCiphertext.utf16.count)
    #expect(
      CryptoEngine.candidateRegex.firstMatch(in: emptyCiphertext, options: [], range: range) != nil)

    let decrypted = try CryptoEngine.decryptToString(
      emptyCiphertext, recipientPrivateKey: alice.privateKey)
    #expect(decrypted == "")

    let decryptedBytes = try CryptoEngine.decrypt(
      emptyCiphertext, recipientPrivateKey: alice.privateKey)
    #expect(decryptedBytes.isEmpty)
  }

  @Test func testAllSecretsUpTo126BytesProduceExactly252Chars() throws {
    let alice = CryptoEngine.generateKeyPair()

    let lengthsToTest = [0, 1, 2, 4, 15, 16, 31, 32, 57, 64, 100, 125, 126]
    for len in lengthsToTest {
      let secret = String(repeating: "K", count: len)
      let ciphertext = try CryptoEngine.encrypt(secret, recipientPublicKey: alice.publicKey)

      #expect(ciphertext.count == 252, "Failed for length \(len): got \(ciphertext.count) chars")

      guard let rawPayload = CryptoEngine.base64UrlDecode(ciphertext) else {
        Issue.record("Failed to Base64URL decode ciphertext for length \(len)")
        continue
      }
      #expect(rawPayload.count == 189)
      #expect(rawPayload[0] == 0x02)

      let decrypted = try CryptoEngine.decryptToString(
        ciphertext, recipientPrivateKey: alice.privateKey)
      #expect(decrypted == secret)
    }
  }

  @Test func testPadmeBucketingLengthsAbove126Bytes() throws {
    let alice = CryptoEngine.generateKeyPair()

    // Test padLengthPadme directly
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 0) == 128)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 128) == 128)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 129) == 144)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 144) == 144)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 145) == 160)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 160) == 160)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 161) == 176)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 200) == 208)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 256) == 256)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 257) == 272)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 500) == 512)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 1000) == 1024)
    #expect(CryptoEngine.padLengthPadme(unpaddedLength: 4000) == 4096)

    // Test encryption of payloads exceeding 126 bytes
    let testCases: [(plaintextLen: Int, expectedTargetSize: Int)] = [
      (127, 144),  // unpadded 129 -> 144
      (150, 160),  // unpadded 152 -> 160
      (200, 208),  // unpadded 202 -> 208
      (254, 256),  // unpadded 256 -> 256
      (500, 512),  // unpadded 502 -> 512
      (1000, 1024),  // unpadded 1002 -> 1024
    ]

    for tc in testCases {
      let secret = String(repeating: "Z", count: tc.plaintextLen)
      let ciphertext = try CryptoEngine.encrypt(secret, recipientPublicKey: alice.publicKey)

      guard let rawPayload = CryptoEngine.base64UrlDecode(ciphertext) else {
        Issue.record("Failed to decode ciphertext of length \(tc.plaintextLen)")
        continue
      }

      let expectedWireBytes = tc.expectedTargetSize + CryptoEngine.fixedOverheadBytes
      #expect(rawPayload.count == expectedWireBytes)
      #expect(rawPayload[0] == 0x02)

      let decrypted = try CryptoEngine.decryptToString(
        ciphertext, recipientPrivateKey: alice.privateKey)
      #expect(decrypted == secret)
    }
  }

  @Test func testDecryptionWithWrongPrivateKeyThrowsAuthenticationFailed() throws {
    let alice = CryptoEngine.generateKeyPair()
    let eve = CryptoEngine.generateKeyPair()

    let ciphertext = try CryptoEngine.encrypt(
      "Confidential Payload", recipientPublicKey: alice.publicKey)

    #expect(throws: CryptoError.authenticationFailed) {
      try CryptoEngine.decrypt(ciphertext, recipientPrivateKey: eve.privateKey)
    }
  }

  @Test func testTamperedCiphertextFailsAuthentication() throws {
    let alice = CryptoEngine.generateKeyPair()
    let ciphertext = try CryptoEngine.encrypt(
      "Sensitive Document", recipientPublicKey: alice.publicKey)
    guard var rawPayload = CryptoEngine.base64UrlDecode(ciphertext) else {
      Issue.record("Failed to decode ciphertext")
      return
    }

    // Test tampering at various positions: version byte, ephemeral key, nonce, ciphertext body, MAC tag
    let testIndices = [0, 1, 15, 32, 33, 40, 44, 45, rawPayload.count / 2, rawPayload.count - 1]

    for idx in testIndices {
      var tampered = rawPayload
      tampered[idx] ^= 0x01
      let tamperedBase64 = CryptoEngine.base64UrlEncode(tampered)

      #expect(throws: Error.self) {
        try CryptoEngine.decrypt(tamperedBase64, recipientPrivateKey: alice.privateKey)
      }
    }
  }

  @Test func testFastRejectionOfInvalidProtocolVersion() throws {
    let alice = CryptoEngine.generateKeyPair()
    let ciphertext = try CryptoEngine.encrypt(
      "Top Secret Message", recipientPublicKey: alice.publicKey)
    guard var rawPayload = CryptoEngine.base64UrlDecode(ciphertext) else {
      Issue.record("Failed to decode ciphertext")
      return
    }

    let invalidVersions: [UInt8] = [0x00, 0x01, 0x03, 0x20, 0xFF]
    for badVersion in invalidVersions {
      rawPayload[0] = badVersion
      let badBase64 = CryptoEngine.base64UrlEncode(rawPayload)

      // Direct decrypt throws authenticationFailed immediately
      #expect(throws: CryptoError.authenticationFailed) {
        try CryptoEngine.decrypt(badBase64, recipientPrivateKey: alice.privateKey)
      }

      // Candidate scanner discards token on byte 0 and returns authFailed
      let conversational = "Look at this token: \(badBase64) from peer"
      let result = CryptoEngine.decryptTextWithCandidates(
        conversational, recipientPrivateKey: alice.privateKey)
      #expect(result == .authFailed)
    }
  }

  @Test func testTruncatedOrInvalidPayloadThrows() throws {
    let alice = CryptoEngine.generateKeyPair()

    // Less than 63 bytes (fixed overhead 61 + 2-byte length prefix)
    let shortBytes = Data(repeating: 0x42, count: 62)
    let shortBase64 = CryptoEngine.base64UrlEncode(shortBytes)

    #expect(throws: CryptoError.self) {
      try CryptoEngine.decrypt(shortBase64, recipientPrivateKey: alice.privateKey)
    }

    // Invalid base64
    #expect(throws: CryptoError.self) {
      try CryptoEngine.decrypt(
        "invalid-base64-symbols-!#%*&^~@", recipientPrivateKey: alice.privateKey)
    }
  }

  // MARK: - In-Place Candidate Scanning and Decryption

  @Test func testCandidateScanningNoCandidates() throws {
    let alice = CryptoEngine.generateKeyPair()

    #expect(
      CryptoEngine.decryptTextWithCandidates("", recipientPrivateKey: alice.privateKey)
        == .noCandidates)
    #expect(
      CryptoEngine.decryptTextWithCandidates(
        "Hello, this is just normal text.", recipientPrivateKey: alice.privateKey) == .noCandidates)

    // String with 165 characters (one less than threshold 166)
    let shortToken = String(repeating: "A", count: 165)
    #expect(
      CryptoEngine.decryptTextWithCandidates(
        "Token: \(shortToken)", recipientPrivateKey: alice.privateKey) == .noCandidates)
  }

  @Test func testCandidateScanningAuthFailed() throws {
    let alice = CryptoEngine.generateKeyPair()
    let bob = CryptoEngine.generateKeyPair()

    // Encrypted for Bob, but decrypted with Alice's key
    let bobCiphertext = try CryptoEngine.encrypt("Bob's secret", recipientPublicKey: bob.publicKey)
    let inputText = "Hey Alice, here is Bob's message: \(bobCiphertext) please read it."

    let result = CryptoEngine.decryptTextWithCandidates(
      inputText, recipientPrivateKey: alice.privateKey)
    #expect(result == .authFailed)
  }

  @Test func testCandidateScanningSingleCandidateInPlace() throws {
    let alice = CryptoEngine.generateKeyPair()
    let secret = "BankPassword123!"
    let ciphertext = try CryptoEngine.encrypt(secret, recipientPublicKey: alice.publicKey)

    let inputText = "Here is the bank password: \(ciphertext) let me know when you are done!"
    let result = CryptoEngine.decryptTextWithCandidates(
      inputText, recipientPrivateKey: alice.privateKey)

    #expect(
      result
        == .success(
          text: "Here is the bank password: \(secret) let me know when you are done!",
          secrets: [secret]
        ))

    guard case .success(let text, let secrets, let ranges) = result else {
      Issue.record("Expected .success")
      return
    }
    #expect(secrets.count == 1)
    #expect(ranges.count == 1)
    #expect(String(text[ranges[0]]) == secret)
  }

  @Test func testCandidateScanningMultipleCandidatesInPlace() throws {
    let alice = CryptoEngine.generateKeyPair()
    let secret1 = "WifiPassword456"
    let secret2 = "VaultPIN789"

    let ct1 = try CryptoEngine.encrypt(secret1, recipientPublicKey: alice.publicKey)
    let ct2 = try CryptoEngine.encrypt(secret2, recipientPublicKey: alice.publicKey)

    let inputText = "Hello! Wifi is \(ct1) and vault PIN is \(ct2). Enjoy!"
    let result = CryptoEngine.decryptTextWithCandidates(
      inputText, recipientPrivateKey: alice.privateKey)

    #expect(
      result
        == .success(
          text: "Hello! Wifi is \(secret1) and vault PIN is \(secret2). Enjoy!",
          secrets: [secret1, secret2]
        ))

    guard case .success(let text, let secrets, let ranges) = result else {
      Issue.record("Expected .success")
      return
    }
    #expect(secrets.count == 2)
    #expect(ranges.count == 2)
    #expect(String(text[ranges[0]]) == secret1)
    #expect(String(text[ranges[1]]) == secret2)
  }

  @Test func testCandidateScanningMixedValidAndInvalidCandidates() throws {
    let alice = CryptoEngine.generateKeyPair()
    let bob = CryptoEngine.generateKeyPair()

    let aliceSecret = "SecretForAlice"
    let bobSecret = "SecretForBob"

    let aliceCt = try CryptoEngine.encrypt(aliceSecret, recipientPublicKey: alice.publicKey)
    let bobCt = try CryptoEngine.encrypt(bobSecret, recipientPublicKey: bob.publicKey)

    let inputText = "Part 1: \(aliceCt) and Part 2: \(bobCt)"
    let result = CryptoEngine.decryptTextWithCandidates(
      inputText, recipientPrivateKey: alice.privateKey)

    #expect(
      result
        == .success(
          text: "Part 1: \(aliceSecret) and Part 2: \(bobCt)",
          secrets: [aliceSecret]
        ))
  }

  // MARK: - Cross-Platform Deterministic Test Vector

  @Test func testCrossPlatformDeterministicV2TestVector() throws {
    let alicePrivHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    let expectedAlicePubBase64 = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9_AsrhtHHw"
    let ephemeralPrivHex = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    let nonceHex = "404142434445464748494a4b"
    let expectedPlaintext = "BankPassword123!"

    let alicePrivData = hexToData(alicePrivHex)
    let aliceKeyPair = try CryptoEngine.createKeyPair(fromPrivateKey: alicePrivData)
    #expect(aliceKeyPair.publicKeyBase64Url == expectedAlicePubBase64)

    let ephemeralPrivData = hexToData(ephemeralPrivHex)
    let ephemeralPriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: ephemeralPrivData)
    let nonce = try ChaChaPoly.Nonce(data: hexToData(nonceHex))
    let fixedPadding = Data(repeating: 0x55, count: 110)

    // Encrypt deterministically
    let deterministicCiphertext = try CryptoEngine.encrypt(
      Data(expectedPlaintext.utf8),
      recipientPublicKey: aliceKeyPair.publicKey,
      minFloor: 128,
      ephemeralPrivateKeyOverride: ephemeralPriv,
      nonceOverride: nonce,
      paddingBytesOverride: fixedPadding
    )

    #expect(deterministicCiphertext.count == 252)

    // Direct decryption
    let decrypted = try CryptoEngine.decryptToString(
      deterministicCiphertext, recipientPrivateKey: aliceKeyPair.privateKey)
    #expect(decrypted == expectedPlaintext)

    // Raw bytes decryption
    let decryptedBytes = try CryptoEngine.decrypt(
      deterministicCiphertext, recipientPrivateKeyData: alicePrivData)
    #expect(String(data: decryptedBytes, encoding: .utf8) == expectedPlaintext)

    // Candidate scanner in conversational text
    let conversationalInput = "Received secret: \(deterministicCiphertext) -- please verify!"
    let scanResult = CryptoEngine.decryptTextWithCandidates(
      conversationalInput, recipientPrivateKey: aliceKeyPair.privateKey)

    #expect(
      scanResult
        == .success(
          text: "Received secret: \(expectedPlaintext) -- please verify!",
          secrets: [expectedPlaintext]
        ))
  }

  // MARK: - Key Generation from Entropy Seed

  @Test func testGeneratingKeyPairFromAccumulatedEntropySeed() throws {
    let acc = EntropyAccumulator()
    acc.addSample(x: 45.0, y: 90.0, dx: 2.0, dy: 3.0, timestampNanos: 123_456_789)
    let seed = acc.generateCombinedSeed()
    #expect(seed.count == 32)

    let keyPair = try CryptoKeyPair(rawRepresentation: Data(seed))
    #expect(keyPair.privateKey.rawRepresentation.count == 32)
    #expect(keyPair.publicKey.rawRepresentation.count == 32)
    #expect(keyPair.publicKeyBase64Url.count == 43)

    // Able to encrypt and decrypt
    let ciphertext = try CryptoEngine.encrypt(
      "hello entropy", recipientPublicKey: keyPair.publicKey)
    let decrypted = try CryptoEngine.decryptToString(
      ciphertext, recipientPrivateKey: keyPair.privateKey)
    #expect(decrypted == "hello entropy")
  }

  // MARK: - Restore Key Pair from Backup Tests

  @Test func testRestoreKeyPairFromBackup43CharBase64Url() throws {
    let originalKeyPair = CryptoEngine.generateKeyPair()
    let privateKeyBase64Url = originalKeyPair.privateKeyBase64Url
    #expect(privateKeyBase64Url.count == 43)

    let restored = try restoreKeyPairFromBackup(privateKeyBase64Url)
    #expect(restored.privateKey.rawRepresentation == originalKeyPair.privateKey.rawRepresentation)
    #expect(restored.publicKey.rawRepresentation == originalKeyPair.publicKey.rawRepresentation)
    #expect(restored == originalKeyPair)
  }

  @Test func testRestoreKeyPairFromBackupDeterministicForArbitraryText() throws {
    let text1 = "correct horse battery staple"
    let text2 = "shhhhhh paper backup test 2026"

    let kp1a = try restoreKeyPairFromBackup(text1)
    let kp1b = try restoreKeyPairFromBackup(text1)
    let kp2 = try restoreKeyPairFromBackup(text2)

    // Deterministic: same text yields same key pair
    #expect(kp1a == kp1b)
    #expect(kp1a.privateKey.rawRepresentation.count == 32)
    #expect(kp1a.publicKey.rawRepresentation.count == 32)

    // Different text yields different key pair
    #expect(kp1a != kp2)

    // Functioning key pair: can encrypt and decrypt
    let secret = "Secret message for restored key"
    let ciphertext = try CryptoEngine.encrypt(secret, recipientPublicKey: kp1a.publicKey)
    let decrypted = try CryptoEngine.decryptToString(
      ciphertext, recipientPrivateKey: kp1a.privateKey)
    #expect(decrypted == secret)
  }

  @Test func testRestoreKeyPairFromBackupThrowsOnBlankInput() throws {
    #expect(throws: RestoreBackupError.blankInput) {
      try restoreKeyPairFromBackup("")
    }
    #expect(throws: RestoreBackupError.blankInput) {
      try restoreKeyPairFromBackup("   \n\t  ")
    }
  }

  @Test func testRestoreKeyPairFromBackupPaddedBase64AndHex() throws {
    let originalKeyPair = CryptoEngine.generateKeyPair()
    let privData = originalKeyPair.privateKey.rawRepresentation

    // 44-char padded Base64
    let paddedBase64 = privData.base64EncodedString()
    #expect(paddedBase64.count == 44)
    let restoredFromBase64 = try restoreKeyPairFromBackup(paddedBase64)
    #expect(restoredFromBase64 == originalKeyPair)

    // 64-char hex
    let hexString = dataToHex(privData)
    #expect(hexString.count == 64)
    let restoredFromHex = try restoreKeyPairFromBackup(hexString)
    #expect(restoredFromHex == originalKeyPair)
  }

  // MARK: - SecretSpan and CopySecretAction Tests

  @Test func testResolveCopySecretActionEmptySingleAndMulti() throws {
    #expect(resolveCopySecretAction([]) == .none)
    #expect(resolveCopySecretAction(["SoloSecret"]) == .copySingle("SoloSecret"))
    #expect(
      resolveCopySecretAction(["SecretOne", "SecretTwo"])
        == .showPicker(["SecretOne", "SecretTwo"]))
    #expect(resolveCopySecretAction(["A", "B", "C"]) == .showPicker(["A", "B", "C"]))
  }

  @Test func testFindSecretSpansWithExplicitRanges() throws {
    let text = "Here is secret one: Alpha and secret two: Beta -- done!"
    let secrets = ["Alpha", "Beta"]

    let range1 = text.range(of: "Alpha")!
    let range2 = text.range(of: "Beta")!

    let spans = findSecretSpans(text: text, secrets: secrets, explicitRanges: [range1, range2])
    #expect(spans.count == 2)
    #expect(spans[0].secret == "Alpha")
    #expect(spans[0].range == range1)
    #expect(String(text[spans[0].range]) == "Alpha")

    #expect(spans[1].secret == "Beta")
    #expect(spans[1].range == range2)
    #expect(String(text[spans[1].range]) == "Beta")
  }

  @Test func testFindSecretSpansFallbackOnInvalidExplicitRanges() throws {
    let text = "First is Foo and second is Bar"
    let secrets = ["Foo", "Bar"]

    // Give a bogus range for the first secret
    let bogusRange = text.startIndex..<text.index(text.startIndex, offsetBy: 3)  // "Fir" != "Foo"
    let range2 = text.range(of: "Bar")!

    // Should fall back to sequential scanning and find both correctly
    let spans = findSecretSpans(text: text, secrets: secrets, explicitRanges: [bogusRange, range2])
    #expect(spans.count == 2)
    #expect(spans[0].secret == "Foo")
    #expect(String(text[spans[0].range]) == "Foo")
    #expect(spans[1].secret == "Bar")
    #expect(String(text[spans[1].range]) == "Bar")
  }

  @Test func testFindSecretSpansWithoutExplicitRanges() throws {
    let text = "Pass: Secret123 and PIN: 4567"
    let secrets = ["Secret123", "4567"]

    let spans = findSecretSpans(text: text, secrets: secrets)
    #expect(spans.count == 2)
    #expect(spans[0].secret == "Secret123")
    #expect(String(text[spans[0].range]) == "Secret123")
    #expect(spans[1].secret == "4567")
    #expect(String(text[spans[1].range]) == "4567")
  }

  @Test func testFindSecretSpansEmptyInputs() throws {
    #expect(findSecretSpans(text: "", secrets: ["A"]).isEmpty)
    #expect(findSecretSpans(text: "Some text", secrets: []).isEmpty)
  }

  @Test func testBuildSecretAttributedStringAppliesAttributes() throws {
    let text = "Hidden secret in plain text"
    let secret = "secret"
    let range = text.range(of: secret)!
    let span = SecretSpan(secret: secret, range: range, in: text)

    let attr = buildSecretAttributedString(text: text, spans: [span])
    #expect(attr.string == text)

    var effectiveRange = NSRange(location: 0, length: 0)
    let bg = attr.attribute(
      .backgroundColor, at: span.nsRange.location, effectiveRange: &effectiveRange)
    #expect(bg != nil)
  }

  // MARK: - ContactRecord & Serialization Tests

  @Test func testContactRecordInitializationAndDefaults() throws {
    let name = "Alice"
    let pubKey = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
    let record = ContactRecord(name: name, publicKeyBase64Url: pubKey)

    #expect(!record.id.isEmpty)
    #expect(record.name == name)
    #expect(record.publicKeyBase64Url == pubKey)
    #expect(record.createdAt > 0)
  }

  @Test func testContactRecordSerializationAndDeserializationSpecialCharacters() throws {
    let contacts = [
      ContactRecord(
        id: "id-1",
        name: "Alice 🔐 \"The Cryptographer\" \\ & ,",
        publicKeyBase64Url: "pubKey1234567890123456789012345678901234567",
        createdAt: 1000
      ),
      ContactRecord(
        id: "id-2",
        name: "Bob 🚀 <Party> \n Newline\tTab",
        publicKeyBase64Url: "pubKeySecond12345678901234567890123456789012",
        createdAt: 2000
      ),
    ]

    let json = try ContactRecord.serialize(contacts)
    #expect(json.contains("Alice 🔐"))
    #expect(json.contains("Bob 🚀"))

    let deserialized = try ContactRecord.deserialize(json)
    #expect(deserialized.count == 2)
    #expect(deserialized[0] == contacts[0])
    #expect(deserialized[1] == contacts[1])
  }

  @Test func testContactRecordDeserializationCorruptedDataThrows() throws {
    let corruptedJson = "{ not valid json }"
    #expect(throws: DecodingError.self) {
      try ContactRecord.deserialize(corruptedJson)
    }

    #expect(try ContactRecord.deserialize("[]").isEmpty)
  }

  // MARK: - InMemoryAppStorage Tests

  @Test func testInMemoryAppStorageKeySavingLoadingClearing() throws {
    let storage = InMemoryAppStorage()
    #expect(storage.loadKeyPair() == nil)

    let keyPair = CryptoEngine.generateKeyPair()
    try storage.saveKeyPair(keyPair)

    let loaded = storage.loadKeyPair()
    #expect(loaded != nil)
    #expect(loaded?.publicKey.rawRepresentation == keyPair.publicKey.rawRepresentation)
    #expect(loaded?.privateKey.rawRepresentation == keyPair.privateKey.rawRepresentation)

    storage.clearKeyPair()
    #expect(storage.loadKeyPair() == nil)
  }

  @Test func testInMemoryAppStorageOnboardingTransitions() {
    let storage = InMemoryAppStorage()
    #expect(!storage.hasCompletedOnboarding())

    storage.setCompletedOnboarding(true)
    #expect(storage.hasCompletedOnboarding())

    storage.setCompletedOnboarding(false)
    #expect(!storage.hasCompletedOnboarding())
  }

  @Test func testInMemoryAppStorageContactCRUD() {
    let storage = InMemoryAppStorage()
    #expect(storage.getContacts().isEmpty)

    let c1 = ContactRecord(id: "1", name: "Alice", publicKeyBase64Url: "key1", createdAt: 200)
    let c2 = ContactRecord(id: "2", name: "Bob", publicKeyBase64Url: "key2", createdAt: 100)

    storage.saveContact(c1)
    storage.saveContact(c2)

    // getContacts returns sorted by createdAt ascending
    let contacts = storage.getContacts()
    #expect(contacts.count == 2)
    #expect(contacts[0].id == "2")  // createdAt 100
    #expect(contacts[1].id == "1")  // createdAt 200

    // Update contact 1
    let updatedC1 = ContactRecord(
      id: "1", name: "Alice Updated", publicKeyBase64Url: "key1_updated", createdAt: 200)
    storage.saveContact(updatedC1)
    let contactsAfterUpdate = storage.getContacts()
    #expect(contactsAfterUpdate.count == 2)
    #expect(contactsAfterUpdate[1].name == "Alice Updated")
    #expect(contactsAfterUpdate[1].publicKeyBase64Url == "key1_updated")

    // Delete contact 2
    storage.deleteContact(id: "2")
    let contactsAfterDelete = storage.getContacts()
    #expect(contactsAfterDelete.count == 1)
    #expect(contactsAfterDelete[0].id == "1")

    // Delete nonexistent contact does not crash
    storage.deleteContact(id: "999")
    #expect(storage.getContacts().count == 1)
  }

  @Test func testInMemoryAppStorageThemeAndClearAll() throws {
    let storage = InMemoryAppStorage()
    #expect(storage.getTheme() == nil)

    storage.setTheme("dark")
    #expect(storage.getTheme() == "dark")

    let keyPair = CryptoEngine.generateKeyPair()
    try storage.saveKeyPair(keyPair)
    storage.setCompletedOnboarding(true)
    storage.saveContact(
      ContactRecord(id: "c1", name: "Charlie", publicKeyBase64Url: "k", createdAt: 10))

    storage.clearAll()
    #expect(storage.loadKeyPair() == nil)
    #expect(!storage.hasCompletedOnboarding())
    #expect(storage.getContacts().isEmpty)
    #expect(storage.getTheme() == nil)
  }

  // MARK: - KeychainHelper Tests

  @Test func testKeychainHelperCrud() {
    let testService = "prefix924.shhhhhh.test.\(UUID().uuidString)"
    let keychain = KeychainHelper(service: testService)
    defer { keychain.deleteAll() }

    let account = "test_account"
    let data = "SensitiveData123".data(using: .utf8)!

    #expect(keychain.read(forAccount: account) == nil)

    // Save
    let saved = keychain.save(data: data, forAccount: account)
    #expect(saved)
    #expect(keychain.read(forAccount: account) == data)

    // Update (upsert)
    let updatedData = "NewSensitiveData456".data(using: .utf8)!
    let updated = keychain.save(data: updatedData, forAccount: account)
    #expect(updated)
    #expect(keychain.read(forAccount: account) == updatedData)

    // Delete
    let deleted = keychain.delete(forAccount: account)
    #expect(deleted)
    #expect(keychain.read(forAccount: account) == nil)

    // Deleting already deleted item returns true
    #expect(keychain.delete(forAccount: account))
  }

  // MARK: - KeychainAppStorage Tests

  @Test func testKeychainAppStorageKeySavingLoadingClearing() throws {
    let testService = "prefix924.shhhhhh.test.\(UUID().uuidString)"
    let keychain = KeychainHelper(service: testService)
    let suiteName = "test.suite.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    defer {
      keychain.deleteAll()
      defaults.removePersistentDomain(forName: suiteName)
    }

    let storage = KeychainAppStorage(keychain: keychain, userDefaults: defaults)
    #expect(storage.loadKeyPair() == nil)

    let keyPair = CryptoEngine.generateKeyPair()
    try storage.saveKeyPair(keyPair)

    let loaded = storage.loadKeyPair()
    #expect(loaded != nil)
    #expect(loaded?.publicKey.rawRepresentation == keyPair.publicKey.rawRepresentation)
    #expect(loaded?.privateKey.rawRepresentation == keyPair.privateKey.rawRepresentation)

    storage.clearKeyPair()
    #expect(storage.loadKeyPair() == nil)
  }

  @Test func testKeychainAppStorageOnboardingAndThemeTransitions() {
    let testService = "prefix924.shhhhhh.test.\(UUID().uuidString)"
    let keychain = KeychainHelper(service: testService)
    let suiteName = "test.suite.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    defer {
      keychain.deleteAll()
      defaults.removePersistentDomain(forName: suiteName)
    }

    let storage = KeychainAppStorage(keychain: keychain, userDefaults: defaults)
    #expect(!storage.hasCompletedOnboarding())
    storage.setCompletedOnboarding(true)
    #expect(storage.hasCompletedOnboarding())

    #expect(storage.getTheme() == nil)
    storage.setTheme("light")
    #expect(storage.getTheme() == "light")
  }

  @Test func testKeychainAppStorageContactCRUD() {
    let testService = "prefix924.shhhhhh.test.\(UUID().uuidString)"
    let keychain = KeychainHelper(service: testService)
    let suiteName = "test.suite.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    defer {
      keychain.deleteAll()
      defaults.removePersistentDomain(forName: suiteName)
    }

    let storage = KeychainAppStorage(keychain: keychain, userDefaults: defaults)
    #expect(storage.getContacts().isEmpty)

    let c1 = ContactRecord(id: "c1", name: "Alice", publicKeyBase64Url: "key1", createdAt: 500)
    let c2 = ContactRecord(id: "c2", name: "Bob", publicKeyBase64Url: "key2", createdAt: 100)

    storage.saveContact(c1)
    storage.saveContact(c2)

    let retrieved = storage.getContacts()
    #expect(retrieved.count == 2)
    #expect(retrieved[0].id == "c2")  // createdAt 100
    #expect(retrieved[1].id == "c1")  // createdAt 500

    // Update
    let updatedC2 = ContactRecord(
      id: "c2", name: "Bob New", publicKeyBase64Url: "key2_new", createdAt: 100)
    storage.saveContact(updatedC2)
    let retrievedUpdated = storage.getContacts()
    #expect(retrievedUpdated.count == 2)
    #expect(retrievedUpdated[0].name == "Bob New")

    // Delete
    storage.deleteContact(id: "c1")
    let afterDelete = storage.getContacts()
    #expect(afterDelete.count == 1)
    #expect(afterDelete[0].id == "c2")

    storage.clearAll()
    #expect(storage.getContacts().isEmpty)
  }

  @Test func testCorruptedDataRecovery() throws {
    let testService = "prefix924.shhhhhh.test.\(UUID().uuidString)"
    let keychain = KeychainHelper(service: testService)
    let suiteName = "test.suite.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    defer {
      keychain.deleteAll()
      defaults.removePersistentDomain(forName: suiteName)
    }

    let storage = KeychainAppStorage(keychain: keychain, userDefaults: defaults)

    // 1. Corrupted private key in keychain (invalid length: 16 bytes instead of 32)
    keychain.save(
      data: Data(repeating: 0xaa, count: 16), forAccount: KeychainAppStorage.accountPrivateKey)
    defaults.set("dummy_pub_key", forKey: KeychainAppStorage.keyPublicKey)
    #expect(storage.loadKeyPair() == nil)

    // 2. Corrupted contacts JSON in UserDefaults
    defaults.set("{ corrupted JSON !@#$ }", forKey: KeychainAppStorage.keyContacts)
    #expect(storage.getContacts().isEmpty)

    // 3. Saving a valid contact recovers from corrupted JSON
    let validContact = ContactRecord(
      id: "recov", name: "Recovered", publicKeyBase64Url: "pk123", createdAt: 1)
    storage.saveContact(validContact)
    let recovered = storage.getContacts()
    #expect(recovered.count == 1)
    #expect(recovered[0].name == "Recovered")
  }

  @Test func testContentViewInitializationFromStorage() throws {
    let emptyStorage = InMemoryAppStorage()
    _ = ContentView(storage: emptyStorage)

    let readyStorage = InMemoryAppStorage()
    let kp = CryptoEngine.generateKeyPair()
    try readyStorage.saveKeyPair(kp)
    readyStorage.setCompletedOnboarding(true)

    _ = ContentView(storage: readyStorage)
    #expect(readyStorage.hasCompletedOnboarding())
    #expect(readyStorage.loadKeyPair() != nil)
  }

  // MARK: - Copy Public Key & Contact Sheet Tests

  @Test func testPublicKeyBase64UrlFormat() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    let pubKeyString = keyPair.publicKeyBase64Url

    // Must be exactly 43 characters (32 raw bytes Base64URL without padding)
    #expect(pubKeyString.count == 43)

    // Must strictly use unpadded Base64URL characters [A-Za-z0-9_-]
    let regex = try Regex("^[A-Za-z0-9_-]{43}$")
    #expect(pubKeyString.contains(regex))

    // Must not contain padding or standard base64 characters
    #expect(!pubKeyString.contains("="))
    #expect(!pubKeyString.contains("+"))
    #expect(!pubKeyString.contains("/"))

    // Must decode to valid 32-byte raw public key
    let decoded = try #require(CryptoEngine.base64UrlDecode(pubKeyString))
    #expect(decoded.count == 32)
    #expect(decoded == keyPair.publicKey.rawRepresentation)
  }

  @MainActor
  @Test func testCopyPublicKeyCopiesRaw43Characters() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    UIPasteboard.general.string = "pre-existing-clipboard-text"

    let copied = copyPublicKeyToClipboard(keyPair)
    #expect(copied)

    let pasted = UIPasteboard.general.string
    #expect(pasted == keyPair.publicKeyBase64Url)
    #expect(pasted?.count == 43)

    // Ensure no preamble, newlines, or template wrappers were copied
    #expect(pasted == CryptoEngine.base64UrlEncode(keyPair.publicKey.rawRepresentation))
  }

  @MainActor
  @Test func testCopyPublicKeyWithNilKeyPair() throws {
    UIPasteboard.general.string = "sentinel-clipboard-value"
    let copied = copyPublicKeyToClipboard(nil)
    #expect(!copied)
    #expect(UIPasteboard.general.string == "sentinel-clipboard-value")
  }

  @Test func testMultipleKeyPairsProduceDistinctValid43CharPublicKeys() throws {
    var observedKeys = Set<String>()
    for _ in 0..<10 {
      let kp = CryptoEngine.generateKeyPair()
      let pubKey = kp.publicKeyBase64Url
      #expect(pubKey.count == 43)
      #expect(!observedKeys.contains(pubKey))
      observedKeys.insert(pubKey)

      let decoded = try #require(CryptoEngine.base64UrlDecode(pubKey))
      #expect(decoded.count == 32)
      #expect(decoded == kp.publicKey.rawRepresentation)
    }
    #expect(observedKeys.count == 10)
  }

  @MainActor
  @Test func testContactSelectionSheetInitialization() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    var bannerText: String? = nil
    let bannerBinding = Binding<String?>(
      get: { bannerText },
      set: { bannerText = $0 }
    )

    var copyCalled = false
    var dismissCalled = false

    let sheet = ContactSelectionSheet(
      userKeyPair: keyPair,
      selectedContact: "Myself (This Device)",
      bannerText: bannerBinding,
      onCopyPublicKey: { copyCalled = true },
      onDismiss: { dismissCalled = true }
    )

    #expect(sheet.userKeyPair == keyPair)
    #expect(sheet.selectedContact == "Myself (This Device)")

    sheet.onCopyPublicKey()
    #expect(copyCalled)

    sheet.onDismiss()
    #expect(dismissCalled)
  }

  // MARK: - SmartKeyExtractor Tests

  @Test func testSmartKeyExtractorDirectValidKey() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    let rawKey = keyPair.publicKeyBase64Url
    #expect(rawKey.count == 43)

    let extracted = SmartKeyExtractor.extractKey(from: rawKey)
    #expect(extracted == rawKey)
    #expect(SmartKeyExtractor.isValidPublicKey(rawKey))
  }

  @Test func testSmartKeyExtractorConversationalPreambleAndPostscript() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    let rawKey = keyPair.publicKeyBase64Url

    let conversational =
      "Hey Alice! Here's my public key: \(rawKey). Save it so we can chat securely!"
    let extracted = SmartKeyExtractor.extractKey(from: conversational)
    #expect(extracted == rawKey)
  }

  @Test func testSmartKeyExtractorWithNewlinesAndPunctuation() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    let rawKey = keyPair.publicKeyBase64Url

    let input = "Key:\n\t[\(rawKey)]\nThanks!"
    let extracted = SmartKeyExtractor.extractKey(from: input)
    #expect(extracted == rawKey)
  }

  @Test func testSmartKeyExtractorRejectsInvalidLengths() throws {
    let keyPair = CryptoEngine.generateKeyPair()
    let rawKey = keyPair.publicKeyBase64Url

    // Too short (42 chars)
    let shortKey = String(rawKey.prefix(42))
    #expect(SmartKeyExtractor.extractKey(from: shortKey) == nil)
    #expect(!SmartKeyExtractor.isValidPublicKey(shortKey))

    // Too long (44 chars - appending a character)
    let longKey = rawKey + "A"
    #expect(SmartKeyExtractor.extractKey(from: longKey) == nil)
    #expect(!SmartKeyExtractor.isValidPublicKey(longKey))

    // Even longer token (50 chars)
    let fiftyChars = rawKey + "1234567"
    #expect(SmartKeyExtractor.extractKey(from: fiftyChars) == nil)
    #expect(!SmartKeyExtractor.isValidPublicKey(fiftyChars))
  }

  @Test func testSmartKeyExtractorRejectsCiphertext() throws {
    let alice = CryptoEngine.generateKeyPair()
    let ciphertext = try CryptoEngine.encrypt("Secret message", recipientPublicKey: alice.publicKey)
    #expect(ciphertext.count >= 166)

    // Ciphertext should NOT be extracted as a public key
    #expect(SmartKeyExtractor.extractKey(from: ciphertext) == nil)
    #expect(!SmartKeyExtractor.isValidPublicKey(ciphertext))
  }

  @Test func testSmartKeyExtractorRejectsInvalidBase64Chars() throws {
    // 43 chars but contains invalid chars (e.g. '@', '$', '!')
    let invalidKey = "3kL9@xK89mZ2Q1pA0BvL81kLz0P82kL9xK89mZ2Q!#$"
    #expect(SmartKeyExtractor.extractKey(from: invalidKey) == nil)
    #expect(!SmartKeyExtractor.isValidPublicKey(invalidKey))
  }

  @Test func testSmartKeyExtractorEmptyAndWhitespace() throws {
    #expect(SmartKeyExtractor.extractKey(from: "") == nil)
    #expect(SmartKeyExtractor.extractKey(from: "   \n\t   ") == nil)
  }

  // MARK: - Contact Management & Recipient Encryption Tests

  @Test func testContactStorageAndRetrieval() throws {
    let storage = InMemoryAppStorage()
    let bobKey = CryptoEngine.generateKeyPair()

    let contact = ContactRecord(
      name: "Bob",
      publicKeyBase64Url: bobKey.publicKeyBase64Url
    )
    storage.saveContact(contact)

    let loaded = storage.getContacts()
    #expect(loaded.count == 1)
    #expect(loaded[0].name == "Bob")
    #expect(loaded[0].publicKeyBase64Url == bobKey.publicKeyBase64Url)
  }

  @Test func testEncryptMessageToContactDecryptedByContactPrivateKey() throws {
    let bobKey = CryptoEngine.generateKeyPair()
    let bobContact = ContactRecord(name: "Bob", publicKeyBase64Url: bobKey.publicKeyBase64Url)

    // Sender converts contact public key and encrypts
    let rawPub = try CryptoEngine.decodePublicKeyBase64Url(bobContact.publicKeyBase64Url)
    let bobPubKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: rawPub)

    let secret = "TopSecretPlan123"
    let ciphertext = try CryptoEngine.encrypt(secret, recipientPublicKey: bobPubKey)

    // Bob decrypts with his private key
    let decrypted = try CryptoEngine.decryptToString(
      ciphertext, recipientPrivateKey: bobKey.privateKey)
    #expect(decrypted == secret)

    // Sender's own key CANNOT decrypt it (intended for Bob)
    let aliceKey = CryptoEngine.generateKeyPair()
    #expect(throws: CryptoError.authenticationFailed) {
      _ = try CryptoEngine.decryptToString(ciphertext, recipientPrivateKey: aliceKey.privateKey)
    }
  }

  @Test func testEncryptToMyselfDecryptedByOwnPrivateKey() throws {
    let myKey = CryptoEngine.generateKeyPair()
    let secret = "PersonalNote456"

    let ciphertext = try CryptoEngine.encrypt(secret, recipientPublicKey: myKey.publicKey)
    let decrypted = try CryptoEngine.decryptToString(
      ciphertext, recipientPrivateKey: myKey.privateKey)
    #expect(decrypted == secret)
  }

  // MARK: - Text Insertion and Paste Logic Tests

  @Test func testApplyTextInsertionUnfocusedAppendsToEnd() throws {
    // Empty text
    let r1 = applyTextInsertion(
      currentText: "",
      insertedText: "Hello",
      selectedRange: NSRange(location: 0, length: 0),
      isFocused: false
    )
    #expect(r1.newText == "Hello")
    #expect(r1.newRange == NSRange(location: 5, length: 0))

    // Non-empty text with non-zero selectedRange (should ignore selectedRange when unfocused)
    let r2 = applyTextInsertion(
      currentText: "Prefix ",
      insertedText: "Pasted",
      selectedRange: NSRange(location: 0, length: 2),
      isFocused: false
    )
    #expect(r2.newText == "Prefix Pasted")
    #expect(r2.newRange == NSRange(location: 13, length: 0))
  }

  @Test func testApplyTextInsertionFocusedCollapsedCursor() throws {
    // Cursor at beginning (0, 0)
    let r1 = applyTextInsertion(
      currentText: "World",
      insertedText: "Hello ",
      selectedRange: NSRange(location: 0, length: 0),
      isFocused: true
    )
    #expect(r1.newText == "Hello World")
    #expect(r1.newRange == NSRange(location: 6, length: 0))

    // Cursor in middle
    let r2 = applyTextInsertion(
      currentText: "Hello World",
      insertedText: "beautiful ",
      selectedRange: NSRange(location: 6, length: 0),
      isFocused: true
    )
    #expect(r2.newText == "Hello beautiful World")
    #expect(r2.newRange == NSRange(location: 16, length: 0))

    // Cursor at end
    let r3 = applyTextInsertion(
      currentText: "Hello World",
      insertedText: "!",
      selectedRange: NSRange(location: 11, length: 0),
      isFocused: true
    )
    #expect(r3.newText == "Hello World!")
    #expect(r3.newRange == NSRange(location: 12, length: 0))
  }

  @Test func testApplyTextInsertionFocusedSelectionReplacement() throws {
    // Replace middle range
    let r1 = applyTextInsertion(
      currentText: "Hello terrible World",
      insertedText: "beautiful",
      selectedRange: NSRange(location: 6, length: 8),
      isFocused: true
    )
    #expect(r1.newText == "Hello beautiful World")
    #expect(r1.newRange == NSRange(location: 15, length: 0))

    // Replace entire text
    let r2 = applyTextInsertion(
      currentText: "OldContent",
      insertedText: "NewContent",
      selectedRange: NSRange(location: 0, length: 10),
      isFocused: true
    )
    #expect(r2.newText == "NewContent")
    #expect(r2.newRange == NSRange(location: 10, length: 0))
  }

  @Test func testApplyTextInsertionEdgeCases() throws {
    // Out-of-bounds location clamped to end
    let r1 = applyTextInsertion(
      currentText: "Short",
      insertedText: "End",
      selectedRange: NSRange(location: 100, length: 5),
      isFocused: true
    )
    #expect(r1.newText == "ShortEnd")
    #expect(r1.newRange == NSRange(location: 8, length: 0))

    // NSNotFound location falls back to appending
    let r2 = applyTextInsertion(
      currentText: "Safe",
      insertedText: "Mode",
      selectedRange: NSRange(location: NSNotFound, length: 0),
      isFocused: true
    )
    #expect(r2.newText == "SafeMode")
    #expect(r2.newRange == NSRange(location: 8, length: 0))

    // Inserting empty string leaves text unchanged
    let r3 = applyTextInsertion(
      currentText: "KeepMe",
      insertedText: "",
      selectedRange: NSRange(location: 4, length: 0),
      isFocused: true
    )
    #expect(r3.newText == "KeepMe")
    #expect(r3.newRange == NSRange(location: 4, length: 0))
  }

  // MARK: - Test Helpers

  private func hexToData(_ hex: String) -> Data {
    let cleanHex = hex.replacingOccurrences(of: " ", with: "")
    var data = Data()
    var index = cleanHex.startIndex
    while index < cleanHex.endIndex {
      let nextIndex = cleanHex.index(index, offsetBy: 2)
      let byteString = cleanHex[index..<nextIndex]
      if let byte = UInt8(byteString, radix: 16) {
        data.append(byte)
      }
      index = nextIndex
    }
    return data
  }

  private func dataToHex(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
  }
}
