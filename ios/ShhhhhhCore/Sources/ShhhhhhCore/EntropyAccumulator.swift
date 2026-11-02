//
//  EntropyAccumulator.swift
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

public final class EntropyAccumulator: @unchecked Sendable {
  private let lock = NSLock()
  private var hasher = SHA256()
  private var sampleCount: Int = 0

  public init() {}

  public func addSample(
    x: Float,
    y: Float,
    dx: Float,
    dy: Float,
    timestampNanos: UInt64
  ) {
    var data = Data(capacity: 24)
    var xBE = x.bitPattern.bigEndian
    var yBE = y.bitPattern.bigEndian
    var dxBE = dx.bitPattern.bigEndian
    var dyBE = dy.bitPattern.bigEndian
    var timeBE = timestampNanos.bigEndian

    withUnsafeBytes(of: &xBE) { data.append(contentsOf: $0) }
    withUnsafeBytes(of: &yBE) { data.append(contentsOf: $0) }
    withUnsafeBytes(of: &dxBE) { data.append(contentsOf: $0) }
    withUnsafeBytes(of: &dyBE) { data.append(contentsOf: $0) }
    withUnsafeBytes(of: &timeBE) { data.append(contentsOf: $0) }

    lock.lock()
    defer { lock.unlock() }
    hasher.update(data: data)
    sampleCount += 1
  }

  public func getSampleCount() -> Int {
    lock.lock()
    defer { lock.unlock() }
    return sampleCount
  }

  public func generateCombinedSeed(systemEntropyOverride: [UInt8]? = nil) -> [UInt8] {
    lock.lock()
    let userDigest = hasher.finalize()
    lock.unlock()

    var systemEntropy = [UInt8](repeating: 0, count: 32)
    if let override = systemEntropyOverride, override.count == 32 {
      systemEntropy = override
    } else {
      var rng = SystemRandomNumberGenerator()
      systemEntropy = (0..<32).map { _ in UInt8.random(in: .min ... .max, using: &rng) }
    }

    var combiner = SHA256()
    combiner.update(data: Data(userDigest))
    combiner.update(data: Data(systemEntropy))
    let combined = combiner.finalize()
    return Array(combined)
  }
}
