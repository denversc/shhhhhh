package prefix924.shhhhhh

import android.content.ClipData
import android.content.ClipboardManager
import prefix924.shhhhhh.crypto.CryptoEngine
import prefix924.shhhhhh.crypto.CryptoKeyPair

/**
 * Copies the raw Base64URL public key from [keyPair] to the clipboard without extra text or
 * template wrappers.
 *
 * @param clipboardManager System clipboard manager.
 * @param keyPair The user's key pair whose public key should be copied.
 * @param onFeedback Callback invoked with the feedback message to display to the user.
 * @return The raw 43-character Base64URL string copied to the clipboard.
 */
internal fun copyPublicKeyToClipboard(
  clipboardManager: ClipboardManager,
  keyPair: CryptoKeyPair,
  onFeedback: (String) -> Unit = {},
): String =
  copyPublicKeyToClipboard(
    clipboardManager = clipboardManager,
    publicKeyBase64Url = keyPair.publicKeyBase64Url,
    onFeedback = onFeedback,
  )

/**
 * Copies the raw Base64URL public key string to the clipboard without extra text or template
 * wrappers.
 *
 * @param clipboardManager System clipboard manager.
 * @param publicKeyBase64Url The raw 43-character Base64URL public key string.
 * @param onFeedback Callback invoked with the feedback message to display to the user.
 * @return The raw Base64URL string copied to the clipboard.
 */
internal fun copyPublicKeyToClipboard(
  clipboardManager: ClipboardManager,
  publicKeyBase64Url: String,
  onFeedback: (String) -> Unit = {},
): String {
  val clip = ClipData.newPlainText("shhhhhh_public_key", publicKeyBase64Url)
  clipboardManager.setPrimaryClip(clip)
  onFeedback("Copied public key")
  return publicKeyBase64Url
}

internal val PUBLIC_KEY_REGEX = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])""")

/**
 * Validates whether the given string is a valid 43-character Base64URL-encoded 32-byte X25519
 * public key.
 *
 * @param publicKeyBase64Url The candidate string to validate.
 * @return True if [publicKeyBase64Url] is exactly 43 Base64URL characters and decodes to a valid
 *   X25519 public key.
 */
internal fun validatePublicKey(publicKeyBase64Url: String): Boolean {
  if (publicKeyBase64Url.length != 43) return false
  return try {
    val rawBytes = CryptoEngine.decodePublicKeyBase64Url(publicKeyBase64Url)
    if (rawBytes.size != CryptoEngine.KEY_SIZE_BYTES) return false
    val canonical = CryptoEngine.encodePublicKeyBase64Url(rawBytes)
    if (canonical != publicKeyBase64Url) return false
    CryptoEngine.rawBytesToPublicKey(rawBytes)
    true
  } catch (_: Exception) {
    false
  }
}

/**
 * Scans [input] text to find and extract a valid 43-character Base64URL X25519 public key, ignoring
 * surrounding conversational text, punctuation, or whitespace.
 *
 * @param input Raw text entered or pasted by the user.
 * @return The first valid 43-character Base64URL public key found, or null if none detected.
 */
internal fun extractPublicKey(input: String): String? {
  if (input.isBlank()) return null
  return PUBLIC_KEY_REGEX.findAll(input).map { it.value }.firstOrNull { validatePublicKey(it) }
}
