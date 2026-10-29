//
//  KeychainHelper.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import Foundation
import Security

/// Thread-safe wrapper around iOS Keychain Services for storing sensitive data.
public final class KeychainHelper: @unchecked Sendable {
  public static let standard = KeychainHelper()

  public let service: String
  public let accessGroup: String?
  public let accessibility: CFString

  public init(
    service: String = "prefix924.shhhhhh",
    accessGroup: String? = nil,
    accessibility: CFString = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
  ) {
    self.service = service
    self.accessGroup = accessGroup
    self.accessibility = accessibility
  }

  /// Saves a Data item for the specified account name.
  /// If an item already exists for this account, it updates it.
  @discardableResult
  public func save(data: Data, forAccount account: String) -> Bool {
    var query = baseQuery(forAccount: account)
    query[kSecValueData as String] = data
    query[kSecAttrAccessible as String] = accessibility

    let status = SecItemAdd(query as CFDictionary, nil)
    if status == errSecSuccess {
      return true
    } else if status == errSecDuplicateItem {
      return update(data: data, forAccount: account)
    }
    return false
  }

  /// Reads a Data item for the specified account name.
  public func read(forAccount account: String) -> Data? {
    var query = baseQuery(forAccount: account)
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne

    var result: AnyObject?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else {
      return nil
    }
    return data
  }

  /// Updates an existing Data item for the specified account name.
  @discardableResult
  public func update(data: Data, forAccount account: String) -> Bool {
    let query = baseQuery(forAccount: account)
    let attributesToUpdate: [String: Any] = [
      kSecValueData as String: data
    ]
    let status = SecItemUpdate(query as CFDictionary, attributesToUpdate as CFDictionary)
    return status == errSecSuccess
  }

  /// Deletes a Data item for the specified account name.
  @discardableResult
  public func delete(forAccount account: String) -> Bool {
    let query = baseQuery(forAccount: account)
    let status = SecItemDelete(query as CFDictionary)
    return status == errSecSuccess || status == errSecItemNotFound
  }

  /// Deletes all items associated with this service.
  @discardableResult
  public func deleteAll() -> Bool {
    var query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
    ]
    if let accessGroup = accessGroup {
      query[kSecAttrAccessGroup as String] = accessGroup
    }
    let status = SecItemDelete(query as CFDictionary)
    return status == errSecSuccess || status == errSecItemNotFound
  }

  private func baseQuery(forAccount account: String) -> [String: Any] {
    var query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
    ]
    if let accessGroup = accessGroup {
      query[kSecAttrAccessGroup as String] = accessGroup
    }
    return query
  }
}
