//
//  KeyContactStorage.swift
//  ShhhhhhCore
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import Foundation

#if canImport(Darwin)
  import CryptoKit
#else
  import Crypto
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
