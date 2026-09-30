package org.foxred.kage.ui

import android.app.Activity
import android.os.Bundle

/** A local consent screen that returns cancellation for the authorization UI test. */
class GoogleConsentCancelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        finish()
    }
}
