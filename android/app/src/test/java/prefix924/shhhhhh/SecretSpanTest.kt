package prefix924.shhhhhh

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SecretSpanTest : FunSpec({

    test("findSecretSpans returns empty list for empty text or empty secrets") {
        findSecretSpans("", emptyList()) shouldBe emptyList()
        findSecretSpans("Hello world", emptyList()) shouldBe emptyList()
        findSecretSpans("", listOf("secret")) shouldBe emptyList()
    }

    test("findSecretSpans finds single secret in text") {
        val text = "Your secret is: BankPassword123! Keep it safe."
        val secret = "BankPassword123!"
        val spans = findSecretSpans(text, listOf(secret))

        spans.size shouldBe 1
        spans[0].secret shouldBe secret
        spans[0].range shouldBe (16 until 32)
        text.substring(spans[0].range) shouldBe secret
    }

    test("findSecretSpans finds multiple secrets in conversational text") {
        val text = "Wifi is HomeWifi123 and alarm code is 987654. Thanks!"
        val secret1 = "HomeWifi123"
        val secret2 = "987654"
        val spans = findSecretSpans(text, listOf(secret1, secret2))

        spans.size shouldBe 2
        spans[0].secret shouldBe secret1
        text.substring(spans[0].range) shouldBe secret1

        spans[1].secret shouldBe secret2
        text.substring(spans[1].range) shouldBe secret2

        (spans[0].range.last < spans[1].range.first) shouldBe true
    }

    test("findSecretSpans handles duplicate identical secrets in separate occurrences") {
        val text = "First token: ABCDEF and second token: ABCDEF. Done."
        val secret = "ABCDEF"
        val spans = findSecretSpans(text, listOf(secret, secret))

        spans.size shouldBe 2
        spans[0].secret shouldBe secret
        spans[1].secret shouldBe secret
        spans[0].range shouldBe (13 until 19)
        spans[1].range shouldBe (38 until 44)
        text.substring(spans[0].range) shouldBe secret
        text.substring(spans[1].range) shouldBe secret
    }

    test("findSecretSpans uses explicit ranges when valid") {
        val text = "Prefix SecretOne Middle SecretTwo Suffix"
        val secret1 = "SecretOne"
        val secret2 = "SecretTwo"
        val range1 = 7 until 16
        val range2 = 24 until 33

        val spans = findSecretSpans(text, listOf(secret1, secret2), listOf(range1, range2))

        spans.size shouldBe 2
        spans[0].range shouldBe range1
        spans[0].secret shouldBe secret1
        spans[1].range shouldBe range2
        spans[1].secret shouldBe secret2
    }

    test("findSecretSpans falls back to sequential search if explicit ranges do not match") {
        val text = "Text has SecretOne here"
        val secret = "SecretOne"
        val invalidRange = 0 until 5 // Doesn't match "SecretOne"

        val spans = findSecretSpans(text, listOf(secret), listOf(invalidRange))

        spans.size shouldBe 1
        spans[0].secret shouldBe secret
        text.substring(spans[0].range) shouldBe secret
    }

    test("resolveCopySecretAction returns None when empty") {
        resolveCopySecretAction(emptyList()) shouldBe CopySecretAction.None
    }

    test("resolveCopySecretAction returns CopySingle for 1 secret") {
        resolveCopySecretAction(listOf("single-secret")) shouldBe CopySecretAction.CopySingle("single-secret")
    }

    test("resolveCopySecretAction returns ShowPicker for 2 or more secrets") {
        val secrets = listOf("secret1", "secret2", "secret3")
        resolveCopySecretAction(secrets) shouldBe CopySecretAction.ShowPicker(secrets)
    }

    test("buildSecretAnnotatedString applies span styles to matching ranges") {
        val text = "Hey, SecretPass is the key"
        val spans = listOf(SecretSpan(secret = "SecretPass", range = 5 until 15))
        var tappedSecret: String? = null

        val annotated = buildSecretAnnotatedString(
            text = text,
            spans = spans,
            highlightColor = Color(0x992196F3),
            textColor = Color.White,
            onSecretClick = { tappedSecret = it }
        )

        annotated.text shouldBe text
        annotated.spanStyles.size shouldBe 1
        annotated.spanStyles[0].start shouldBe 5
        annotated.spanStyles[0].end shouldBe 15
        annotated.spanStyles[0].item.background shouldBe Color(0x992196F3)
    }

    test("SecretHighlightVisualTransformation transforms text with styling when spans present") {
        val originalText = "Prefix 123456 Suffix"
        val spans = listOf(SecretSpan(secret = "123456", range = 7 until 13))
        val transformation = SecretHighlightVisualTransformation(
            spans = spans,
            highlightColor = Color(0x992196F3),
            textColor = Color.White
        )

        val transformed = transformation.filter(androidx.compose.ui.text.AnnotatedString(originalText))
        transformed.text.text shouldBe originalText
        transformed.text.spanStyles.size shouldBe 1
        transformed.text.spanStyles[0].start shouldBe 7
        transformed.text.spanStyles[0].end shouldBe 13
    }

    test("SecretHighlightVisualTransformation returns identity with empty spans") {
        val originalText = "Just normal text"
        val transformation = SecretHighlightVisualTransformation(
            spans = emptyList(),
            highlightColor = Color(0x992196F3),
            textColor = Color.White
        )

        val transformed = transformation.filter(androidx.compose.ui.text.AnnotatedString(originalText))
        transformed.text.text shouldBe originalText
        transformed.text.spanStyles.size shouldBe 0
    }

    test("findSecretSpans handles unicode and emoji secrets") {
        val text = "Greeting: 🔑 SecretSafe-🔒 and then some."
        val secret = "🔑 SecretSafe-🔒"
        val spans = findSecretSpans(text, listOf(secret))

        spans.size shouldBe 1
        spans[0].secret shouldBe secret
        text.substring(spans[0].range) shouldBe secret
    }
})
