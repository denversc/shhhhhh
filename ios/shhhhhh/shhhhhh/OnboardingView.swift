//
//  OnboardingView.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import CryptoKit
import Darwin
import QuartzCore
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

    private let entropyAccumulator = EntropyAccumulator()

    private var hasReachedFullEntropy: Bool {
        progress >= 1.0
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

                    Text("Private end-to-end encryption for your text messages. To initialize your key, scribble or tap randomly in the box:")
                        .font(.subheadline)
                        .foregroundColor(Color(uiColor: .secondaryLabel))
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)

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
                        .onChanged { value in
                            handleDragChanged(value)
                        }
                        .onEnded { _ in
                            handleDragEnded()
                        }
                )
                .disabled(hasReachedFullEntropy)

                // Entropy Progress Section
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
        }
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
            checkProgressTrigger()
        }
    }

    private func handleDragEnded() {
        currentGestureLastPoint = nil
        currentGestureHasDragged = false
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

// MARK: - Restore from Backup Helper

enum RestoreBackupError: LocalizedError, Equatable {
    case blankInput

    var errorDescription: String? {
        switch self {
        case .blankInput:
            return "Backup string cannot be blank"
        }
    }
}

/// Reconstructs a CryptoKeyPair from a backup string.
///
/// If the input is a 43-character Base64URL string (or valid 32-byte key), reconstructs
/// the key pair from that private key scalar. Otherwise, generates a deterministic key pair
/// from the SHA-256 digest of the backup string.
func restoreKeyPairFromBackup(_ input: String) throws -> CryptoKeyPair {
    let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else {
        throw RestoreBackupError.blankInput
    }

    if trimmed.count == 43 {
        if let data = CryptoEngine.base64UrlDecode(trimmed), data.count == CryptoEngine.keySizeBytes {
            if let kp = try? CryptoKeyPair(rawRepresentation: data) {
                return kp
            }
        }
    } else if trimmed.count == 44 {
        if let data = CryptoEngine.base64UrlDecode(trimmed), data.count == CryptoEngine.keySizeBytes {
            if let kp = try? CryptoKeyPair(rawRepresentation: data) {
                return kp
            }
        }
        if let data = Data(base64Encoded: trimmed), data.count == CryptoEngine.keySizeBytes {
            if let kp = try? CryptoKeyPair(rawRepresentation: data) {
                return kp
            }
        }
    } else if trimmed.count == 64 && trimmed.allSatisfy({ "0123456789abcdefABCDEF".contains($0) }) {
        var data = Data()
        var index = trimmed.startIndex
        while index < trimmed.endIndex {
            let nextIndex = trimmed.index(index, offsetBy: 2)
            if let byte = UInt8(trimmed[index..<nextIndex], radix: 16) {
                data.append(byte)
            }
            index = nextIndex
        }
        if data.count == CryptoEngine.keySizeBytes, let kp = try? CryptoKeyPair(rawRepresentation: data) {
            return kp
        }
    }

    let digest = SHA256.hash(data: Data(trimmed.utf8))
    return try! CryptoKeyPair(rawRepresentation: Data(digest))
}

#Preview {
    OnboardingView(onComplete: { _ in })
}
