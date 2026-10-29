//
//  ContactRecord.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import Foundation

/// A contact record representing a known peer's public key and metadata.
public struct ContactRecord: Codable, Equatable, Identifiable, Sendable {
  public let id: String
  public let name: String
  public let publicKeyBase64Url: String
  public let createdAt: Int64

  public init(
    id: String = UUID().uuidString,
    name: String,
    publicKeyBase64Url: String,
    createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
  ) {
    self.id = id
    self.name = name
    self.publicKeyBase64Url = publicKeyBase64Url
    self.createdAt = createdAt
  }

  /// Serializes a list of contact records to a JSON string.
  public static func serialize(_ contacts: [ContactRecord]) throws -> String {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    let data = try encoder.encode(contacts)
    guard let json = String(data: data, encoding: .utf8) else {
      throw DecodingError.dataCorrupted(
        DecodingError.Context(
          codingPath: [], debugDescription: "Failed to encode contacts as UTF-8 string")
      )
    }
    return json
  }

  /// Deserializes a JSON string into a list of contact records.
  public static func deserialize(_ json: String) throws -> [ContactRecord] {
    guard let data = json.data(using: .utf8) else {
      throw DecodingError.dataCorrupted(
        DecodingError.Context(codingPath: [], debugDescription: "Invalid UTF-8 string")
      )
    }
    let decoder = JSONDecoder()
    return try decoder.decode([ContactRecord].self, from: data)
  }
}
