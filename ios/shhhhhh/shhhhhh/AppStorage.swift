//
//  AppStorage.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation

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
public protocol AppStorage: KeyStorage, ContactStorage {
    func getTheme() -> String?
    func setTheme(_ theme: String)
    func clearAll()
}
