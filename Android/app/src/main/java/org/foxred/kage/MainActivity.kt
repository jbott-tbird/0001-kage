// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.navigation.KageApp
import org.foxred.kage.ui.theme.KageTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KageApplication).container
        setContent {
            val vm: MailViewModel =
                viewModel(
                    factory =
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                MailViewModel(
                                    container.mailRepository,
                                    container.realAccountSetup,
                                    container.remoteMailRepository,
                                    container.googleAuthorization,
                                ) as T
                        }
                )
            KageTheme { KageApp(vm) }
        }
    }
}
