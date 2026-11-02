//
//  TextUtilities.swift
//  ShhhhhhCore
//
//  Created by Denver Coneybeare on 2026-11-02.
//

import Foundation

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
