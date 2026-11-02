//
//  KeyContactStorage.swift
//  ShhhhhhCore
//
//  Created by Denver Coneybeare on 2026-10-27.
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

/// Protocol governing cryptographic key persistence and onboarding state.
public protocol KeyStorage: AnyObject, Sendable {
  func saveKeyPair(_ keyPair: CryptoKeyPair) throws
  func loadKeyPair() -> CryptoKeyPair?
  func clearKeyPair()
  func hasCompletedOnboarding() -> Bool
  func setCompletedOnboarding(_ completed: Bool)
}

/// Protocol governing contact record persistence.
public protocol ContactStorage: AnyObject, Sendable {
  func getContacts() -> [ContactRecord]
  func saveContact(_ contact: ContactRecord)
  func deleteContact(id: String)
}

/// Unified application storage interface for keys, contacts, and preferences.
public protocol KeyContactStorage: KeyStorage, ContactStorage {
  func getTheme() -> String?
  func setTheme(_ theme: String)
  func clearAll()
}
