// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.foxred.kage.data.background.BackgroundMailSettings
import org.foxred.kage.data.background.backgroundMailEligible
import org.foxred.kage.di.AppContainer

class KageApplication : Application() {
    val container by lazy { AppContainer(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            container.mailRepository.mailbox
                .map { mail -> mail.accounts.any(::backgroundMailEligible) to mail.preferences.offline }
                .distinctUntilChanged()
                .collect { (hasEligibleAccount, offline) ->
                    runCatching {
                        BackgroundMailSettings.reconcile(this@KageApplication,
                            hasEligibleAccount, offline)
                    }
                }
        }
    }
}
