package org.foxred.kage.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.notice.value = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else {
            val start = remember {
                if (mail.preferences.started && mail.accounts.isNotEmpty()) "mail" else "welcome"
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
                composable("setup") { SetupScreen(vm, { nav.popBackStack() }, { inbox() }, { nav.navigate("setup-real") }) }
                composable("setup-real") { RealSetupScreen(vm, { nav.popBackStack() }, { inbox() }) }
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
                        { nav.popBackStack() },
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
                        { nav.popBackStack() },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        vm,
                        { nav.popBackStack() },
                        { nav.navigate("setup") },
                        { welcome() },
                    )
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(T.lg),
        )
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
