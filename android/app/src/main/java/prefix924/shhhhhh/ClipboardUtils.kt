package prefix924.shhhhhh

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import prefix924.shhhhhh.crypto.CryptoKeyPair

internal fun calculatePasteResult(
  currentValue: TextFieldValue,
  pasteText: String,
  isFocused: Boolean,
): TextFieldValue {
  val currentText = currentValue.text
  val (start, end) =
    if (isFocused) {
      val min = currentValue.selection.min.coerceIn(0, currentText.length)
      val max = currentValue.selection.max.coerceIn(0, currentText.length)
      min to max
    } else {
      currentText.length to currentText.length
    }
  val newText = currentText.replaceRange(start, end, pasteText)
  val newCursorPos = start + pasteText.length
  return TextFieldValue(text = newText, selection = TextRange(newCursorPos))
}

internal fun getClipboardText(context: Context, clipboardManager: ClipboardManager): String? {
  val description = clipboardManager.primaryClipDescription ?: return null
  val hasText =
    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
      description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) ||
      description.hasMimeType("text/*")
  if (!hasText) return null

  val clip = clipboardManager.primaryClip ?: return null
  if (clip.itemCount == 0) return null

  val item = clip.getItemAt(0)
  val itemText = item.text?.toString() ?: item.coerceToText(context)?.toString()
  return if (!itemText.isNullOrEmpty()) itemText else null
}

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
