// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.emaildisplay

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/** Measures to the full email height; the surrounding Compose reader owns scrolling. */
internal class MessageWebView(context: Context) : WebView(context) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        // Keep link taps and text selection, but let the reader intercept vertical drags.
        parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }
}
