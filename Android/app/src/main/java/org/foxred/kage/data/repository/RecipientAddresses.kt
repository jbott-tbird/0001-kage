// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import org.foxred.kage.core.account.EmailAddress

/** Parse mailbox recipients while preserving RFC address groups and legacy semicolon lists. */
fun parseRecipientAddresses(value: String): List<EmailAddress> {
    if (value.isBlank()) return emptyList()
    val parsed = try {
        InternetAddress.parse(value, true)
    } catch (error: AddressException) {
        if (';' !in value || ':' in value) throw error
        InternetAddress.parse(value.replace(';', ','), true)
    }
    return parsed.flatMap { address ->
        if (address.isGroup) address.getGroup(true).asList() else listOf(address)
    }.map { address ->
        address.validate()
        EmailAddress(address.address, address.personal.orEmpty())
    }
}
