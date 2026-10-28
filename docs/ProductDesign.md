# Product Design Document: shhhhhh

## 1. Overview & Vision
`shhhhhh` is a lightweight, consumer-focused encryption utility for iOS and Android designed to defeat low-effort surveillance and eavesdropping over untrusted communication channels (e.g., SMS, MMS, unencrypted messaging apps, cellular network infrastructure).

The application allows two parties (such as partners, spouses, or close contacts) to easily encrypt short, sensitive messages (e.g., banking credentials, passwords, private personal info) on one device, transmit the ciphertext via any third-party messaging channel (e.g., SMS), and paste the ciphertext into the app on the recipient's device to decrypt it.

### Core Principles
- **Over-the-wire privacy**: Focus on defeating passive/low-effort surveillance by intermediaries (cellular carriers, ISPs, network observers).
- **Zero code-sharing, 100% native**: Independent, idiomatic native implementations (Swift/SwiftUI on iOS; Kotlin/Jetpack Compose on Android) that look and behave identically.
- **Simplicity first**: Consumer-grade ease of use with zero friction—no complicated cryptographic key ceremonies or confusing jargon.
- **Ephemeral & Paste-oriented**: Frictionless clipboard workflows for encrypting outgoing text and decrypting incoming ciphertext. Encryption-at-rest is explicitly out of scope for this tier.

---

## 2. Platform Architecture & Tech Stack
- **Supported Platforms**:
  - **iOS**: Swift / SwiftUI (Bundle ID: `prefix924.shhhhhh`, Target: iOS 16+)
  - **Android**: Kotlin / Jetpack Compose (Package: `prefix924.shhhhhh`, `minSdkVersion: 37` / Android 17.0)
- **Code Sharing**: None (completely independent native implementations)
- **Repository Structure**:
  - `android/`: Native Android project (Gradle, Kotlin, Jetpack Compose)
  - `ios/shhhhhh/`: Native iOS project (`shhhhhh.xcodeproj`, SwiftUI)
  - `docs/`: Product specifications, architecture, and design documents
- **Cross-Platform Parity**: Identical UX flows, visual styling, cryptographic algorithms, and serialization formats to ensure complete cross-platform interoperability.

---

## 3. Core Purpose & Target Audience
- **Target Audience**: Everyday consumers who want a simple, frictionless way to share sensitive information (e.g., credentials, account numbers, private details) with a trusted contact over standard text messaging.
- **Primary Use Case**:
  1. Sender opens `shhhhhh`, types sensitive message (e.g., "Bank password is ...").
  2. Sender selects contact, taps **Encrypt**, and taps **Copy**.
  3. Sender sends ciphertext over standard text message (SMS).
  4. Recipient receives SMS, double-taps to copy the ciphertext blob, opens `shhhhhh`.
  5. Recipient pastes text into the text box and taps **Decrypt** to reveal original plaintext in-place.

---

## 4. Key Features & Functional Requirements
- **First-Launch Onboarding & Interactive Entropy Collection**:
  - Displayed exclusively on first app launch (before any keys exist).
  - The user cannot proceed past this screen until their cryptographic key pair is generated or restored.
  - **Restore Option**:
    - A secondary option ("Restore from Backup") allows users migrating to a new device to paste an existing Paper Backup bundle, restoring their private key, public key, and contact list immediately without generating new keys.
  - **Interactive Entropy Canvas**:
    - Prompt asks the user to randomly draw, scribble, or tap in a designated canvas area on screen.
    - Captures user entropy: touch coordinates $(x, y)$, high-resolution timestamps, and gesture vectors.
    - A visual progress indicator (e.g., ring or progress bar) fills as user entropy is harvested.
  - **Key Generation**:
    - User entropy is cryptographically combined (hashed with SHA-256) with the system's cryptographically secure random number generator (CSPRNG).
    - While the key pair is generating, an idiomatic progress spinner (SwiftUI `ProgressView` / Compose `CircularProgressIndicator`) is displayed.
    - Private key is securely saved to native storage (iOS Keychain / Android Keystore).
    - Public key (32 bytes raw, 43 characters Base64URL) is derived.
  - **Completion**:
    - Upon completion, the screen unlocks with a "Get Started" button (or smooth transition) navigating directly to the Unified Main Screen.

