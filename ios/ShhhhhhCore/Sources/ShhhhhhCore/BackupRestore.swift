//
//  BackupRestore.swift
//  ShhhhhhCore
//
//  Created by Denver Coneybeare on 2026-11-02.
//

import Foundation

#if canImport(CryptoKit)
  import CryptoKit
#elseif os(Linux)
  import Crypto
#else
  #error(
    "ShhhhhhCore only supports Apple platforms (CryptoKit) and Linux (swift-crypto). Support for other platforms is technically possible (swift-crypto supports them) but was omitted for lack of need. If needed, ask an AI coding agent to add this platform to the swift-crypto dependency condition in ios/ShhhhhhCore/Package.swift and to every '#elseif os(Linux)' crypto import in ShhhhhhCore."
  )
#endif

// MARK: - Restore from Backup Helper

/// Errors that can occur during backup key restoration.
public enum RestoreBackupError: LocalizedError, Equatable {
  case blankInput

  public var errorDescription: String? {
    switch self {
    case .blankInput:
      return "Backup string cannot be blank"
    }
  }
}

/// Reconstructs a CryptoKeyPair from a backup string.
///
/// If the input is a 43-character Base64URL string (or valid 32-byte key), reconstructs
/// the key pair from that private key scalar. Otherwise, generates a deterministic key pair
/// from the SHA-256 digest of the backup string.
public func restoreKeyPairFromBackup(_ input: String) throws -> CryptoKeyPair {
  let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
  guard !trimmed.isEmpty else {
    throw RestoreBackupError.blankInput
  }

  if trimmed.count == 43 {
    if let data = CryptoEngine.base64UrlDecode(trimmed), data.count == CryptoEngine.keySizeBytes {
      if let kp = try? CryptoKeyPair(rawRepresentation: data) {
        return kp
      }
    }
  } else if trimmed.count == 44 {
    if let data = CryptoEngine.base64UrlDecode(trimmed), data.count == CryptoEngine.keySizeBytes {
      if let kp = try? CryptoKeyPair(rawRepresentation: data) {
        return kp
      }
    }
    if let data = Data(base64Encoded: trimmed), data.count == CryptoEngine.keySizeBytes {
      if let kp = try? CryptoKeyPair(rawRepresentation: data) {
        return kp
      }
    }
  } else if trimmed.count == 64 && trimmed.allSatisfy({ "0123456789abcdefABCDEF".contains($0) }) {
    var data = Data()
    var index = trimmed.startIndex
    while index < trimmed.endIndex {
      let nextIndex = trimmed.index(index, offsetBy: 2)
      if let byte = UInt8(trimmed[index..<nextIndex], radix: 16) {
        data.append(byte)
      }
      index = nextIndex
    }
    if data.count == CryptoEngine.keySizeBytes, let kp = try? CryptoKeyPair(rawRepresentation: data)
    {
      return kp
    }
  }

  let digest = SHA256.hash(data: Data(trimmed.utf8))
  return try! CryptoKeyPair(rawRepresentation: Data(digest))
}
