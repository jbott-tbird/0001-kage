// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.ui.navigation.KageApp
import org.foxred.kage.ui.theme.KageTheme
import org.junit.*

class NavigationRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var db: MailDatabase
    private lateinit var vm: MailViewModel

    @Before
    fun start() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        vm =
            MailViewModel(
                RoomMailRepository(
                    db,
                    context,
                    DemoMail(context),
                    org.foxred.kage.data.security.AndroidCredentialStore(context, "repository-test"),
                )
            )
        restoration.setContent { KageTheme { KageApp(vm) } }
        compose.waitUntil(10000) { vm.ready.value }
    }

    @After
    fun stop() {
        vm.viewModelScope.cancel()
        db.close()
    }

    @Test
    fun repeatedSetupBackDoesNotRemoveTheRootScreen() {
        compose.onNodeWithText("Get started").performClick()
        val back = compose.onNodeWithContentDescription("Back")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        // Both input events can arrive before Compose removes the outgoing screen.
        compose.runOnIdle { back(); back() }
        compose.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun setupFinishSurvivesStateRestoration() {
        compose.onNodeWithText("Set up a demo account").performClick()
        compose.onNodeWithText("Email address").performTextInput("navigation@example.net")
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.mailbox.value.accounts.size == 4 }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Finish").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Filter messages").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Filter messages").assertExists()
    }
}
