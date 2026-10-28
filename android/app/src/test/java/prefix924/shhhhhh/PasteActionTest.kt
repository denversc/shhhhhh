package prefix924.shhhhhh

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class PasteActionTest : FunSpec({

    test("calculatePasteResult when unfocused appends to empty text box and positions cursor at end") {
        val initial = TextFieldValue(text = "", selection = TextRange.Zero)
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = "Hello World",
            isFocused = false
        )

        result.text shouldBe "Hello World"
        result.selection shouldBe TextRange(11)
    }

    test("calculatePasteResult when unfocused appends to end of existing text without overwriting") {
        val initial = TextFieldValue(text = "Existing message: ", selection = TextRange.Zero)
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = "pasted content",
            isFocused = false
        )

        result.text shouldBe "Existing message: pasted content"
        result.selection shouldBe TextRange(32)
    }

    test("calculatePasteResult when unfocused ignores previous selection and appends to end") {
        val initial = TextFieldValue(text = "PrefixSuffix", selection = TextRange(0, 6))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = " - Extra",
            isFocused = false
        )

        result.text shouldBe "PrefixSuffix - Extra"
        result.selection shouldBe TextRange(20)
    }

    test("calculatePasteResult when focused inserts at cursor position at start") {
        val initial = TextFieldValue(text = "World", selection = TextRange(0))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = "Hello ",
            isFocused = true
        )

        result.text shouldBe "Hello World"
        result.selection shouldBe TextRange(6)
    }

    test("calculatePasteResult when focused inserts at cursor position in middle") {
        val initial = TextFieldValue(text = "HelloWorld", selection = TextRange(5))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = " Beautiful ",
            isFocused = true
        )

        result.text shouldBe "Hello Beautiful World"
        result.selection shouldBe TextRange(16)
    }

    test("calculatePasteResult when focused inserts at cursor position at end") {
        val initial = TextFieldValue(text = "Hello", selection = TextRange(5))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = " World!",
            isFocused = true
        )

        result.text shouldBe "Hello World!"
        result.selection shouldBe TextRange(12)
    }

    test("calculatePasteResult when focused replaces active selection range and positions cursor at end of pasted text") {
        val initial = TextFieldValue(text = "Hello REPLACE_ME World", selection = TextRange(6, 16))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = "Beautiful",
            isFocused = true
        )

        result.text shouldBe "Hello Beautiful World"
        result.selection shouldBe TextRange(15)
    }

    test("calculatePasteResult when focused replaces entire text when fully selected") {
        val initial = TextFieldValue(text = "Entire text to replace", selection = TextRange(0, 22))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = "Fresh text",
            isFocused = true
        )

        result.text shouldBe "Fresh text"
        result.selection shouldBe TextRange(10)
    }

    test("calculatePasteResult when focused handles reverse/inverted selection range correctly") {
        // Selection dragged backwards: start > end (16 down to 6)
        val initial = TextFieldValue(text = "Hello REPLACE_ME World", selection = TextRange(16, 6))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = "Wonderful",
            isFocused = true
        )

        result.text shouldBe "Hello Wonderful World"
        result.selection shouldBe TextRange(15)
    }

    test("calculatePasteResult clamps out-of-bounds selection safely") {
        val initial = TextFieldValue(text = "Short", selection = TextRange(50, 100))
        val result = calculatePasteResult(
            currentValue = initial,
            pasteText = "Added",
            isFocused = true
        )

        result.text shouldBe "ShortAdded"
        result.selection shouldBe TextRange(10)
    }

    test("getClipboardText returns null when primaryClipDescription is null") {
        val context = mockk<Context>()
        val clipboardManager = mockk<ClipboardManager>()
        every { clipboardManager.primaryClipDescription } returns null

        getClipboardText(context, clipboardManager) shouldBe null
    }

    test("getClipboardText returns null when clipboard contains non-text content (e.g. image)") {
        val context = mockk<Context>()
        val clipboardManager = mockk<ClipboardManager>()
        val description = mockk<ClipDescription>()

        every { description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns false
        every { description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) } returns false
        every { description.hasMimeType("text/*") } returns false
        every { clipboardManager.primaryClipDescription } returns description

        getClipboardText(context, clipboardManager) shouldBe null
    }

    test("getClipboardText returns null when primaryClip is null or itemCount is zero") {
        val context = mockk<Context>()
        val clipboardManager = mockk<ClipboardManager>()
        val description = mockk<ClipDescription>()

        every { description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns true
        every { clipboardManager.primaryClipDescription } returns description
        every { clipboardManager.primaryClip } returns null

        getClipboardText(context, clipboardManager) shouldBe null

        val emptyClip = mockk<ClipData>()
        every { emptyClip.itemCount } returns 0
        every { clipboardManager.primaryClip } returns emptyClip

        getClipboardText(context, clipboardManager) shouldBe null
    }

    test("getClipboardText returns null when clipboard text is empty") {
        val context = mockk<Context>()
        val clipboardManager = mockk<ClipboardManager>()
        val description = mockk<ClipDescription>()
        val clip = mockk<ClipData>()
        val item = mockk<ClipData.Item>()

        every { description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns true
        every { clipboardManager.primaryClipDescription } returns description
        every { clipboardManager.primaryClip } returns clip
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { item.text } returns ""
        every { item.coerceToText(context) } returns ""

        getClipboardText(context, clipboardManager) shouldBe null
    }

    test("getClipboardText returns text verbatim including spaces without stripping") {
        val context = mockk<Context>()
        val clipboardManager = mockk<ClipboardManager>()
        val description = mockk<ClipDescription>()
        val clip = mockk<ClipData>()
        val item = mockk<ClipData.Item>()
        val textWithSpaces = "   leading and trailing spaces \n "

        every { description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) } returns true
        every { clipboardManager.primaryClipDescription } returns description
        every { clipboardManager.primaryClip } returns clip
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { item.text } returns textWithSpaces

        getClipboardText(context, clipboardManager) shouldBe textWithSpaces
    }
})
