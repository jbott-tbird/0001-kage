package org.foxred.kage.ui

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.lightColorScheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.foxred.kage.ui.emaildisplay.SafeMessageHtml
import org.foxred.kage.ui.theme.DesignTokens
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageHtmlLayoutTest {
    @Test
    fun newsletterTablesAndLongTextFitNarrowViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val html = """
            <table width="1200"><tr><td><table width="900"><tr><td>
            <h1>A newsletter heading that should wrap within the message pane</h1>
            <p>${"https://example.test/" + "long-link".repeat(50)}</p>
            <pre>${"unbroken".repeat(80)}</pre>
            </td></tr></table></td></tr></table>
        """.trimIndent()
        val content = SafeMessageHtml.render(context, html, emptyList())
        for (fontScale in listOf(1f, 1.5f)) {
            val finished = CountDownLatch(1)
            var result = ""
            lateinit var view: WebView
            instrumentation.runOnMainSync {
                view = WebView(context)
                // Script execution is enabled only in this test to measure the rendered layout.
                view.settings.javaScriptEnabled = true
                val width = (320 * context.resources.displayMetrics.density).toInt()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, 1200)
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(webView: WebView, url: String?) {
                        webView.evaluateJavascript(
                            "document.documentElement.clientWidth > 0 && " +
                                "document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1",
                        ) {
                            result = it
                            finished.countDown()
                        }
                    }
                }
                view.loadDataWithBaseURL(null,
                    "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                        "<style>${DesignTokens.emailCss(lightColorScheme(), fontScale)}</style>" +
                        "</head><body>$content</body></html>", "text/html", "UTF-8", null)
            }
            try {
                assertTrue("Layout did not finish", finished.await(20, TimeUnit.SECONDS))
                assertTrue("Message overflowed at font scale $fontScale: $result", result == "true")
            } finally {
                instrumentation.runOnMainSync { view.destroy() }
            }
        }
    }
}
