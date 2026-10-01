package org.foxred.kage.ui.compose

import android.graphics.Typeface
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.foxred.kage.ui.theme.KageTheme
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RichMessageEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun formattingPublishesHtmlAndPlainTextAndCanBeCleared() {
        var body = ""
        var html = ""
        compose.setContent {
            KageTheme {
                RichMessageEditor("Hello world", null, true) { text, markup ->
                    body = text
                    html = markup
                }
            }
        }
        compose.onNodeWithText("Formatting").performClick()
        onView(isAssignableFrom(EditText::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(EditText::class.java)
            override fun getDescription() = "Select the first word"
            override fun perform(uiController: UiController, view: View) {
                (view as EditText).requestFocus()
                view.setSelection(0, 5)
                uiController.loopMainThreadUntilIdle()
            }
        })
        compose.onNodeWithContentDescription("Bold selected text").performClick()
        compose.runOnIdle {
            assertEquals("Hello world", body)
            val restored = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            assertTrue(restored.getSpans(0, 5, StyleSpan::class.java).any { it.style == Typeface.BOLD })
            assertTrue(restored.getSpans(6, 11, StyleSpan::class.java).isEmpty())
        }
        compose.onNodeWithContentDescription("Clear selected text formatting").performClick()
        compose.runOnIdle {
            assertTrue(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                .getSpans(0, 5, StyleSpan::class.java).isEmpty())
            assertEquals("Hello world", body)
        }
    }

    @Test fun reopeningDraftPreservesFormattingWithoutAddingBlankLines() {
        val text = SpannableStringBuilder("Hello world\n")
        text.setSpan(StyleSpan(Typeface.ITALIC), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.UnderlineSpan(), 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val html = Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
        val restored = messageEditorText(text.toString(), html) as Spanned
        assertEquals(text.toString(), restored.toString())
        assertEquals(Typeface.ITALIC, restored.getSpans(0, 5, StyleSpan::class.java).single().style)
        assertEquals(1, restored.getSpans(6, 11, android.text.style.UnderlineSpan::class.java).size)
    }

    @Test fun clearingMiddleOfSpanPreservesBothSides() {
        val text = SpannableStringBuilder("one two three")
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        clearMessageFormatting(text, 4, 7)
        val spans = text.getSpans(0, text.length, StyleSpan::class.java)
        assertEquals(setOf(0 to 4, 7 to 13), spans.map { text.getSpanStart(it) to text.getSpanEnd(it) }.toSet())
        val restored = Html.fromHtml(Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL),
            Html.FROM_HTML_MODE_COMPACT)
        assertTrue(restored.getSpans(4, 7, StyleSpan::class.java).isEmpty())
    }

    @Test fun replacingLinkRetainsBoldAndNeighboringLinkText() {
        val text = SpannableStringBuilder("one two three")
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(URLSpan("https://example.com"), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        clearMessageFormatting(text, 4, 7, linksOnly = true)
        text.setSpan(URLSpan("https://example.org"), 4, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(1, text.getSpans(0, text.length, StyleSpan::class.java).size)
        val restored = Html.fromHtml(Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL),
            Html.FROM_HTML_MODE_COMPACT)
        assertEquals("https://example.org", restored.getSpans(4, 7, URLSpan::class.java).single().url)
        assertEquals("https://example.com", restored.getSpans(0, 3, URLSpan::class.java).single().url)
    }
}
