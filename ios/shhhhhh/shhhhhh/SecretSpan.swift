//
//  SecretSpan.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import Foundation
import SwiftUI
import UIKit

/// Representation of a decrypted secret substring within the displayed text.
public struct SecretSpan: Equatable, Hashable {
    public let secret: String
    public let range: Range<String.Index>
    public let nsRange: NSRange

    public init(secret: String, range: Range<String.Index>, in text: String) {
        self.secret = secret
        self.range = range
        self.nsRange = NSRange(range, in: text)
    }

    public init(secret: String, nsRange: NSRange, in text: String) {
        self.secret = secret
        self.nsRange = nsRange
        self.range = Range(nsRange, in: text) ?? (text.startIndex..<text.startIndex)
    }
}

/// Dispatched user action when tapping the morphed [Copy Secret] button.
public enum CopySecretAction: Equatable {
    case copySingle(String)
    case showPicker([String])
    case none
}

/// Resolves the clipboard or sheet presentation action based on decrypted secret count.
public func resolveCopySecretAction(_ secrets: [String]) -> CopySecretAction {
    switch secrets.count {
    case 0:
        return .none
    case 1:
        return .copySingle(secrets[0])
    default:
        return .showPicker(secrets)
    }
}

/// Finds the exact spans corresponding to decrypted secrets within the text.
/// If valid explicit candidate ranges are supplied matching secrets count, they are used directly.
/// Otherwise, scans sequentially through the text.
public func findSecretSpans(
    text: String,
    secrets: [String],
    explicitRanges: [Range<String.Index>]? = nil
) -> [SecretSpan] {
    guard !text.isEmpty, !secrets.isEmpty else { return [] }

    if let explicitRanges = explicitRanges, explicitRanges.count == secrets.count {
        var spans: [SecretSpan] = []
        var valid = true
        for (index, secret) in secrets.enumerated() {
            let range = explicitRanges[index]
            if range.lowerBound >= text.startIndex && range.upperBound <= text.endIndex &&
                text[range] == secret {
                spans.append(SecretSpan(secret: secret, range: range, in: text))
            } else {
                valid = false
                break
            }
        }
        if valid {
            return spans
        }
    }

    var spans: [SecretSpan] = []
    var currentIndex = text.startIndex
    for secret in secrets {
        guard !secret.isEmpty else { continue }
        if let range = text.range(of: secret, range: currentIndex..<text.endIndex) {
            spans.append(SecretSpan(secret: secret, range: range, in: text))
            currentIndex = range.upperBound
        }
    }
    return spans
}

/// Convenience overload for finding spans using NSRange candidate offsets.
public func findSecretSpans(
    text: String,
    secrets: [String],
    explicitNSRanges: [NSRange]?
) -> [SecretSpan] {
    let stringRanges = explicitNSRanges?.compactMap { Range($0, in: text) }
    return findSecretSpans(text: text, secrets: secrets, explicitRanges: stringRanges)
}

/// Builds an NSAttributedString with visual background highlighting and semibold styling
/// for each decrypted secret span.
public func buildSecretAttributedString(
    text: String,
    spans: [SecretSpan],
    highlightColor: UIColor = UIColor.systemBlue.withAlphaComponent(0.18),
    textColor: UIColor = .label,
    font: UIFont = .systemFont(ofSize: 16),
    highlightFont: UIFont = .systemFont(ofSize: 16, weight: .semibold)
) -> NSAttributedString {
    let attributed = NSMutableAttributedString(
        string: text,
        attributes: [
            .foregroundColor: textColor,
            .font: font
        ]
    )

    let textLength = (text as NSString).length
    for span in spans {
        let nsRange = span.nsRange
        if nsRange.location != NSNotFound &&
            nsRange.location + nsRange.length <= textLength {
            attributed.addAttributes([
                .backgroundColor: highlightColor,
                .foregroundColor: textColor,
                .font: highlightFont
            ], range: nsRange)
        }
    }

    return attributed
}

/// Computes the new string and updated cursor range when inserting or replacing text.
/// If `isFocused` is false, appends `insertedText` to the end of `currentText`.
/// Otherwise, replaces `selectedRange` (or inserts at cursor if collapsed) with `insertedText`.
public func applyTextInsertion(
    currentText: String,
    insertedText: String,
    selectedRange: NSRange,
    isFocused: Bool
) -> (newText: String, newRange: NSRange) {
    let nsText = currentText as NSString
    let targetRange: NSRange
    if isFocused && selectedRange.location != NSNotFound {
        let clampedLocation = min(selectedRange.location, nsText.length)
        let clampedLength = min(selectedRange.length, nsText.length - clampedLocation)
        targetRange = NSRange(location: clampedLocation, length: clampedLength)
    } else {
        targetRange = NSRange(location: nsText.length, length: 0)
    }

    let newText = nsText.replacingCharacters(in: targetRange, with: insertedText)
    let newCursorLocation = targetRange.location + (insertedText as NSString).length
    let newRange = NSRange(location: newCursorLocation, length: 0)
    return (newText, newRange)
}

/// UIKit-backed multi-line text editor supporting inline tappable secret spans
/// and seamless editing / typing fallback.
public struct SecretTextEditor: UIViewRepresentable {
    @Binding public var text: String
    @Binding public var selectedRange: NSRange
    @Binding public var isFocused: Bool
    public var spans: [SecretSpan]
    public var isHighlightActive: Bool
    public var onSecretTapped: (String) -> Void
    public var onTextChanged: (String) -> Void

