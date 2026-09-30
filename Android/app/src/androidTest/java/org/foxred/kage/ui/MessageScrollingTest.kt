package org.foxred.kage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.foxred.kage.ui.emaildisplay.MessageWebView
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessageScrollingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun bodySwipesScrollLongEmailAndHandOffAtBottom() {
        lateinit var webView: MessageWebView
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height(40.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("email"),
                    factory = { context ->
                        MessageWebView(context).also {
                            webView = it
                            it.loadDataWithBaseURL(null,
                                "<html><body>${"<p>Newsletter paragraph</p>".repeat(150)}</body></html>",
                                "text/html", "UTF-8", null)
                        }
                    },
                    onRelease = { it.destroy() },
                )
                Spacer(Modifier.height(1000.dp))
            }
        }
        compose.waitUntil(20_000) {
            var ready = false
            compose.runOnIdle { ready = webView.canScrollVertically(1) }
            ready
        }
        compose.onNodeWithTag("email").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("Swipe should scroll the email body", webView.scrollY > 0)
            assertTrue("Outer screen should remain still inside the body", scroll.value == 0)
            webView.flingScroll(0, 0)
            webView.scrollTo(0, Int.MAX_VALUE)
        }
        compose.onNodeWithTag("email").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("At the email bottom, swipe should reach the outer content", scroll.value > 0)
        }
    }
}
