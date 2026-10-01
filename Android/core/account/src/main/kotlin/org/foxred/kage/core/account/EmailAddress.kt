// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

/** Domain vocabulary mirrors iOS Core/Account; no provider or persistence types escape here. */
data class EmailAddress(val address: String, val name: String = "") {
    init {
        require(address.isNotBlank() && !address.contains('\r') && !address.contains('\n'))
    }
}
