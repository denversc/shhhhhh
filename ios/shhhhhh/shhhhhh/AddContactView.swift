//
//  AddContactView.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import SwiftUI
import UIKit

/// Form for adding a new contact with automatic smart public key extraction and validation.
public struct AddContactView: View {
    let storage: AppStorage
    let onSave: (ContactRecord) -> Void
    let onCancel: () -> Void

    @State private var name: String = ""
    @State private var rawKeyInput: String = ""
    @Environment(\.dismiss) private var dismiss

    public init(
        storage: AppStorage,
        onSave: @escaping (ContactRecord) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.storage = storage
        self.onSave = onSave
        self.onCancel = onCancel
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var extractedKey: String? {
        SmartKeyExtractor.extractKey(from: rawKeyInput)
    }

    private var isFormValid: Bool {
        !trimmedName.isEmpty && extractedKey != nil
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Contact Name Field
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Contact Name")
                            .font(.headline)
                            .foregroundColor(.primary)

                        TextField("e.g. Wife, Alice", text: $name)
                            .textFieldStyle(.plain)
                            .padding(12)
                            .background(Color(uiColor: .secondarySystemBackground))
                            .cornerRadius(10)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(Color(uiColor: .separator), lineWidth: 1)
                            )
                            .accessibilityIdentifier("add_contact_name_input")
                    }

                    // Public Key Input Field
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Public Key")
                                .font(.headline)
                                .foregroundColor(.primary)

                            Spacer()

                            Button(action: handlePaste) {
                                HStack(spacing: 4) {
                                    Image(systemName: "doc.on.clipboard")
                                    Text("Paste from Clipboard")
                                }
                                .font(.caption)
                                .fontWeight(.medium)
                            }
                            .accessibilityIdentifier("add_contact_paste_button")
                        }

                        TextField("Pasted text or key...", text: $rawKeyInput, axis: .vertical)
                            .textFieldStyle(.plain)
                            .lineLimit(3...5)
                            .padding(12)
                            .background(Color(uiColor: .secondarySystemBackground))
                            .cornerRadius(10)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(Color(uiColor: .separator), lineWidth: 1)
                            )
                            .accessibilityIdentifier("add_contact_key_input")

                        // Extraction Feedback / Helper Text
                        if let key = extractedKey {
                            HStack(alignment: .top, spacing: 6) {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                                    .font(.caption)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Detected 43-character public key:")
                                        .font(.caption)
                                        .fontWeight(.semibold)
                                        .foregroundColor(.green)
                                    Text(key)
                                        .font(.system(.caption2, design: .monospaced))
                                        .foregroundColor(.primary)
                                        .lineLimit(2)
                                }
                            }
                            .padding(.top, 4)
                            .accessibilityIdentifier("add_contact_feedback_text")
                        } else if !rawKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            HStack(spacing: 6) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.red)
                                    .font(.caption)
                                Text("No valid 43-character Base64URL public key found.")
                                    .font(.caption)
                                    .foregroundColor(.red)
                            }
                            .padding(.top, 4)
                            .accessibilityIdentifier("add_contact_error_text")
                        } else {
                            Text("(Smart extractor automatically detects and extracts the 43-character key)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .padding(.top, 2)
                        }
                    }

                    Spacer(minLength: 24)

                    // Action Buttons Row (Matching wireframe)
                    HStack(spacing: 12) {
                        Button(action: {
                            onCancel()
                            dismiss()
                        }) {
                            Text("Cancel")
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                                .frame(height: 48)
                                .background(Color(uiColor: .secondarySystemBackground))
                                .foregroundColor(.primary)
                                .cornerRadius(10)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(Color(uiColor: .separator), lineWidth: 1)
                                )
                        }
                        .accessibilityIdentifier("add_contact_cancel_button")

                        Button(action: handleSave) {
                            Text("Save")
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                                .frame(height: 48)
                                .background(isFormValid ? Color.blue : Color.gray.opacity(0.3))
                                .foregroundColor(isFormValid ? .white : Color(uiColor: .tertiaryLabel))
                                .cornerRadius(10)
                        }
                        .disabled(!isFormValid)
                        .accessibilityIdentifier("add_contact_save_button")
                    }
                }
                .padding(20)
            }
            .navigationTitle("Add New Contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onCancel()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        handleSave()
                    }
                    .disabled(!isFormValid)
                }
            }
        }
    }

    private func handlePaste() {
        if let paste = UIPasteboard.general.string, !paste.isEmpty {
            rawKeyInput = paste
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    private func handleSave() {
        guard isFormValid, let validKey = extractedKey else { return }
        let contact = ContactRecord(
            name: trimmedName,
            publicKeyBase64Url: validKey
        )
        storage.saveContact(contact)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        onSave(contact)
        dismiss()
    }
}
