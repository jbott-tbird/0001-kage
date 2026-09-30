package org.foxred.kage.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/** Ignore callbacks from an outgoing screen and never pop the last visible destination. */
internal fun NavHostController.popBackStackFrom(source: NavBackStackEntry): Boolean {
    if (currentBackStackEntry != source || previousBackStackEntry == null) return false
    return popBackStack()
}
