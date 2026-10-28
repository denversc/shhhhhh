//
//  shhhhhhTests.swift
//  shhhhhhTests
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation
import Testing
@testable import shhhhhh

struct shhhhhhTests {

    // MARK: - EntropyAccumulator Tests

    @Test func testEntropyAccumulatorInitialState() async throws {
        let accumulator = EntropyAccumulator()
        #expect(accumulator.getSampleCount() == 0)
    }

    @Test func testEntropyAccumulatorAddSample() async throws {
        let accumulator = EntropyAccumulator()
        accumulator.addSample(x: 10.5, y: 20.25, dx: 1.0, dy: 2.0, timestampNanos: 123456789)
        #expect(accumulator.getSampleCount() == 1)

        accumulator.addSample(x: 30.0, y: 40.0, dx: 0.0, dy: 0.0, timestampNanos: 987654321)
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
        let derivedPub = try CryptoEngine.derivePublicKey(fromPrivateKey: keyPair.privateKey.rawRepresentation)

        #expect(derivedPub == keyPair.publicKey.rawRepresentation)

        let recreatedPair = try CryptoEngine.createKeyPair(fromPrivateKey: keyPair.privateKey.rawRepresentation)
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
        let expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

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
            String(repeating: "A", count: 10_000)
        ]

