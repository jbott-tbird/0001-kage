package org.foxred.kage.ui.emaildisplay

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/** Keeps body swipes in the reader until the email reaches its scroll boundary. */
internal class MessageWebView(context: Context) : WebView(context) {
    private var lastTouchY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                // Compose must not intercept the drag before WebView can start scrolling.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = lastTouchY - event.y
                if (delta != 0f) {
                    parent?.requestDisallowInterceptTouchEvent(
                        canScrollVertically(if (delta > 0f) 1 else -1)
                    )
                }
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }
}
