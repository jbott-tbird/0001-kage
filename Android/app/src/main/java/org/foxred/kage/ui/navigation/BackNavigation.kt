// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/** Ignore callbacks from an outgoing screen and never pop the last visible destination. */
internal fun NavHostController.popBackStackFrom(source: NavBackStackEntry): Boolean {
    if (currentBackStackEntry != source || previousBackStackEntry == null) return false
    return popBackStack()
}
