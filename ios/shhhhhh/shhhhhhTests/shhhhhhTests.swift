//
//  shhhhhhTests.swift
//  shhhhhhTests
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Foundation
import Testing
@testable import shhhhhh

struct shhhhhhTests {

    @Test func testEntropyAccumulatorInitialState() async throws {
        let accumulator = EntropyAccumulator()
        #expect(accumulator.getSampleCount() == 0)
    }

    @Test func testEntropyAccumulatorAddSample() async throws {
        let accumulator = EntropyAccumulator()
        accumulator.addSample(x: 10.5, y: 20.25, dx: 1.0, dy: 2.0, timestampNanos: 123456789)
        #expect(accumulator.getSampleCount() == 1)

        accumulator.addSample(x: 30.0, y: 40.0, dx: 0.0, dy: 0.0, timestampNanos: 987654321)
        #expect(accumulator.getSampleCount() == 2)
    }

    @Test func testEntropyAccumulatorDeterministicSeedWithOverride() async throws {
        let accumulator = EntropyAccumulator()
        accumulator.addSample(x: 1.0, y: 2.0, dx: 0.5, dy: 0.5, timestampNanos: 1000)

        let mockSystemEntropy = [UInt8](repeating: 0x42, count: 32)
        let seed = accumulator.generateCombinedSeed(systemEntropyOverride: mockSystemEntropy)
        #expect(seed.count == 32)

        // Generating again with the same accumulator will produce the same seed for identical override
        let seed2 = accumulator.generateCombinedSeed(systemEntropyOverride: mockSystemEntropy)
        #expect(seed == seed2)
    }

    @Test func testEntropyAccumulatorDefaultCSPRNGGeneratesUniqueSeeds() async throws {
        let accumulator1 = EntropyAccumulator()
        accumulator1.addSample(x: 5.0, y: 10.0, dx: 0.0, dy: 0.0, timestampNanos: 500)
        let seed1 = accumulator1.generateCombinedSeed()

        let accumulator2 = EntropyAccumulator()
        accumulator2.addSample(x: 5.0, y: 10.0, dx: 0.0, dy: 0.0, timestampNanos: 500)
        let seed2 = accumulator2.generateCombinedSeed()

        #expect(seed1.count == 32)
        #expect(seed2.count == 32)
        #expect(seed1 != seed2)
    }
}
