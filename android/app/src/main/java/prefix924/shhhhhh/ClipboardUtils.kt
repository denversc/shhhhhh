package prefix924.shhhhhh

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

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
