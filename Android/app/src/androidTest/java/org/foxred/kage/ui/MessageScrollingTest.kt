// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
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
    fun bodySwipesMoveHeaderAndEmailTogether() {
        lateinit var webView: MessageWebView
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().testTag("reader").verticalScroll(scroll)) {
                Spacer(Modifier.height(40.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight().testTag("email"),
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
            compose.runOnIdle { ready = webView.height > 2 * webView.resources.displayMetrics.heightPixels }
            ready
        }
        compose.onNodeWithTag("reader").performTouchInput { swipeUp() }
        var firstScroll = 0
        compose.runOnIdle {
            assertTrue("Header and body should scroll together", scroll.value > 0)
            assertTrue("Email must not have an independent scroll offset", webView.scrollY == 0)
            firstScroll = scroll.value
        }
        compose.onNodeWithTag("reader").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("Further body swipes should keep moving the whole page", scroll.value > firstScroll)
            assertTrue("Email must remain expanded", webView.scrollY == 0)
        }
    }
}
