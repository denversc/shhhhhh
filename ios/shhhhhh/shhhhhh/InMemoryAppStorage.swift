//
//  InMemoryAppStorage.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation

/// In-memory implementation of `AppStorage` designed for unit tests, previews,
/// and isolated execution contexts.
public final class InMemoryAppStorage: AppStorage, @unchecked Sendable {
  private let lock = NSLock()
  private var privateKeyData: Data?
  private var publicKeyBase64Url: String?
  private var onboardingCompleted: Bool = false
  private var contacts: [ContactRecord] = []
  private var themePreference: String? = nil

  public init(
    initialKeyPair: CryptoKeyPair? = nil,
    hasCompletedOnboarding: Bool = false,
    initialContacts: [ContactRecord] = [],
    initialTheme: String? = nil
  ) {
    if let kp = initialKeyPair {
      self.privateKeyData = kp.privateKey.rawRepresentation
      self.publicKeyBase64Url = kp.publicKeyBase64Url
    }
    self.onboardingCompleted = hasCompletedOnboarding
    self.contacts = initialContacts
    self.themePreference = initialTheme
  }

  // MARK: - KeyStorage Implementation

  public func saveKeyPair(_ keyPair: CryptoKeyPair) throws {
    lock.lock()
    defer { lock.unlock() }

    privateKeyData = keyPair.privateKey.rawRepresentation
    publicKeyBase64Url = keyPair.publicKeyBase64Url
  }

  public func loadKeyPair() -> CryptoKeyPair? {
    lock.lock()
    defer { lock.unlock() }

    guard let data = privateKeyData, data.count == CryptoEngine.keySizeBytes else {
      return nil
    }
    return try? CryptoKeyPair(rawRepresentation: data)
  }

  public func clearKeyPair() {
    lock.lock()
    defer { lock.unlock() }

    privateKeyData = nil
    publicKeyBase64Url = nil
  }

  public func hasCompletedOnboarding() -> Bool {
    lock.lock()
    defer { lock.unlock() }

    return onboardingCompleted
  }

  public func setCompletedOnboarding(_ completed: Bool) {
    lock.lock()
    defer { lock.unlock() }

    onboardingCompleted = completed
  }

  // MARK: - ContactStorage Implementation

  public func getContacts() -> [ContactRecord] {
    lock.lock()
    defer { lock.unlock() }

    return contacts.sorted { $0.createdAt < $1.createdAt }
  }

  public func saveContact(_ contact: ContactRecord) {
    lock.lock()
    defer { lock.unlock() }

    if let index = contacts.firstIndex(where: { $0.id == contact.id }) {
      contacts[index] = contact
    } else {
      contacts.append(contact)
    }
  }

  public func deleteContact(id: String) {
    lock.lock()
    defer { lock.unlock() }

    contacts.removeAll { $0.id == id }
  }

  // MARK: - AppStorage Unified Preferences

  public func getTheme() -> String? {
    lock.lock()
    defer { lock.unlock() }

    return themePreference
  }

  public func setTheme(_ theme: String) {
    lock.lock()
    defer { lock.unlock() }

    themePreference = theme
  }

  public func clearAll() {
    lock.lock()
    defer { lock.unlock() }

    privateKeyData = nil
    publicKeyBase64Url = nil
    onboardingCompleted = false
    contacts.removeAll()
    themePreference = nil
  }
}
