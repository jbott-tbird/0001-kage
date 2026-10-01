// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

sealed interface DeletePolicy {
    data object Never : DeletePolicy

    data object OnDelete : DeletePolicy

    data object MarkAsRead : DeletePolicy

    data class After(val days: Int = 7) : DeletePolicy {
        init {
            require(days >= 0)
        }
    }
}
