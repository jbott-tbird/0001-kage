package org.foxred.kage.core.account

/** Domain vocabulary mirrors iOS Core/Account; no provider or persistence types escape here. */
data class EmailAddress(val address: String, val name: String = "") {
    init {
        require(address.isNotBlank() && !address.contains('\r') && !address.contains('\n'))
    }
}
