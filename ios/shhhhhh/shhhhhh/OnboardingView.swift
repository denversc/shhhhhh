//
//  OnboardingView.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Darwin
import QuartzCore
import ShhhhhhCore
import SwiftUI
import UIKit

private struct StrokeSegment: Identifiable {
  let id = UUID()
  let start: CGPoint
  let end: CGPoint
}

private let tapEntropyIncrement: Double = 0.013
private let moveEntropyIncrement: Double = 0.0017
private let minMoveDistance: CGFloat = 3.0
private let maxVisualSegments: Int = 360
private let maxVisualDots: Int = 80
private let minKeygenSpinnerMs: UInt64 = 650
private let progressSectionFadeInSeconds: Double = 0.2
private let brandBlue = Color(red: 0x1A / 255.0, green: 0x73 / 255.0, blue: 0xE8 / 255.0)

struct OnboardingView: View {
  let onComplete: (CryptoKeyPair) -> Void

  @State private var segments: [StrokeSegment] = []
  @State private var dots: [CGPoint] = []
  @State private var progress: Double = 0.0
  @State private var isKeyReady: Bool = false
  @State private var isGenerating: Bool = false
  @State private var showRestoreSheet: Bool = false
  @State private var generatedKeyPair: CryptoKeyPair? = nil

  @State private var currentGestureLastPoint: CGPoint? = nil
  @State private var currentGestureHasDragged: Bool = false

  // Idle swipe hint state. `hintStartDate` is non-nil only while a hint animation is playing.
  @State private var hintStartDate: Date? = nil
  @State private var hintIdleGeneration: Int = 0
  @GestureState private var isFingerDown: Bool = false

  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  private let entropyAccumulator = EntropyAccumulator()

  private var hasReachedFullEntropy: Bool {
    progress >= 1.0
  }

  private var hasCollectedEntropy: Bool {
    progress > 0
  }

  /// Whether the idle swipe hint is allowed to run at all right now.
  private var isSwipeHintEnabled: Bool {
    !hasReachedFullEntropy && !isGenerating && !isKeyReady && !isFingerDown
      && !showRestoreSheet && scenePhase == .active
  }

  private var swipeHintTaskKey: SwipeHintTaskKey {
    SwipeHintTaskKey(isEnabled: isSwipeHintEnabled, idleGeneration: hintIdleGeneration)
  }

