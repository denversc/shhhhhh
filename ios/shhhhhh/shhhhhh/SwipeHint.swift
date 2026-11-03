//
//  SwipeHint.swift
//  shhhhhh
//

import CoreGraphics
import Foundation

/// Pure timing and geometry helpers for the idle "swipe hint" animation shown over the onboarding
/// entropy box.
///
/// The hint is a pointing finger that sweeps left-to-right along a gentle S-shaped wave, leaving a
/// short fading trail, to nudge the user into scribbling. These helpers contain no SwiftUI state so
/// that they can be unit tested in isolation.
enum SwipeHint {
  /// Idle time, measured from the most recent input, before the first hint animation starts.
  static let idleDelay: TimeInterval = 5.0

  /// Start-to-start interval between consecutive hint animations while the user remains idle.
  static let period: TimeInterval = 5.0

  /// Duration of a single hint animation.
  static let duration: TimeInterval = 1.0

  /// Wave amplitude, as a fraction of the box height, on either side of the vertical center.
  static let amplitudeFraction: CGFloat = 0.15

  /// Horizontal inset, as a fraction of the box width, kept clear at each end of the path.
  static let horizontalInsetFraction: CGFloat = 0.15

  /// Length of the fading trail behind the fingertip, as a fraction of the animation's duration.
  static let trailFraction: Double = 0.3

  /// Cubic ease-in-out: accelerates toward the midpoint and decelerates toward the end.
  ///
  /// - Parameter t: linear progress; clamped to `0...1`.
  /// - Returns: eased progress in `0...1`.
  static func easeInOut(_ t: Double) -> Double {
    let t = min(max(t, 0), 1)
    if t < 0.5 {
      return 4 * t * t * t
    }
    let u = -2 * t + 2
    return 1 - (u * u * u) / 2
  }

  /// Point on the hint's wave path for the given *eased* horizontal progress.
  ///
  /// The path runs left-to-right inside `size`, inset from both edges, along one full sine period
  /// centered on the vertical midline: it rises, crosses the center, dips, and returns to the
  /// center, forming a gentle S-curve.
  ///
  /// - Parameters:
  ///   - progress: eased progress along the path; clamped to `0...1`.
  ///   - size: size of the box the path is drawn in.
  static func wavePoint(progress: Double, in size: CGSize) -> CGPoint {
    let p = CGFloat(min(max(progress, 0), 1))
    let inset = size.width * horizontalInsetFraction
    let x = inset + p * (size.width - 2 * inset)
    let amplitude = size.height * amplitudeFraction
    // Subtract because the y-axis points down: the wave first rises, then dips.
    let y = size.height / 2 - amplitude * sin(2 * .pi * p)
    return CGPoint(x: x, y: y)
  }

  /// Fingertip position for the given linear animation progress (eased horizontally).
  static func fingertipPoint(animationProgress t: Double, in size: CGSize) -> CGPoint {
    wavePoint(progress: easeInOut(t), in: size)
  }

  /// Sampled trail points (oldest first, fingertip last) for the given linear animation progress.
  ///
  /// The trail covers the last `trailFraction` of the animation's *time*, so it stretches out while
  /// the finger is moving fast and shrinks as the finger decelerates.
  static func trailPoints(
    animationProgress t: Double, in size: CGSize, sampleCount: Int = 24
  ) -> [CGPoint] {
    let end = min(max(t, 0), 1)
    let start = max(0, end - trailFraction)
    guard sampleCount >= 2, end > start else {
      return [fingertipPoint(animationProgress: end, in: size)]
    }
    return (0..<sampleCount).map { i in
      let s = start + (end - start) * Double(i) / Double(sampleCount - 1)
      return fingertipPoint(animationProgress: s, in: size)
    }
  }

  /// Sampled points along the entire wave path, used as the static trail when Reduce Motion is on.
  static func fullWavePoints(in size: CGSize, sampleCount: Int = 48) -> [CGPoint] {
    guard sampleCount >= 2 else { return [wavePoint(progress: 0, in: size)] }
    return (0..<sampleCount).map { i in
      wavePoint(progress: Double(i) / Double(sampleCount - 1), in: size)
    }
  }

  /// Delay from `elapsed` (time since the most recent input) until the next animation starts.
  ///
  /// Animations start at `idleDelay`, `idleDelay + period`, `idleDelay + 2 * period`, and so on
  /// after the most recent input. If `elapsed` is exactly at a start time the delay is zero.
  static func delayUntilNextStart(elapsed: TimeInterval) -> TimeInterval {
    if elapsed <= idleDelay {
      return idleDelay - max(elapsed, 0)
    }
    let sinceFirst = elapsed - idleDelay
    let remainder = sinceFirst.truncatingRemainder(dividingBy: period)
    return remainder == 0 ? 0 : period - remainder
  }

  /// Linear progress (`0..<1`) of the animation in flight at `elapsed` seconds after the most recent
  /// input, or `nil` if no animation is playing at that moment.
  static func animationProgress(elapsed: TimeInterval) -> Double? {
    guard elapsed >= idleDelay else { return nil }
    let sinceStart = (elapsed - idleDelay).truncatingRemainder(dividingBy: period)
    guard sinceStart < duration else { return nil }
    return sinceStart / duration
  }

  /// Opacity envelope for the overlay over a single animation.
  ///
  /// With motion, the overlay fades in quickly and fades out at the end so it does not pop. With
  /// Reduce Motion, the stationary hand and wave fade in and out smoothly over the whole duration.
  static func opacity(animationProgress t: Double, reduceMotion: Bool) -> Double {
    let t = min(max(t, 0), 1)
    if reduceMotion {
      return sin(.pi * t)
    }
    let fadeIn = 0.12
    let fadeOut = 0.2
    if t < fadeIn {
      return t / fadeIn
    }
    if t > 1 - fadeOut {
      return (1 - t) / fadeOut
    }
    return 1
  }
}