- **Key Pair & Identity**:
  - User's private key remains securely stored on-device.
  - Public key is displayed and exportable from the Contact Selection sheet.
  - User is always available as a contact ("Myself") using the local public key to encrypt messages to self.

- **Unified Main Screen**:
  - Single multi-line text box for both input and in-place output.
  - **Encrypt / Morphing Action Button**:
    - Pre-encryption state: Displays as **`[Encrypt]`** (vivid bright blue matching Decrypt button luminance, with a locked padlock icon; identical text size to Decrypt). Encrypts text in-place with the selected contact's public key and resets the Decrypt button to **`[Decrypt]`**.
    - Post-encryption state: Morphs in-place into **`[Copy]`** with a Copy icon (same physical dimensions, coordinates, and zero layout shift).
      - **Tap**: Instantly copies the resulting ciphertext to the clipboard (1 click).
      - **Reversion**: As soon as the text inside the text box is edited, typed into, replaced, or **Decrypt** is clicked, the button reverts to **`[Encrypt]`**.
  - **Decrypt / Morphing Action Button**:
    - Pre-decryption state: Displays as **`[Decrypt]`** (vivid forest green, with an unlocked padlock icon; identical text size to Encrypt). Decrypts text in-place using device's private key and ephemeral key in payload, and resets the Encrypt button to **`[Encrypt]`**.
    - Post-decryption state: Morphs in-place into **`[Copy Secret]`** with a Copy icon (same physical dimensions, coordinates, and zero layout shift).
      - **Tap (Single Secret)**: Instantly copies exclusively the decrypted secret to the clipboard (1 click).
      - **Tap (Multiple Secrets)**: Opens an overlay sheet/picker to select which decrypted secret to copy (2-step).
      - **Long-Press**: Copies the entire contents of the text box (the decrypted secret and all surrounding conversational text).
      - **Reversion**: As soon as the text inside the text box is edited, typed into, replaced, or **Encrypt** is clicked, the button reverts to **`[Decrypt]`**.
  - **Clear Button**: A dedicated one-tap button (positioned on the leading side of the utility bar) that immediately wipes all text from the box, resets the Encrypt/Decrypt buttons back to their default states, and dismisses any active error overlays.
  - **Paste Button**: A dedicated one-tap button positioned in the middle of the utility bar (between Clear on the left and Copy on the right) that inserts whatever text is in the clipboard at the cursor position in the text box (or replaces actively selected text, or appends to the end of existing text if unfocused) without clearing the text box contents. If the clipboard is empty, displays a transient feedback message. Pasting dismisses any active error overlays and reverts morphing action buttons to their default states.
  - **Copy Button**: Positioned on the trailing side of the utility bar; copies whatever text is currently in the text box directly to the clipboard verbatim (no auto-copy, no whitespace stripping, even if empty).
  - **Contact Selector Widget**: Positioned adjacent to action buttons, showing current recipient (defaults to "Myself"). Tapping it opens the Contact Selection sheet.
  - **Strict Zero Layout Shift**: Pre-decryption and pre-encryption UI elements (text box, Encrypt button, Decrypt button, Clear/Paste/Copy buttons, Contact Selector) MUST NOT move, resize, or jump when actions occur.