  var body: some View {
    NavigationStack {
      VStack(spacing: 16) {
        // Header & Instructions
        VStack(spacing: 8) {
          Text("Welcome to shhhhhh")
            .font(.title2)
            .fontWeight(.bold)
            .multilineTextAlignment(.center)

          Text(
            "Private end-to-end encryption for your text messages. To initialize your key, scribble or tap randomly in the box:"
          )
          .font(.subheadline)
          .foregroundColor(Color(uiColor: .secondaryLabel))
          .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)

        // Entropy Progress Section
        // Hidden until the first entropy sample is collected, but its space stays reserved in the
        // layout (by an invisible, non-accessible copy) so that the entropy box below never changes
        // size or position when it appears. The real section is only inserted into the view tree
        // once visible because `.accessibilityHidden` alone does not hide it from XCUITest.
        ZStack {
          EntropyProgressSection(progress: 0)
            .hidden()
            .accessibilityHidden(true)
          if hasCollectedEntropy {
            EntropyProgressSection(progress: progress)
              .transition(.opacity)
          }
        }
        .animation(.easeInOut(duration: progressSectionFadeInSeconds), value: hasCollectedEntropy)

        // Interactive Entropy Canvas Box
        ZStack(alignment: .center) {
          if segments.isEmpty && dots.isEmpty && progress == 0 {
            Text("Scribble or tap randomly here")
              .font(.body)
              .foregroundColor(Color(uiColor: .placeholderText))
              .multilineTextAlignment(.center)
              .padding(24)
              .allowsHitTesting(false)
          }

          Canvas { context, _ in
            renderStrokes(in: &context)
          }
          .allowsHitTesting(false)

          if let hintStartDate {
            SwipeHintOverlay(startDate: hintStartDate, reduceMotion: reduceMotion)
              .allowsHitTesting(false)
              .accessibilityElement()
              .accessibilityLabel("Swipe hint")
              .accessibilityIdentifier("entropy_swipe_hint")
          }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
          RoundedRectangle(cornerRadius: 12)
            .stroke(Color(uiColor: .separator), lineWidth: 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("entropy_canvas")
        .gesture(
          DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .updating($isFingerDown) { _, isDown, _ in
              isDown = true
            }
            .onChanged { value in
              handleDragChanged(value)
            }
            .onEnded { _ in
              handleDragEnded()
            }
        )
        .disabled(hasReachedFullEntropy)

        // Primary Action / Key Generation Status Button (50pt height, 10pt corner radius)
        Button(action: {
          if isKeyReady, let keyPair = generatedKeyPair {
            onComplete(keyPair)
          }
        }) {
          HStack(spacing: 10) {
            if isKeyReady {
              Text("Get Started")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.white)
            } else if isGenerating || hasReachedFullEntropy {
              ProgressView()
                .tint(brandBlue)
              Text("Generating Key Pair...")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(Color(uiColor: .label))
            } else {
              Text("Scribble or Tap to Collect Entropy")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(Color(uiColor: .secondaryLabel))
            }
          }
          .frame(maxWidth: .infinity)
          .frame(height: 50)
          .background(
            isKeyReady
              ? brandBlue
              : Color(uiColor: .secondarySystemBackground)
          )
          .cornerRadius(10)
        }
        .disabled(!isKeyReady)
        .accessibilityIdentifier("keygen_action_button")

        Divider()

        // Secondary Action: Restore from Backup
        Button(action: {
          showRestoreSheet = true
        }) {
          Text("Restore from Backup")
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(.blue)
        }
        .disabled(isGenerating || (hasReachedFullEntropy && !isKeyReady))
        .padding(.vertical, 4)
        .accessibilityIdentifier("restore_backup_button")
      }
      .padding(16)
      .navigationTitle("shhhhhh")
      .navigationBarTitleDisplayMode(.inline)
      .sheet(isPresented: $showRestoreSheet) {
        RestoreBackupSheet(onRestoreComplete: onComplete)
      }
      .task(id: swipeHintTaskKey) {
        await runSwipeHintLoop()
      }
    }
  }

  // MARK: - Idle Swipe Hint

  /// Plays the swipe hint every `SwipeHint.period` seconds, starting `SwipeHint.idleDelay` seconds
  /// after this task starts. The task is restarted (via `swipeHintTaskKey`) on every entropy
  /// sample, finger down/up, and whenever the hint becomes enabled/disabled, which restarts the
  /// full idle countdown.
  private func runSwipeHintLoop() async {
    hintStartDate = nil
    guard isSwipeHintEnabled else { return }

    let idleSince = Date()
    while !Task.isCancelled {
      let elapsed = Date().timeIntervalSince(idleSince)
      let delay = SwipeHint.delayUntilNextStart(elapsed: elapsed)
      do {
        try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        hintStartDate = Date()
        try await Task.sleep(nanoseconds: UInt64(SwipeHint.duration * 1_000_000_000))
        hintStartDate = nil
        // Avoid re-triggering immediately if we woke up exactly on a start boundary.
        try await Task.sleep(nanoseconds: 10_000_000)
      } catch {
        return
      }
    }
  }

  /// Hides any in-flight hint and restarts the idle countdown.
  private func resetSwipeHintIdleTimer() {
    hintStartDate = nil
    hintIdleGeneration &+= 1
  }

  // MARK: - Gesture Handling

  private func handleDragChanged(_ value: DragGesture.Value) {
    guard progress < 1.0 else { return }

    let currentPoint = value.location

    if let lastPoint = currentGestureLastPoint {
      let dx = currentPoint.x - lastPoint.x
      let dy = currentPoint.y - lastPoint.y
      let distance = hypot(dx, dy)

      if distance >= minMoveDistance {
        if !currentGestureHasDragged && !dots.isEmpty {
          dots.removeLast()
        }
        currentGestureHasDragged = true

        let timestamp = clock_gettime_nsec_np(CLOCK_UPTIME_RAW)
        entropyAccumulator.addSample(
          x: Float(currentPoint.x),
          y: Float(currentPoint.y),
          dx: Float(dx),
          dy: Float(dy),
          timestampNanos: timestamp
        )

        if segments.count >= maxVisualSegments {
          segments.removeFirst()
        }
        segments.append(StrokeSegment(start: lastPoint, end: currentPoint))
        currentGestureLastPoint = currentPoint

        progress = min(1.0, progress + moveEntropyIncrement)
        resetSwipeHintIdleTimer()
        checkProgressTrigger()
      }
    } else {
      currentGestureLastPoint = currentPoint
      currentGestureHasDragged = false

      let timestamp = clock_gettime_nsec_np(CLOCK_UPTIME_RAW)
      entropyAccumulator.addSample(
        x: Float(currentPoint.x),
        y: Float(currentPoint.y),
        dx: 0,
        dy: 0,
        timestampNanos: timestamp
      )

      if dots.count >= maxVisualDots {
        dots.removeFirst()
      }
      dots.append(currentPoint)

      progress = min(1.0, progress + tapEntropyIncrement)
      resetSwipeHintIdleTimer()
      checkProgressTrigger()
    }
  }

  private func handleDragEnded() {
    currentGestureLastPoint = nil
    currentGestureHasDragged = false
    resetSwipeHintIdleTimer()
  }

  // MARK: - Key Generation Trigger

  private func checkProgressTrigger() {
    if progress >= 1.0 && !isKeyReady && !isGenerating {
      triggerKeyGeneration()
    }
  }

  private func triggerKeyGeneration() {
    isGenerating = true
    Task {
      let startTime = CACurrentMediaTime()
      let seed = await Task.detached(priority: .userInitiated) {
        entropyAccumulator.generateCombinedSeed()
      }.value

      let elapsedMs = (CACurrentMediaTime() - startTime) * 1000.0
      let remainingMs = Double(minKeygenSpinnerMs) - elapsedMs
      if remainingMs > 0 {
        try? await Task.sleep(nanoseconds: UInt64(remainingMs * 1_000_000))
      }

      let keyPair = try! CryptoKeyPair(rawRepresentation: Data(seed))

      await MainActor.run {
        generatedKeyPair = keyPair
        isGenerating = false
        isKeyReady = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
      }
    }
  }

  // MARK: - Canvas Rendering

  private func renderStrokes(in context: inout GraphicsContext) {
    let strokeWidth: CGFloat = 3.0
    let dotRadius: CGFloat = 4.0

    let totalSegments = segments.count
    for (index, segment) in segments.enumerated() {
      let ageRatio = totalSegments <= 1 ? 1.0 : Double(index + 1) / Double(totalSegments)
      let alpha = 0.25 + 0.65 * ageRatio
      var path = Path()
      path.move(to: segment.start)
      path.addLine(to: segment.end)
      context.stroke(
        path,
        with: .color(brandBlue.opacity(alpha)),
        style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round, lineJoin: .round)
      )
    }

    let totalDots = dots.count
    for (index, dot) in dots.enumerated() {
      let ageRatio = totalDots <= 1 ? 1.0 : Double(index + 1) / Double(totalDots)
      let alpha = 0.35 + 0.55 * ageRatio
      let rect = CGRect(
        x: dot.x - dotRadius,
        y: dot.y - dotRadius,
        width: dotRadius * 2,
        height: dotRadius * 2
      )
      context.fill(
        Path(ellipseIn: rect),
        with: .color(brandBlue.opacity(alpha))
      )
    }
  }
}