    public init(
        text: Binding<String>,
        selectedRange: Binding<NSRange> = .constant(NSRange(location: 0, length: 0)),
        isFocused: Binding<Bool> = .constant(false),
        spans: [SecretSpan],
        isHighlightActive: Bool,
        onSecretTapped: @escaping (String) -> Void,
        onTextChanged: @escaping (String) -> Void
    ) {
        self._text = text
        self._selectedRange = selectedRange
        self._isFocused = isFocused
        self.spans = spans
        self.isHighlightActive = isHighlightActive
        self.onSecretTapped = onSecretTapped
        self.onTextChanged = onTextChanged
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    public func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.backgroundColor = .clear
        textView.font = .systemFont(ofSize: 16)
        textView.textColor = .label
        textView.textContainerInset = UIEdgeInsets(top: 8, left: 4, bottom: 8, right: 4)
        textView.accessibilityIdentifier = "main_text_editor"
        textView.delegate = context.coordinator
        textView.isScrollEnabled = true
        textView.isEditable = true
        textView.isSelectable = true

        let tapGesture = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        tapGesture.delegate = context.coordinator
        tapGesture.cancelsTouchesInView = true
        textView.addGestureRecognizer(tapGesture)
        context.coordinator.tapGesture = tapGesture
        context.coordinator.textView = textView

        return textView
    }

    public func updateUIView(_ uiView: UITextView, context: Context) {
        context.coordinator.parent = self

        if isHighlightActive && !spans.isEmpty {
            let attributed = buildSecretAttributedString(text: text, spans: spans)
            if uiView.attributedText != attributed {
                uiView.attributedText = attributed
            }
        } else {
            if uiView.text != text {
                uiView.text = text
                uiView.font = .systemFont(ofSize: 16)
                uiView.textColor = .label
            } else if uiView.attributedText.length > 0 && !isHighlightActive {
                // Ensure plain styling without span highlight remnants
                let plain = NSAttributedString(
                    string: text,
                    attributes: [
                        .font: UIFont.systemFont(ofSize: 16),
                        .foregroundColor: UIColor.label
                    ]
                )
                if uiView.attributedText != plain {
                    uiView.attributedText = plain
                }
            }
        }

        let textLength = (text as NSString).length
        if selectedRange.location != NSNotFound {
            let clampedLocation = min(selectedRange.location, textLength)
            let clampedLength = min(selectedRange.length, textLength - clampedLocation)
            let validRange = NSRange(location: clampedLocation, length: clampedLength)
            if uiView.selectedRange != validRange {
                uiView.selectedRange = validRange
            }
        }
    }

    public class Coordinator: NSObject, UITextViewDelegate, UIGestureRecognizerDelegate {
        var parent: SecretTextEditor
        weak var textView: UITextView?
        weak var tapGesture: UITapGestureRecognizer?

        init(_ parent: SecretTextEditor) {
            self.parent = parent
        }

        public func textViewDidBeginEditing(_ textView: UITextView) {
            if !parent.isFocused {
                parent.isFocused = true
            }
            if parent.selectedRange != textView.selectedRange {
                parent.selectedRange = textView.selectedRange
            }
        }

        public func textViewDidEndEditing(_ textView: UITextView) {
            if parent.isFocused {
                parent.isFocused = false
            }
        }

        public func textViewDidChangeSelection(_ textView: UITextView) {
            if parent.selectedRange != textView.selectedRange {
                parent.selectedRange = textView.selectedRange
            }
        }

        public func textViewDidChange(_ textView: UITextView) {
            let newText = textView.text ?? ""
            if parent.text != newText {
                parent.text = newText
            }
            if parent.selectedRange != textView.selectedRange {
                parent.selectedRange = textView.selectedRange
            }
            parent.onTextChanged(newText)
        }

        func span(at point: CGPoint) -> SecretSpan? {
            guard let tv = textView,
                  let text = tv.text, !text.isEmpty,
                  parent.isHighlightActive, !parent.spans.isEmpty else {
                return nil
            }

            for span in parent.spans {
                let nsRange = span.nsRange
                guard nsRange.location != NSNotFound,
                      nsRange.location + nsRange.length <= (text as NSString).length,
                      let startPos = tv.position(from: tv.beginningOfDocument, offset: nsRange.location),
                      let endPos = tv.position(from: startPos, offset: nsRange.length),
                      let textRange = tv.textRange(from: startPos, to: endPos) else {
                    continue
                }

                let rects = tv.selectionRects(for: textRange)
                for selectionRect in rects {
                    // Check if point falls within bounding rect with 4pt touch padding
                    let expandedRect = selectionRect.rect.insetBy(dx: -4, dy: -4)
                    if expandedRect.contains(point) {
                        return span
                    }
                }
            }
            return nil
        }

        // MARK: - UIGestureRecognizerDelegate

        public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard let tv = textView else { return false }
            let point = touch.location(in: tv)
            return span(at: point) != nil
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended, let tv = textView else { return }
            let point = gesture.location(in: tv)
            if let matchedSpan = span(at: point) {
                parent.onSecretTapped(matchedSpan.secret)
            }
        }
    }
}
