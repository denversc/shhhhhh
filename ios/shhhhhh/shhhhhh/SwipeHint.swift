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

  /// Duration of a single left-to-right sweep. After the sweep the hand stays parked at the right
  /// end of the path for the rest of the period.
  static let duration: TimeInterval = 2.0

  /// Duration of the parked hand's quick fade-out, which ends exactly when the next sweep starts.
  static let handFadeOutDuration: TimeInterval = 0.2

  /// Fraction of the sweep over which the hand (and trail) fade in at the start of each sweep.
  static let sweepFadeInFraction: Double = 0.1

  /// Fraction of the sweep, at its end, over which the trail fades out.
  static let trailFadeOutFraction: Double = 0.2

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

  /// Time into the current hint cycle, given the time elapsed since the *first* sweep started.
  ///
  /// Sweeps start every `period` seconds (start-to-start), so the result is in `0..<period`.
  static func cycleTime(sinceFirstSweepStart elapsed: TimeInterval) -> TimeInterval {
    max(elapsed, 0).truncatingRemainder(dividingBy: period)
  }

  /// Linear sweep progress (`0...1`) at `cycleTime`; stays at 1 (parked) after the sweep ends.
  static func sweepProgress(cycleTime: TimeInterval) -> Double {
    min(max(cycleTime / duration, 0), 1)
  }

  /// Opacity of the hand at `cycleTime`.
  ///
  /// The hand fades in quickly at the start of the sweep (with Reduce Motion, over the first half
  /// of the sweep window, in place), stays fully visible while parked for the rest of the cycle,
  /// and fades out over `handFadeOutDuration` so that it is fully transparent exactly when the next
  /// sweep starts.
  static func handOpacity(cycleTime: TimeInterval, reduceMotion: Bool) -> Double {
    let fadeInDuration = reduceMotion ? duration / 2 : duration * sweepFadeInFraction
    let t = min(max(cycleTime, 0), period)
    let fadeOutStart = period - handFadeOutDuration
    if t >= fadeOutStart {
      return max(0, (period - t) / handFadeOutDuration)
    }
    if t < fadeInDuration {
      return t / fadeInDuration
    }
    return 1
  }

  /// Opacity of the trail (with Reduce Motion, the static wave) at `cycleTime`.
  ///
  /// With motion, the trail fades in with the hand and fades out over the end of the sweep. With
  /// Reduce Motion, the static wave fades smoothly in and out over the sweep window. Either way the
  /// trail is invisible while the hand is parked.
  static func trailOpacity(cycleTime: TimeInterval, reduceMotion: Bool) -> Double {
    let t = sweepProgress(cycleTime: cycleTime)
    guard cycleTime < duration else { return 0 }
    if reduceMotion {
      return sin(.pi * t)
    }
    if t < sweepFadeInFraction {
      return t / sweepFadeInFraction
    }
    if t > 1 - trailFadeOutFraction {
      return (1 - t) / trailFadeOutFraction
    }
    return 1
  }
}
