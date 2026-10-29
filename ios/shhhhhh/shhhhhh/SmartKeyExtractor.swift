//
//  SmartKeyExtractor.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation

/// Utility for extracting and validating 43-character Base64URL Curve25519 public keys from input text.
public enum SmartKeyExtractor {
  /// Regular expression matching a 43-character Base64URL string bounded by non-Base64URL characters or boundaries.
  public static let keyRegex = try! NSRegularExpression(
    pattern: #"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])"#
  )

  /// Scans the given input text for candidate 43-character Base64URL keys, returning the first
  /// candidate that decodes to a valid 32-byte Curve25519 key agreement public key.
  ///
  /// Ignores surrounding preamble, conversational text, and postscript.
  public static func extractKey(from input: String) -> String? {
    let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }

    let nsString = input as NSString
    let matches = keyRegex.matches(
      in: input, options: [], range: NSRange(location: 0, length: nsString.length))

    for match in matches {
      let candidate = nsString.substring(with: match.range)
      if isValidPublicKey(candidate) {
        return candidate
      }
    }
    return nil
  }

  /// Verifies whether the string is an unpadded 43-character Base64URL string
  /// that decodes to a valid 32-byte Curve25519 public key.
  public static func isValidPublicKey(_ candidate: String) -> Bool {
    guard candidate.count == 43 else { return false }
    guard let data = CryptoEngine.base64UrlDecode(candidate),
      data.count == CryptoEngine.keySizeBytes
    else {
      return false
    }
    do {
      _ = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: data)
      return true
    } catch {
      return false
    }
  }
}