        for message in messages {
            // Alice encrypts for Bob
            let ciphertext = try CryptoEngine.encrypt(message, recipientPublicKey: bob.publicKey)
            #expect(ciphertext.count >= 80)

            let range = NSRange(location: 0, length: ciphertext.utf16.count)
            #expect(CryptoEngine.candidateRegex.firstMatch(in: ciphertext, options: [], range: range) != nil)

            // Bob decrypts with Bob's private key
            let decrypted = try CryptoEngine.decryptToString(ciphertext, recipientPrivateKey: bob.privateKey)
            #expect(decrypted == message)
        }
    }

    @Test func testEncryptionAndDecryptionOfEmptyStringProduces80Chars() throws {
        let alice = CryptoEngine.generateKeyPair()

        let emptyCiphertext = try CryptoEngine.encrypt("", recipientPublicKey: alice.publicKey)
        #expect(emptyCiphertext.count == 80)

        let range = NSRange(location: 0, length: emptyCiphertext.utf16.count)
        #expect(CryptoEngine.candidateRegex.firstMatch(in: emptyCiphertext, options: [], range: range) != nil)

        let decrypted = try CryptoEngine.decryptToString(emptyCiphertext, recipientPrivateKey: alice.privateKey)
        #expect(decrypted == "")

        let decryptedBytes = try CryptoEngine.decrypt(emptyCiphertext, recipientPrivateKey: alice.privateKey)
        #expect(decryptedBytes.isEmpty)
    }

    @Test func testDecryptionWithWrongPrivateKeyThrowsAuthenticationFailed() throws {
        let alice = CryptoEngine.generateKeyPair()
        let eve = CryptoEngine.generateKeyPair()

        let ciphertext = try CryptoEngine.encrypt("Confidential Payload", recipientPublicKey: alice.publicKey)

        #expect(throws: CryptoError.authenticationFailed) {
            try CryptoEngine.decrypt(ciphertext, recipientPrivateKey: eve.privateKey)
        }
    }

    @Test func testTamperedCiphertextFailsAuthentication() throws {
        let alice = CryptoEngine.generateKeyPair()
        let ciphertext = try CryptoEngine.encrypt("Sensitive Document", recipientPublicKey: alice.publicKey)
        guard var rawPayload = CryptoEngine.base64UrlDecode(ciphertext) else {
            Issue.record("Failed to decode ciphertext")
            return
        }

        // Test tampering at various positions: ephemeral key, nonce, ciphertext body, MAC tag
        let testIndices = [0, 15, 31, 32, 40, 43, 44, rawPayload.count / 2, rawPayload.count - 1]

        for idx in testIndices {
            var tampered = rawPayload
            tampered[idx] ^= 0x01
            let tamperedBase64 = CryptoEngine.base64UrlEncode(tampered)

            #expect(throws: Error.self) {
                try CryptoEngine.decrypt(tamperedBase64, recipientPrivateKey: alice.privateKey)
            }
        }
    }

    @Test func testTruncatedOrInvalidPayloadThrows() throws {
        let alice = CryptoEngine.generateKeyPair()

        // Less than 60 bytes
        let shortBytes = Data(repeating: 0x42, count: 59)
        let shortBase64 = CryptoEngine.base64UrlEncode(shortBytes)

        #expect(throws: CryptoError.self) {
            try CryptoEngine.decrypt(shortBase64, recipientPrivateKey: alice.privateKey)
        }

        // Invalid base64
        #expect(throws: CryptoError.self) {
            try CryptoEngine.decrypt("invalid-base64-symbols-!#%*&^~@", recipientPrivateKey: alice.privateKey)
        }
    }

    // MARK: - In-Place Candidate Scanning and Decryption

    @Test func testCandidateScanningNoCandidates() throws {
        let alice = CryptoEngine.generateKeyPair()

        #expect(CryptoEngine.decryptTextWithCandidates("", recipientPrivateKey: alice.privateKey) == .noCandidates)
        #expect(CryptoEngine.decryptTextWithCandidates("Hello, this is just normal text.", recipientPrivateKey: alice.privateKey) == .noCandidates)

        // String with 79 characters (one less than threshold)
        let shortToken = String(repeating: "A", count: 79)
        #expect(CryptoEngine.decryptTextWithCandidates("Token: \(shortToken)", recipientPrivateKey: alice.privateKey) == .noCandidates)
    }

    @Test func testCandidateScanningAuthFailed() throws {
        let alice = CryptoEngine.generateKeyPair()
        let bob = CryptoEngine.generateKeyPair()

        // Encrypted for Bob, but decrypted with Alice's key
        let bobCiphertext = try CryptoEngine.encrypt("Bob's secret", recipientPublicKey: bob.publicKey)
        let inputText = "Hey Alice, here is Bob's message: \(bobCiphertext) please read it."

        let result = CryptoEngine.decryptTextWithCandidates(inputText, recipientPrivateKey: alice.privateKey)
        #expect(result == .authFailed)
    }

    @Test func testCandidateScanningSingleCandidateInPlace() throws {
        let alice = CryptoEngine.generateKeyPair()
        let secret = "BankPassword123!"
        let ciphertext = try CryptoEngine.encrypt(secret, recipientPublicKey: alice.publicKey)

        let inputText = "Here is the bank password: \(ciphertext) let me know when you are done!"
        let result = CryptoEngine.decryptTextWithCandidates(inputText, recipientPrivateKey: alice.privateKey)

        #expect(result == .success(
            text: "Here is the bank password: \(secret) let me know when you are done!",
            secrets: [secret]
        ))
    }

    @Test func testCandidateScanningMultipleCandidatesInPlace() throws {
        let alice = CryptoEngine.generateKeyPair()
        let secret1 = "WifiPassword456"
        let secret2 = "VaultPIN789"

        let ct1 = try CryptoEngine.encrypt(secret1, recipientPublicKey: alice.publicKey)
        let ct2 = try CryptoEngine.encrypt(secret2, recipientPublicKey: alice.publicKey)

        let inputText = "Hello! Wifi is \(ct1) and vault PIN is \(ct2). Enjoy!"
        let result = CryptoEngine.decryptTextWithCandidates(inputText, recipientPrivateKey: alice.privateKey)

        #expect(result == .success(
            text: "Hello! Wifi is \(secret1) and vault PIN is \(secret2). Enjoy!",
            secrets: [secret1, secret2]
        ))
    }

    @Test func testCandidateScanningMixedValidAndInvalidCandidates() throws {
        let alice = CryptoEngine.generateKeyPair()
        let bob = CryptoEngine.generateKeyPair()

        let aliceSecret = "SecretForAlice"
        let bobSecret = "SecretForBob"

        let aliceCt = try CryptoEngine.encrypt(aliceSecret, recipientPublicKey: alice.publicKey)
        let bobCt = try CryptoEngine.encrypt(bobSecret, recipientPublicKey: bob.publicKey)

        let inputText = "Part 1: \(aliceCt) and Part 2: \(bobCt)"
        let result = CryptoEngine.decryptTextWithCandidates(inputText, recipientPrivateKey: alice.privateKey)

        #expect(result == .success(
            text: "Part 1: \(aliceSecret) and Part 2: \(bobCt)",
            secrets: [aliceSecret]
        ))
    }

    // MARK: - Cross-Platform Android Test Vector

    @Test func testCrossPlatformAndroidTestVector() throws {
        let alicePrivHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
        let expectedAlicePubBase64 = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9_AsrhtHHw"
        let androidCiphertext = "HYlYuDVFH0uDir-USFnAmTOJzR3O0r6PR_rZQtXWzDSxicCpJoy1_sVhjKTcVhKUkvVr72eRH_q3OlBOJGX5KUYbPKALUXq8D6SE-Q"
        let expectedPlaintext = "BankPassword123!"

        let alicePrivData = hexToData(alicePrivHex)
        let aliceKeyPair = try CryptoEngine.createKeyPair(fromPrivateKey: alicePrivData)

        #expect(aliceKeyPair.publicKeyBase64Url == expectedAlicePubBase64)

        // Direct decryption
        let decrypted = try CryptoEngine.decryptToString(androidCiphertext, recipientPrivateKey: aliceKeyPair.privateKey)
        #expect(decrypted == expectedPlaintext)

        // Raw bytes decryption
        let decryptedBytes = try CryptoEngine.decrypt(androidCiphertext, recipientPrivateKeyData: alicePrivData)
        #expect(String(data: decryptedBytes, encoding: .utf8) == expectedPlaintext)

        // In-place multi-match scanner with Android ciphertext
        let conversationalInput = "Received from Android: \(androidCiphertext) -- check it out!"
        let scanResult = CryptoEngine.decryptTextWithCandidates(conversationalInput, recipientPrivateKey: aliceKeyPair.privateKey)

        #expect(scanResult == .success(
            text: "Received from Android: \(expectedPlaintext) -- check it out!",
            secrets: [expectedPlaintext]
        ))
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
