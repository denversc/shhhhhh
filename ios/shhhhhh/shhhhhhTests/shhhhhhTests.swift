//
//  shhhhhhTests.swift
//  shhhhhhTests
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation
import ShhhhhhCore
import SwiftUI
import Testing
import UIKit

@testable import shhhhhh

// swift-format-ignore: TypeNamesShouldBeCapitalized
struct shhhhhhTests {

  // MARK: - SecretSpan and Attributed String Tests

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

  // MARK: - SwiftUI View and Clipboard Tests

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
}
