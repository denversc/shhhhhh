package prefix924.shhhhhh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

data class SecretSpan(
    val secret: String,
    val range: IntRange
)

sealed interface CopySecretAction {
    data class CopySingle(val secret: String) : CopySecretAction
    data class ShowPicker(val secrets: List<String>) : CopySecretAction
    data object None : CopySecretAction
}

fun resolveCopySecretAction(secrets: List<String>): CopySecretAction {
    return when {
        secrets.isEmpty() -> CopySecretAction.None
        secrets.size == 1 -> CopySecretAction.CopySingle(secrets[0])
        else -> CopySecretAction.ShowPicker(secrets)
    }
}

fun findSecretSpans(
    text: String,
    secrets: List<String>,
    explicitRanges: List<IntRange>? = null
): List<SecretSpan> {
    if (text.isEmpty() || secrets.isEmpty()) return emptyList()

    if (explicitRanges != null && explicitRanges.size == secrets.size) {
        val spans = mutableListOf<SecretSpan>()
        var valid = true
        for (i in secrets.indices) {
            val range = explicitRanges[i]
            val secret = secrets[i]
            if (range.first >= 0 && range.last < text.length &&
                text.substring(range.first, range.last + 1) == secret
            ) {
                spans.add(SecretSpan(secret = secret, range = range))
            } else {
                valid = false
                break
            }
        }
        if (valid) return spans
    }

    val spans = mutableListOf<SecretSpan>()
    var currentIndex = 0
    for (secret in secrets) {
        if (secret.isEmpty()) continue
        val index = text.indexOf(secret, startIndex = currentIndex)
        if (index != -1) {
            val range = index until (index + secret.length)
            spans.add(SecretSpan(secret = secret, range = range))
            currentIndex = range.last + 1
        }
    }
    return spans
}

fun buildSecretAnnotatedString(
    text: String,
    spans: List<SecretSpan>,
    highlightColor: Color,
    textColor: Color,
    onSecretClick: ((String) -> Unit)? = null
): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    for (span in spans) {
        val start = span.range.first.coerceIn(0, text.length)
        val end = (span.range.last + 1).coerceIn(0, text.length)
        if (start < end) {
            builder.addStyle(
                SpanStyle(
                    background = highlightColor,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold
                ),
                start,
                end
            )
            if (onSecretClick != null) {
                builder.addLink(
                    LinkAnnotation.Clickable(
                        tag = "SECRET",
                        linkInteractionListener = { onSecretClick(span.secret) }
                    ),
                    start,
                    end
                )
            }
        }
    }
    return builder.toAnnotatedString()
}

class SecretHighlightVisualTransformation(
    private val spans: List<SecretSpan>,
    private val highlightColor: Color,
    private val textColor: Color,
    private val onSecretClick: ((String) -> Unit)? = null
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val annotated = buildSecretAnnotatedString(
            text = text.text,
            spans = spans,
            highlightColor = highlightColor,
            textColor = textColor,
            onSecretClick = onSecretClick
        )
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
