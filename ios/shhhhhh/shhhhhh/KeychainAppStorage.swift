//
//  KeychainAppStorage.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation

/// Errors that can occur during persistent storage operations.
public enum AppStorageError: LocalizedError, Equatable {
  case keychainSaveFailed
  case invalidKeyData

  public var errorDescription: String? {
    switch self {
    case .keychainSaveFailed:
      return "Failed to save private key to Keychain"
    case .invalidKeyData:
      return "Invalid cryptographic key data"
    }
  }
}

/// Production `AppStorage` implementation backed by iOS Keychain for sensitive key material
/// and `UserDefaults` for public identity metadata, preferences, and contacts.
public final class KeychainAppStorage: AppStorage, @unchecked Sendable {
  public static let shared = KeychainAppStorage()

  public static let accountPrivateKey = "user_private_key"
  public static let keyPublicKey = "public_key"
  public static let keyOnboardingCompleted = "onboarding_completed"
  public static let keyContacts = "contacts_json"
  public static let keyTheme = "theme_preference"

  private let keychain: KeychainHelper
  private let userDefaults: UserDefaults
  private let contactsLock = NSLock()

  public init(
    keychain: KeychainHelper = .standard,
    userDefaults: UserDefaults = .standard
  ) {
    self.keychain = keychain
    self.userDefaults = userDefaults
  }

  // MARK: - KeyStorage Implementation

  public func saveKeyPair(_ keyPair: CryptoKeyPair) throws {
    let rawPrivate = keyPair.privateKey.rawRepresentation
    guard rawPrivate.count == CryptoEngine.keySizeBytes else {
      throw AppStorageError.invalidKeyData
    }

    let saved = keychain.save(data: rawPrivate, forAccount: Self.accountPrivateKey)
    guard saved else {
      throw AppStorageError.keychainSaveFailed
    }

    userDefaults.set(keyPair.publicKeyBase64Url, forKey: Self.keyPublicKey)
  }

  public func loadKeyPair() -> CryptoKeyPair? {
    guard let rawPrivate = keychain.read(forAccount: Self.accountPrivateKey) else {
      return nil
    }
    guard rawPrivate.count == CryptoEngine.keySizeBytes else {
      return nil
    }

    do {
      let keyPair = try CryptoKeyPair(rawRepresentation: rawPrivate)
      return keyPair
    } catch {
      return nil
    }
  }

  public func clearKeyPair() {
    keychain.delete(forAccount: Self.accountPrivateKey)
    userDefaults.removeObject(forKey: Self.keyPublicKey)
  }

  public func hasCompletedOnboarding() -> Bool {
    userDefaults.bool(forKey: Self.keyOnboardingCompleted)
  }

  public func setCompletedOnboarding(_ completed: Bool) {
    userDefaults.set(completed, forKey: Self.keyOnboardingCompleted)
  }

  // MARK: - ContactStorage Implementation

  public func getContacts() -> [ContactRecord] {
    contactsLock.lock()
    defer { contactsLock.unlock() }

    guard let json = userDefaults.string(forKey: Self.keyContacts), !json.isEmpty else {
      return []
    }
    do {
      return try ContactRecord.deserialize(json).sorted { $0.createdAt < $1.createdAt }
    } catch {
      return []
    }
  }

  public func saveContact(_ contact: ContactRecord) {
    contactsLock.lock()
    defer { contactsLock.unlock() }

    var contacts: [ContactRecord] = []
    if let json = userDefaults.string(forKey: Self.keyContacts), !json.isEmpty {
      contacts = (try? ContactRecord.deserialize(json)) ?? []
    }

    if let existingIndex = contacts.firstIndex(where: { $0.id == contact.id }) {
      contacts[existingIndex] = contact
    } else {
      contacts.append(contact)
    }

    if let encodedJson = try? ContactRecord.serialize(contacts) {
      userDefaults.set(encodedJson, forKey: Self.keyContacts)
    }
  }

  public func deleteContact(id: String) {
    contactsLock.lock()
    defer { contactsLock.unlock() }

    guard let json = userDefaults.string(forKey: Self.keyContacts), !json.isEmpty else {
      return
    }

    var contacts = (try? ContactRecord.deserialize(json)) ?? []
    let originalCount = contacts.count
    contacts.removeAll { $0.id == id }

    if contacts.count != originalCount {
      if let encodedJson = try? ContactRecord.serialize(contacts) {
        userDefaults.set(encodedJson, forKey: Self.keyContacts)
      }
    }
  }

  // MARK: - AppStorage Unified Preferences

  public func getTheme() -> String? {
    userDefaults.string(forKey: Self.keyTheme)
  }

  public func setTheme(_ theme: String) {
    userDefaults.set(theme, forKey: Self.keyTheme)
  }

  public func clearAll() {
    contactsLock.lock()
    defer { contactsLock.unlock() }

    clearKeyPair()
    userDefaults.removeObject(forKey: Self.keyOnboardingCompleted)
    userDefaults.removeObject(forKey: Self.keyContacts)
    userDefaults.removeObject(forKey: Self.keyTheme)
  }
}