// MARK: - Entropy Progress Section

/// "Entropy Collection" label, percentage, and progress bar shown above the entropy box.
private struct EntropyProgressSection: View {
  let progress: Double

  var body: some View {
    VStack(spacing: 8) {
      HStack {
        Text("Entropy Collection")
          .font(.footnote)
          .fontWeight(.medium)
        Spacer()
        Text("\(Int(round(progress * 100)))%")
          .font(.footnote)
          .fontWeight(.semibold)
          .foregroundColor(brandBlue)
          .accessibilityIdentifier("entropy_percentage_text")
      }

      ProgressView(value: progress, total: 1.0)
        .tint(brandBlue)
        .accessibilityIdentifier("entropy_progress_bar")
    }
  }
}

// MARK: - Idle Swipe Hint Overlay

/// Identity of the idle swipe hint task; any change restarts the task (and the idle countdown).
private struct SwipeHintTaskKey: Equatable {
  let isEnabled: Bool
  let idleGeneration: Int
}

/// Purely visual overlay that plays one swipe hint animation starting at `startDate`.
///
/// A semi-transparent pointing hand sweeps left-to-right along `SwipeHint`'s wave path, trailed by
/// a short fading brand-blue stroke. With Reduce Motion, the hand stays at the end of a static wave
/// and the whole hint fades in and out in place instead.
private struct SwipeHintOverlay: View {
  let startDate: Date
  let reduceMotion: Bool

  private static let iconSize: CGFloat = 48
  /// Offset from the fingertip to the center of the `hand.point.up.left.fill` glyph, whose
  /// fingertip sits near the glyph's top-left corner.
  private static let fingertipToIconCenter = CGSize(width: 18, height: 20)
  private static let trailLineWidth: CGFloat = 3
  private static let trailMaxOpacity: Double = 0.6
  private static let staticWaveOpacity: Double = 0.4
  private static let handOpacity: Double = 0.45