- **Contact Management & Public Key Sharing**:
  - Centralized in the **Contact Selection sheet**:
    - **My Public Key Section**: Displays user's public key (43-character unpadded Base64URL) with a **Copy My Public Key** action.
    - **Contact List**: Lists "Myself" and added contacts. Tapping a contact selects them and dismisses sheet.
    - **Add Contact Action**: Simple form to enter:
      - **Contact Name**: Display name for the contact.
      - **Public Key Input**: Supports direct typing, pasting, or tapping a dedicated "Paste" button.
      - **Smart Key Extraction**: Automatically scans pasted text for a valid 43-character Base64URL string (`[A-Za-z0-9_-]{43}`), extracting the key and ignoring any surrounding conversational preamble or postscript (e.g. *"Here's my key: `<43-char-key>`"*).
      - **Validation**: Verifies that the extracted key is exactly 43 Base64URL characters and decodes to a valid 32-byte X25519 public key before saving.
    - **Paper Backup & Migration Action**:
      - An **Export Backup** button that displays or copies a self-contained backup bundle (containing the user's private key, public key, and saved contacts list in Base64URL).
      - Allows manual copying/saving of identity keys and contacts for migration to a new device or physical paper safekeeping.
    - **Theme Selector**: Allows the user to select their preferred appearance mode: **System Default**, **Light**, or **Dark**.
  - Editing/deleting contacts deferred to future releases.

- **Message Encryption Flow**:
  1. User enters plaintext into the shared text box (or leaves it empty; encrypting empty text is explicitly allowed and yields a valid 80-character ciphertext for a 0-byte payload).
  2. User selects recipient in the Contact Selector widget (defaults to "Myself").
  3. User taps **Encrypt**.
  4. App generates an ephemeral X25519 key pair and a fresh 12-byte nonce.
  5. App performs ECDH with recipient's public key, derives a 256-bit key via HKDF-SHA256, and encrypts with ChaCha20-Poly1305.
  6. Payload (`ephemeralPubKey[32] + nonce[12] + ciphertext[N] + poly1305Tag[16]`) is encoded to unpadded Base64URL.
  7. Text box content is replaced in-place with the Base64URL ciphertext string.
  8. The `[Encrypt]` button morphs in-place into **`[Copy]`** (with a Copy icon), and resets the Decrypt button to **`[Decrypt]`**.
  9. User taps **`[Copy]`** (same physical position) to copy the ciphertext to the clipboard.

- **Message Decryption Flow**:
  1. User pastes message into the text box (which may include conversational text like *"Here is the bank password: `<blob1>` and wifi: `<blob2>`"*). If empty, tapping **Decrypt** displays the red `[Error]` overlay (no candidate found).
  2. User taps **Decrypt**.
  3. **Candidate Scanning & In-Place Replacement**:
     - The app scans the input text for all candidate Base64URL tokens of at least 80 characters matching `[A-Za-z0-9_-]{80,}`.
     - For each matching candidate string:
       - Attempts decryption using the device's private key (X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305).
       - If decryption succeeds: Substitutes that ciphertext substring in-place with the decrypted plaintext.
       - If decryption fails: Leaves that candidate substring unchanged as ciphertext.
  4. **Decrypted Plaintext Copy Affordances**:
     - **Inline Tappable Spans**: Decrypted substrings inside the text box are styled with an interactive highlight and can be tapped directly to copy that specific plaintext to the clipboard.
     - **Morphing Button Primary Copy**:
       - The `[Decrypt]` button morphs directly into `[Copy Secret]`.
       - Because the user's thumb just pressed `[Decrypt]`, a second tap in the exact same spot immediately copies the decrypted secret (1-tap).
       - If multiple secrets are decrypted, tapping opens a clean selection picker/sheet (2-step).
       - Long-pressing `[Copy Secret]` copies the full text box content (including conversational text).
       - Editing text in the box reverts the button to `[Decrypt]`.
  5. **Decryption Failure & Error Overlay**:
     - If decryption fails for all candidate strings (or if no $\ge 80$-character Base64URL string is present):
       - A red **`[Error]`** button is displayed as an overlay centered horizontally and vertically inside the text box (Z-stack overlay, causing zero layout shift).
       - Triggers an error haptic buzz upon appearance.
       - Tapping outside the button elsewhere in the text box immediately dismisses the error button and restores text interaction.
       - Typing, deleting, or pasting into the text box automatically dismisses the error button.
       - Tapping the red **`[Error]`** button dismisses the button and presents a native modal system **Alert** dialog with the specific reason:
         - *No candidates found*: Explains that no Base64URL string of 80+ characters was found.
         - *Authentication failed*: Explains that ciphertext was found but could not be decrypted with the device's private key (e.g. intended for a different recipient or corrupted).

---

## 5. UI/UX Wireframes & Screen States

### Screen 1: First-Launch Onboarding & Entropy Collection
```text
+------------------------------------------+
|                 shhhhhh                  |
|                                          |
|          Welcome to shhhhhh              |
|                                          |
|  Private end-to-end encryption for your  |
|  text messages. To initialize your key,  |
|  scribble or tap randomly in the box:    |
|                                          |
|  +------------------------------------+  |
|  | ~ ~ . *  . *   ~  .  *  ~  .  *    |  |
|  |   .   *   ~  .  ~     *   .    ~   |  |
|  | *   ~   .    *    ~ .    *   .     |  |
|  |       [ Interactive Canvas ]       |  |
|  |   . *   ~  .   *    ~  .   *   ~   |  |
|  +------------------------------------+  |
|                                          |
|  Entropy Collection: [==========   ] 75% |
|                                          |
|  [     ⏳ Generating Key Pair...      ]  |
|  (Disabled until 100% entropy gathered)  |
|                                          |
|  --------------------------------------  |
|        [ Restore from Backup ]           |
+------------------------------------------+
```

---

### Screen 2: Unified Main Screen (Default / Idle State)
```text
+------------------------------------------+
| shhhhhh                                  |
+------------------------------------------+
|  +------------------------------------+  |
|  | Type message to encrypt or paste   |  |
|  | text containing ciphertext here... |  |
|  |                                    |  |
|  |                                    |  |
|  |                                    |  |
|  |                                    |  |
|  | [Clear]       [Paste]       [Copy] |  |
|  +------------------------------------+  |
|                                          |
|  +------------------+  +--------------+  |
|  |    [Encrypt]     |  |  [Decrypt]   |  |
|  +------------------+  +--------------+  |
|                                          |
|  +------------------------------------+  |
|  | Recipient: [ Myself (This Device) ▾]  |
|  +------------------------------------+  |
+------------------------------------------+
```

---

### Screen 3: Unified Main Screen (Post-Encryption State)
*Note: Exact same layout coordinates; `[Encrypt]` morphs into `[Copy]` with zero layout shift.*
```text
+------------------------------------------+
| shhhhhh                                  |
+------------------------------------------+
|  +------------------------------------+  |
|  | xK89_mZ2Q1pA0-BvL81kLz0P82kL9_...  |  |
|  | (Base64URL ciphertext string)      |  |
|  |                                    |  |
|  |                                    |  |
|  |                                    |  |
|  |                                    |  |
|  | [Clear]       [Paste]       [Copy] |  |
|  +------------------------------------+  |
|                                          |
|  +------------------+  +--------------+  |
|  |      [Copy]      |  |  [Decrypt]   |  |  <- Tap to copy ciphertext
|  +------------------+  +--------------+  |
|                                          |
|  +------------------------------------+  |
|  | Recipient: [ Myself (This Device) ▾]  |
|  +------------------------------------+  |
+------------------------------------------+
```

---

### Screen 4: Unified Main Screen (Post-Decryption State)
*Note: Decrypted text replaced in-place; inline highlighted span is tappable; `[Decrypt]` morphs into `[Copy Secret]`.*
```text
+------------------------------------------+
| shhhhhh                                  |
+------------------------------------------+
|  +------------------------------------+  |
|  | Hey honey, here is the password:   |  |
|  | +--------------------------------+ |  |
|  | | 🔒 BankPassword123!             | |  |  <- Inline Tappable Span
|  | +--------------------------------+ |  |     (Tap to copy secret)
|  | let me know when you are done!     |  |
|  | [Clear]       [Paste]       [Copy] |  |
|  +------------------------------------+  |
|                                          |
|  +------------------+  +--------------+  |
|  |    [Encrypt]     |  | [Copy Secret]|  |  <- Tap: Copy secret (1-tap)
|  +------------------+  +--------------+  |     Long-press: Copy all text
|                                          |
|  +------------------------------------+  |
|  | Recipient: [ Myself (This Device) ▾]  |
|  +------------------------------------+  |
+------------------------------------------+
```

---

### Screen 5: Unified Main Screen (Decryption Failure / Error Overlay)
*Note: Centered red button overlay inside text box; zero layout shift; tap outside dismisses.*
```text
+------------------------------------------+
| shhhhhh                                  |
+------------------------------------------+
|  +------------------------------------+  |
|  | Random chatter without valid       |  |
|  | ciphertext strings...              |  |
|  |          +----------------+        |  |
|  |          |   ⚠️  Error    |        |  |  <- Red overlay button
|  |          +----------------+        |  |     (Tap: Opens Alert dialog;
|  |                                    |  |      Tap outside: Dismisses)
|  | [Clear]       [Paste]       [Copy] |  |
|  +------------------------------------+  |
|                                          |
|  +------------------+  +--------------+  |
|  |    [Encrypt]     |  |  [Decrypt]   |  |  <- Fixed, unmoving position
|  +------------------+  +--------------+  |
|                                          |
|  +------------------------------------+  |
|  | Recipient: [ Myself (This Device) ▾]  |
|  +------------------------------------+  |
+------------------------------------------+
```

---

### Screen 6: Contact Selection & Management Sheet (Modal)
*Opened by tapping the Contact Selector widget.*
```text
+------------------------------------------+
|                [======]                  |
|          Contacts & Settings             |
+------------------------------------------+
| MY PUBLIC KEY                            |
| 3kL9_xK89mZ2Q1pA0BvL81kLz0P82kL9xK89mZ2Q |
| [📋 Copy My Public Key]                  |
+------------------------------------------+
| CONTACTS                                 |
|  ✓ Myself (This Device)                  |
|    Wife                                  |
|    Accountant                            |
+------------------------------------------+
|  [ + Add New Contact ]                   |
+------------------------------------------+
| BACKUP & MIGRATION                       |
|  [ Export Paper Backup ]                 |
+------------------------------------------+
| APPEARANCE                               |
|  Theme: [ System Default | Light | Dark ]|
+------------------------------------------+
```

---

### Screen 7: Add Contact Modal Form
```text
+------------------------------------------+
|                [======]                  |
|            Add New Contact               |
+------------------------------------------+
| Contact Name:                            |
| [ Wife                                 ] |
|                                          |
| Public Key:                              |
| [ Pasted text or key...                ] |
| [ 📋 Paste from Clipboard ]              |
|                                          |
| (Smart extractor automatically detects   |
|  and extracts the 43-character key)      |
|                                          |
|  +------------------+  +--------------+  |
|  |     [Cancel]     |  |    [Save]    |  |
|  +------------------+  +--------------+  |
+------------------------------------------+
```

---

## 6. Technical & Security Specifications
- **Design System & Visual Consistency**:
  - **Shared Palette & Styling**: Identical color tokens, font hierarchy, corner radii, and padding across both platforms.
  - **Themes**: Support for **System Default**, **Light**, and **Dark** modes across both iOS (SwiftUI `preferredColorScheme`) and Android (Jetpack Compose `MaterialTheme` color schemes).
- **Threat Model**: Passive network eavesdropping, cellular carrier inspection, and wire logging intermediaries.
- **Cryptographic Suite**: **X25519 + ChaCha20-Poly1305 (Sealed Box)**:
  - **Key Agreement**: Curve25519 (X25519). Public keys are 32 bytes raw.
  - **Symmetric Encryption**: ChaCha20-Poly1305 authenticated encryption with an explicit 12-byte nonce (IETF RFC 8439) and 16-byte Poly1305 MAC tag.
  - **Key Derivation**: HKDF-SHA256 derives the 256-bit symmetric key from ECDH shared secret + ephemeral public key.
  - **Native Platform Primitives**:
    - iOS: Apple `CryptoKit` (`Curve25519.KeyAgreement`, `ChaChaPoly`, `HKDF`).
    - Android: Native `java.security` XEC / XDH support (`minSdkVersion: 33` guarantees native availability).
- **Decryption Model**: Anonymous / Sealed Box. Recipient decrypts using local private key and bundled ephemeral public key. No contact selection or sender authentication required for decryption.
- **Transport Wire Encoding & Clipboard Tolerance**:
  - **Encoding**: Unpadded Base64URL (`[A-Za-z0-9-_]`).
  - **SMS Character Set Preservation (GSM 7-bit)**: All Base64URL characters reside in the basic GSM 7-bit alphabet, guaranteeing standard 160-character SMS limits.
  - **In-Place Substring Decoding**: Automatic regex scanning (`[A-Za-z0-9_-]{80,}`) decodes all valid matching ciphertext strings in-place while leaving non-matching or undecryptable substrings intact.

---

## 7. Decisions & Open Questions
- **[Decided] Core Vision**: Consumer-focused text encryptor/decryptor over untrusted messaging (SMS/wire) using clipboard copy/paste.
- **[Decided] Scope of At-Rest Protection**: Encryption at rest on device is not required.
- **[Decided] Key Management Model**: Asymmetric public/private key pairs. Each user generates keys using device entropy. Users exchange public keys out-of-band and maintain an in-app Contact List.
- **[Decided] Decryption & Sender Verification**: Anonymous / Sealed Box model. Ephemeral sender key is bundled with the ciphertext, allowing immediate decryption using recipient's private key without contact selection.
- **[Decided] Screen Layout & Navigation**: Unified single-screen architecture. Separate Encrypt and Decrypt buttons. Contact Selector widget adjacent to buttons. Empty contacts default to "Myself". Contact selection sheet/dialog displays even if only one contact exists. Editing/deleting contacts deferred to future releases.
- **[Decided] Text Box & Clipboard UX**: Single shared multi-line text box with in-place text replacement. Dedicated utility bar with 'Clear', 'Paste', and 'Copy' buttons. Dedicated "Paste" button inserts clipboard text at the cursor position (or replaces actively selected text, or appends to end if unfocused) without clearing existing contents; displays transient feedback if clipboard is empty. Dedicated "Copy" button that verbatim copies whatever text is in the box (even empty, no trimming). Dedicated "Clear" button that instantly wipes the text box and resets state. No automatic clipboard overwriting.
- **[Decided] Zero Layout Shift & Post-Action Copying**:
  - `[Encrypt]` morphs in-place into `[Copy]` (with Copy icon).
  - `[Decrypt]` morphs in-place into `[Copy Secret]` (with Copy icon). Tap copies isolated secret (or opens picker for multiple secrets); long-press copies entire text box.
  - Clicking `Encrypt` resets the Decrypt button to `[Decrypt]`, and clicking `Decrypt` resets the Encrypt button to `[Encrypt]`.
  - Text box supports Inline Tappable Spans for decrypted secrets.
  - Editing text reverts buttons to `[Encrypt]` and `[Decrypt]`.
- **[Decided] Error Handling**: Centered red `[Error]` button overlay inside text box (zero layout shift). Dismisses on tap-outside or typing. Tapping the button shows a native modal Alert dialog with the detailed error.
- **[Decided] First-Launch Experience**: Welcome screen with interactive touch/draw entropy collection canvas. Spinner during key generation. User cannot proceed until keys are generated.
- **[Decided] Contact Management & Key Sharing**: Centralized inside the Contact Selection sheet/UI. Smart key extraction parses 43-character Base64URL public keys from pasted text, ignoring conversational chatter.
- **[Decided] Cryptographic Suite**: X25519 + HKDF-SHA256 + ChaCha20-Poly1305 with explicit 12-byte nonce (61 bytes minimum overhead). Supported natively on iOS (`CryptoKit`) and Android (`minSdkVersion: 37`).
- **[Decided] Wire Encoding Scheme**: Unpadded Base64URL (`A-Z`, `a-z`, `0-9`, `-`, `_`). All characters in basic GSM 7-bit alphabet.
- **[Decided] In-Place Decryption**: All candidate Base64URL strings $\ge 80$ chars are decoded in-place. Failed candidates remain as ciphertext. Encrypting empty text is allowed.
- **[Decided] Key & Contact Migration**: Paper Backup model. Users can export their keys and contacts as a self-contained bundle for physical safekeeping or manual migration to a new device. First-launch onboarding includes a "Restore from Backup" option.
- **[Decided] Theming**: Custom theme selector supporting System Default, Light, and Dark modes.
- **[Decided] Repository Structure**: Top-level `android/` and `ios/shhhhhh/` folders containing independent native projects with bundle/package ID `prefix924.shhhhhh`.












