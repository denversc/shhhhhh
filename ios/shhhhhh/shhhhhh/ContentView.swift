//
//  ContentView.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import SwiftUI
import UIKit

enum EncryptButtonState {
    case encrypt
    case copyCiphertext
}

enum DecryptButtonState {
    case decrypt
    case copySecret
}

struct ContentView: View {
    @State private var text: String = ""
    @State private var encryptState: EncryptButtonState = .encrypt
    @State private var decryptState: DecryptButtonState = .decrypt
    @State private var selectedContact: String = "Myself (This Device)"
    @State private var showContactSheet: Bool = false
    @State private var copiedBannerText: String? = nil
    @State private var programmaticTextTarget: String? = nil
    @State private var isDecryptPressed: Bool = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                // Shared Multi-line Text Box with Header Utility Bar
                VStack(spacing: 0) {
                    // Utility bar pinned at top of text box container
                    HStack {
                        Spacer()
                        Button(action: handleClear) {
                            HStack(spacing: 4) {
                                Image(systemName: "xmark.circle")
                                Text("Clear")
                            }
                            .font(.subheadline)
                            .foregroundColor(.primary)
                        }
                        .padding(.trailing, 12)

                        Button(action: handleCopyVerbatim) {
                            HStack(spacing: 4) {
                                Image(systemName: "doc.on.doc")
                                Text("Copy")
                            }
                            .font(.subheadline)
                            .foregroundColor(.primary)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(uiColor: .secondarySystemBackground))

                    Divider()

                    // Multi-line Text Editor
                    ZStack(alignment: .topLeading) {
                        if text.isEmpty {
                            Text("Type message to encrypt or paste text containing ciphertext here...")
                                .font(.body)
                                .foregroundColor(Color(uiColor: .placeholderText))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 12)
                                .allowsHitTesting(false)
                        }

                        TextEditor(text: $text)
                            .font(.body)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .scrollContentBackground(.hidden)
                            .background(Color.clear)
                            .onChange(of: text) { _, newValue in
                                handleTextChange(newValue)
                            }
                    }
                    .frame(minHeight: 180, maxHeight: .infinity)
                }
                .background(Color(uiColor: .secondarySystemBackground))
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color(uiColor: .separator), lineWidth: 1)
                )

                // Action Buttons Row (Fixed Dimensions, Zero Layout Shift)
                HStack(spacing: 12) {
                    // Encrypt / Morphing Button
                    Button(action: handleEncryptTap) {
                        HStack(spacing: 6) {
                            Image(systemName: encryptState == .encrypt ? "lock.fill" : "doc.on.doc.fill")
                            Text(encryptState == .encrypt ? "Encrypt" : "Copy Ciphertext")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(encryptState == .encrypt ? Color.blue : Color.indigo)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                    }

                    // Decrypt / Morphing Button (Supports Tap and Long-Press; mutually exclusive)
                    HStack(spacing: 6) {
                        Image(systemName: decryptState == .decrypt ? "lock.open.fill" : "key.fill")
                        Text(decryptState == .decrypt ? "Decrypt" : "Copy Secret")
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(decryptState == .decrypt ? Color.green : Color.orange)
                    .foregroundColor(.white)
                    .cornerRadius(10)
                    .opacity(isDecryptPressed ? 0.75 : 1.0)
                    .contentShape(Rectangle())
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isButton)
                    .onLongPressGesture(
                        minimumDuration: 0.5,
                        perform: {
                            if decryptState == .copySecret {
                                handleDecryptLongPress()
                            } else {
                                handleDecryptTap()
                            }
                        },
                        onPressingChanged: { isPressing in
                            isDecryptPressed = isPressing
                        }
                    )
                    .onTapGesture {
                        handleDecryptTap()
                    }
                }

                // Contact Selector Widget (Positioned Adjacent to Action Buttons)
                Button(action: { showContactSheet = true }) {
                    HStack {
                        Image(systemName: "person.crop.circle")
                            .font(.title3)
                            .foregroundColor(.blue)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Recipient")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Text(selectedContact)
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .foregroundColor(.primary)
                        }

                        Spacer()

                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .cornerRadius(10)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(Color(uiColor: .separator), lineWidth: 1)
                    )
                }
            }
            .padding(16)
            .navigationTitle("shhhhhh")
            .navigationBarTitleDisplayMode(.inline)
            .overlay(alignment: .top) {
                if let banner = copiedBannerText {
                    Text(banner)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial)
                        .cornerRadius(20)
                        .shadow(radius: 4)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .padding(.top, 8)
                }
            }
            .sheet(isPresented: $showContactSheet) {
                NavigationStack {
                    VStack(spacing: 20) {
                        Text("Contact Selection Sheet (Piece 2)")
                            .font(.headline)
                        Text("Selected: \(selectedContact)")
                            .foregroundColor(.secondary)
                        Button("Close") {
                            showContactSheet = false
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .navigationTitle("Contacts")
                    .navigationBarTitleDisplayMode(.inline)
                }
            }
        }
    }

    // MARK: - Actions

    private func setProgrammaticText(_ newValue: String) {
        if text != newValue {
            programmaticTextTarget = newValue
            text = newValue
        } else {
            programmaticTextTarget = nil
        }
    }

    private func handleClear() {
        programmaticTextTarget = nil
        text = ""
        encryptState = .encrypt
        decryptState = .decrypt
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private func handleCopyVerbatim() {
        UIPasteboard.general.string = text
        showCopiedFeedback("Copied full text")
    }

    private func handleTextChange(_ newValue: String) {
        if let target = programmaticTextTarget {
            programmaticTextTarget = nil
            if newValue == target {
                return
            }
        }
        // Revert morphing buttons whenever text is edited
        if encryptState == .copyCiphertext {
            encryptState = .encrypt
        }
        if decryptState == .copySecret {
            decryptState = .decrypt
        }
    }

    private func handleEncryptTap() {
        if encryptState == .encrypt {
            // Placeholder: Encrypts text in-place into sample Base64URL ciphertext
            let sampleCiphertext = "xK89_mZ2Q1pA0-BvL81kLz0P82kL9xK89mZ2Q1pA0-BvL81kLz0P82kL9xK89mZ2Q1pA0-BvL81kLz0P82kL9"
            setProgrammaticText(sampleCiphertext)
            encryptState = .copyCiphertext
            decryptState = .decrypt
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        } else {
            // Copy Ciphertext
            UIPasteboard.general.string = text
            showCopiedFeedback("Copied ciphertext")
        }
    }

    private func handleDecryptTap() {
        if decryptState == .decrypt {
            // Placeholder: Decrypts in-place and morphs button
            let samplePlaintext = "Hey honey, here is the password: BankPassword123! let me know when done"
            setProgrammaticText(samplePlaintext)
            decryptState = .copySecret
            encryptState = .encrypt
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        } else {
            // Copy Secret exclusively (1-tap)
            UIPasteboard.general.string = "BankPassword123!"
            showCopiedFeedback("Copied secret: BankPassword123!")
        }
    }

    private func handleDecryptLongPress() {
        guard decryptState == .copySecret else { return }
        UIPasteboard.general.string = text
        showCopiedFeedback("Copied full message")
    }

    private func showCopiedFeedback(_ message: String) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        withAnimation {
            copiedBannerText = message
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            withAnimation {
                if copiedBannerText == message {
                    copiedBannerText = nil
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