  var body: some View {
    TimelineView(.animation) { timeline in
      let elapsed = timeline.date.timeIntervalSince(startDate)
      let t = min(max(elapsed / SwipeHint.duration, 0), 1)
      GeometryReader { geometry in
        let size = geometry.size
        let tip =
          reduceMotion
          ? SwipeHint.wavePoint(progress: 1, in: size)
          : SwipeHint.fingertipPoint(animationProgress: t, in: size)

        ZStack {
          Canvas { context, canvasSize in
            if reduceMotion {
              strokeWave(
                SwipeHint.fullWavePoints(in: canvasSize), opacity: { _ in Self.staticWaveOpacity },
                in: &context)
            } else {
              strokeWave(
                SwipeHint.trailPoints(animationProgress: t, in: canvasSize),
                opacity: { ratio in Self.trailMaxOpacity * ratio }, in: &context)
            }
          }

          Image(systemName: "hand.point.up.left.fill")
            .font(.system(size: Self.iconSize))
            .foregroundStyle(Color(uiColor: .label).opacity(Self.handOpacity))
            .position(
              x: tip.x + Self.fingertipToIconCenter.width,
              y: tip.y + Self.fingertipToIconCenter.height)
        }
        .opacity(SwipeHint.opacity(animationProgress: t, reduceMotion: reduceMotion))
      }
    }
  }

  /// Strokes consecutive segments of `points`, with per-segment opacity given by `opacity`, which
  /// receives the segment's position along the polyline in `0...1` (oldest to newest).
  private func strokeWave(
    _ points: [CGPoint], opacity: (Double) -> Double, in context: inout GraphicsContext
  ) {
    guard points.count >= 2 else { return }
    let segmentCount = points.count - 1
    for i in 0..<segmentCount {
      var path = Path()
      path.move(to: points[i])
      path.addLine(to: points[i + 1])
      let ratio = Double(i + 1) / Double(segmentCount)
      context.stroke(
        path,
        with: .color(brandBlue.opacity(opacity(ratio))),
        style: StrokeStyle(lineWidth: Self.trailLineWidth, lineCap: .round, lineJoin: .round)
      )
    }
  }
}

// MARK: - Restore from Backup Sheet

struct RestoreBackupSheet: View {
  let onRestoreComplete: (CryptoKeyPair) -> Void
  @Environment(\.dismiss) private var dismiss

  @State private var backupInput: String = ""
  @State private var restoreError: String? = nil

  var body: some View {
    NavigationStack {
      VStack(alignment: .leading, spacing: 16) {
        Text("Paste your Paper Backup bundle below to restore your keys and contacts.")
          .font(.body)
          .foregroundColor(Color(uiColor: .secondaryLabel))

        VStack(alignment: .leading, spacing: 6) {
          ZStack(alignment: .topLeading) {
            if backupInput.isEmpty {
              Text("Paste Paper Backup string here...")
                .font(.body)
                .foregroundColor(Color(uiColor: .placeholderText))
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .allowsHitTesting(false)
            }

            TextEditor(text: $backupInput)
              .font(.body)
              .frame(minHeight: 110, maxHeight: 150)
              .padding(8)
              .scrollContentBackground(.hidden)
              .accessibilityIdentifier("restore_text_editor")
              .onChange(of: backupInput) { _, _ in
                if restoreError != nil {
                  restoreError = nil
                }
              }
          }
          .background(Color(uiColor: .secondarySystemBackground))
          .cornerRadius(10)
          .overlay(
            RoundedRectangle(cornerRadius: 10)
              .stroke(restoreError != nil ? Color.red : Color(uiColor: .separator), lineWidth: 1)
          )

          if let error = restoreError {
            Text(error)
              .font(.caption)
              .foregroundColor(.red)
              .accessibilityIdentifier("restore_error_text")
          }
        }

        Button(action: handlePasteFromClipboard) {
          HStack(spacing: 8) {
            Image(systemName: "doc.on.clipboard")
            Text("Paste from Clipboard")
              .fontWeight(.medium)
          }
          .frame(maxWidth: .infinity)
          .frame(height: 44)
          .background(Color(uiColor: .secondarySystemBackground))
          .foregroundColor(.primary)
          .cornerRadius(8)
          .overlay(
            RoundedRectangle(cornerRadius: 8)
              .stroke(Color(uiColor: .separator), lineWidth: 1)
          )
        }
        .accessibilityIdentifier("paste_clipboard_button")

        Spacer()
      }
      .padding(16)
      .navigationTitle("Restore from Backup")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel") {
            dismiss()
          }
          .accessibilityIdentifier("cancel_restore_button")
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Restore") {
            handleRestore()
          }
          .fontWeight(.semibold)
          .accessibilityIdentifier("confirm_restore_button")
        }
      }
    }
  }

  private func handlePasteFromClipboard() {
    if let clipboardString = UIPasteboard.general.string, !clipboardString.isEmpty {
      backupInput = clipboardString
      restoreError = nil
    } else {
      restoreError = "Clipboard is empty."
    }
  }

  private func handleRestore() {
    if backupInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      restoreError = "Please paste a backup bundle before restoring."
    } else {
      do {
        let restored = try restoreKeyPairFromBackup(backupInput)
        dismiss()
        onRestoreComplete(restored)
      } catch {
        restoreError = "Invalid backup: \(error.localizedDescription)"
      }
    }
  }
}

#Preview {
  OnboardingView(onComplete: { _ in })
}
