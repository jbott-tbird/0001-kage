// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import kotlinx.coroutines.flow.first
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.compose.ComposeScreen
import org.foxred.kage.ui.emaildisplay.InboxScreen
import org.foxred.kage.ui.emaildisplay.MessageScreen
import org.foxred.kage.ui.settings.SettingsScreen
import org.foxred.kage.ui.account.auth.*
import org.foxred.kage.ui.welcome.WelcomeScreen
import org.foxred.kage.ui.theme.DesignTokens as T

@Composable
fun KageApp(vm: MailViewModel) {
    val ready by vm.ready.collectAsStateWithLifecycle()
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val outboxCounts by vm.outboxCounts.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.notice.compareAndSet(it, null)
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else {
            val start = remember {
                if (mail.accounts.any { it.mode == "REAL" }) "mail" else "welcome"
            }
            fun inbox() {
                nav.navigate("mail") {
                    popUpTo(nav.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
            fun welcome() {
                nav.navigate("welcome") {
                    popUpTo(nav.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
            NavHost(navController = nav, startDestination = start) {
                composable("welcome") {
                    WelcomeScreen(
                        { nav.navigate("setup") },
                        { nav.navigate("setup-real") },
                        {
                            vm.action(success = { inbox() }) {
                                if (mail.accounts.isEmpty()) vm.repository.resetDemo()
                                vm.repository.updatePreferences(
                                    vm.repository.mailbox.first().preferences.copy(started = true)
                                )
                            }
                        },
                    )
                }
                composable("setup") { entry -> SetupScreen(vm, { nav.popBackStackFrom(entry) }, { inbox() }, { nav.navigate("setup-real") }) }
                composable("setup-real") { entry -> RealSetupScreen(vm, { nav.popBackStackFrom(entry) }, { inbox() }) }
                composable("mail") {
                    InboxScreen(
                        vm,
                        { message ->
                            nav.navigate(
                                if (message.draft) "compose/draft/${message.id}"
                                else "message/${message.id}"
                            )
                        },
                        { nav.navigate("compose/new/none") },
                        { nav.navigate("setup") },
                        { nav.navigate("settings") },
                    )
                }
                composable("message/{id}") { entry ->
                    MessageScreen(
                        vm,
                        entry.arguments?.getString("id").orEmpty(),
                        { nav.popBackStackFrom(entry) },
                        { mode ->
                            nav.navigate("compose/$mode/${entry.arguments?.getString("id")}")
                        },
                    )
                }
                composable("compose/{mode}/{id}") { entry ->
                    ComposeScreen(
                        vm,
                        entry.arguments?.getString("id"),
                        entry.arguments?.getString("mode").orEmpty(),
                        { nav.popBackStackFrom(entry) },
                    )
                }
                composable("settings") { entry ->
                    SettingsScreen(
                        vm,
                        { nav.popBackStackFrom(entry) },
                        { nav.navigate("setup") },
                        { welcome() },
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(T.lg),
            verticalArrangement = Arrangement.spacedBy(T.sm),
        ) {
            if (outboxCounts.sending > 0 || outboxCounts.queued > 0) {
                Surface(tonalElevation = T.sm, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(T.md)
                        .semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(
                            if (outboxCounts.sending > 0) "Sending email…"
                            else if (mail.preferences.offline) "Email queued — offline mode is on"
                            else "Email queued for sending…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (outboxCounts.sending > 0) {
                            Spacer(Modifier.height(T.sm))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            SnackbarHost(snackbar)
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.error.value = null },
            title = { Text("Couldn’t complete that action") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("OK") } },
        )
    }
}
