//
//  ContactSelectionSheet.swift
//  shhhhhh
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import SwiftUI
import UIKit

/// Sheet presenting user's public key, contact selection list, and affordance to add contacts.
public struct ContactSelectionSheet: View {
  public let storage: AppStorage
  public let userKeyPair: CryptoKeyPair?
  public let selectedContact: String
  public let selectedContactRecord: ContactRecord?
  @Binding public var bannerText: String?
  public let onSelectContact: ((ContactRecord?) -> Void)?
  public let onCopyPublicKey: () -> Void
  public let onDismiss: () -> Void

  @State private var contacts: [ContactRecord] = []
  @State private var showAddContactSheet: Bool = false
  @State private var localBannerText: String? = nil
  @Environment(\.dismiss) private var dismiss

  public init(
    storage: AppStorage = KeychainAppStorage.shared,
    userKeyPair: CryptoKeyPair?,
    selectedContact: String = "Myself (This Device)",
    selectedContactRecord: ContactRecord? = nil,
    bannerText: Binding<String?> = .constant(nil),
    onSelectContact: ((ContactRecord?) -> Void)? = nil,
    onCopyPublicKey: @escaping () -> Void = {},
    onDismiss: @escaping () -> Void
  ) {
    self.storage = storage
    self.userKeyPair = userKeyPair
    self.selectedContact = selectedContact
    self.selectedContactRecord = selectedContactRecord
    self._bannerText = bannerText
    self.onSelectContact = onSelectContact
    self.onCopyPublicKey = onCopyPublicKey
    self.onDismiss = onDismiss
  }

  public init(
    userKeyPair: CryptoKeyPair?,
    selectedContact: String,
    bannerText: Binding<String?> = .constant(nil),
    onCopyPublicKey: @escaping () -> Void = {},
    onDismiss: @escaping () -> Void
  ) {
    self.init(
      storage: InMemoryAppStorage(),
      userKeyPair: userKeyPair,
      selectedContact: selectedContact,
      selectedContactRecord: nil,
      bannerText: bannerText,
      onSelectContact: nil,
      onCopyPublicKey: onCopyPublicKey,
      onDismiss: onDismiss
    )
  }

  private func handleCopyTap() {
    if let userKeyPair {
      copyPublicKeyToClipboard(userKeyPair)
    }
    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    onCopyPublicKey()
    withAnimation {
      localBannerText = "Copied public key"
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
      withAnimation {
        if localBannerText == "Copied public key" {
          localBannerText = nil
        }
      }
    }
  }

  public var body: some View {
    NavigationStack {
      List {
        // Section: MY PUBLIC KEY
        if let keyPair = userKeyPair {
          Section {
            VStack(alignment: .leading, spacing: 10) {
              Text(keyPair.publicKeyBase64Url)
                .font(.system(.subheadline, design: .monospaced))
                .foregroundColor(.primary)
                .textSelection(.enabled)
                .lineLimit(nil)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Color(uiColor: .secondarySystemBackground))
                .cornerRadius(10)
                .overlay(
                  RoundedRectangle(cornerRadius: 10)
                    .stroke(Color(uiColor: .separator), lineWidth: 1)
                )
                .accessibilityIdentifier("my_public_key_text")

              Button(action: handleCopyTap) {
                HStack(spacing: 8) {
                  Image(systemName: "doc.on.doc")
                  Text("Copy My Public Key")
                    .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(10)
              }
              .buttonStyle(.plain)
              .accessibilityIdentifier("copy_my_public_key_button")
            }
            .padding(.vertical, 4)
          } header: {
            Text("MY PUBLIC KEY")
              .font(.caption)
              .fontWeight(.semibold)
          }
        }

        // Section: CONTACTS
        Section {
          // Myself (This Device)
          Button(action: {
            onSelectContact?(nil)
            onDismiss()
            dismiss()
          }) {
            HStack {
              VStack(alignment: .leading, spacing: 2) {
                Text("Myself (This Device)")
                  .font(.body)
                  .foregroundColor(.primary)
              }

              Spacer()

              if selectedContactRecord == nil
                && (selectedContact == "Myself (This Device)" || selectedContact.isEmpty)
              {
                Image(systemName: "checkmark")
                  .foregroundColor(.blue)
                  .fontWeight(.semibold)
              }
            }
          }
          .accessibilityIdentifier("contact_item_myself")

          // Saved Contacts
          ForEach(contacts) { contact in
            Button(action: {
              onSelectContact?(contact)
              onDismiss()
              dismiss()
            }) {
              HStack {
                VStack(alignment: .leading, spacing: 2) {
                  Text(contact.name)
                    .font(.body)
                    .foregroundColor(.primary)
                }

                Spacer()

                if selectedContactRecord?.id == contact.id || selectedContact == contact.name {
                  Image(systemName: "checkmark")
                    .foregroundColor(.blue)
                    .fontWeight(.semibold)
                }
              }
            }
            .accessibilityIdentifier("contact_item_\(contact.id)")
          }

          // Add New Contact Button
          Button(action: {
            showAddContactSheet = true
          }) {
            HStack(spacing: 6) {
              Image(systemName: "plus.circle.fill")
                .foregroundColor(.blue)
              Text("Add New Contact")
                .fontWeight(.medium)
                .foregroundColor(.blue)
            }
          }
          .accessibilityIdentifier("add_new_contact_button")
        } header: {
          Text("CONTACTS")
            .font(.caption)
            .fontWeight(.semibold)
        }
      }
      .listStyle(.insetGrouped)
      .navigationTitle("Contacts & Settings")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Close") {
            onDismiss()
            dismiss()
          }
          .accessibilityIdentifier("sheet_close_button")
        }
      }
      .overlay(alignment: .top) {
        if let banner = bannerText ?? localBannerText {
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
            .accessibilityIdentifier("sheet_copied_banner")
        }
      }
      .sheet(isPresented: $showAddContactSheet) {
        AddContactView(
          storage: storage,
          onSave: { newContact in
            showAddContactSheet = false
            onSelectContact?(newContact)
            onDismiss()
            dismiss()
          },
          onCancel: {
            showAddContactSheet = false
          }
        )
      }
      .onAppear {
        contacts = storage.getContacts()
      }
    }
  }
}
