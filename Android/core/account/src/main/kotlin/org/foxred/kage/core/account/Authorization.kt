// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

import java.time.Instant

/** Intentionally not a data class: generated toString must never disclose a credential. */
class Authorization(
    val secret: String,
    val kind: Kind = Kind.APP_PASSWORD,
    val expiresAt: Instant? = null,
    val refreshToken: String? = null,
) {
    enum class Kind {
        APP_PASSWORD,
        OAUTH2,
        NONE,
    }

    init {
        require(secret.isNotBlank() || kind == Kind.NONE)
    }

    fun isExpired(now: Instant = Instant.now()): Boolean =
        expiresAt?.let { !it.isAfter(now) } ?: false

    companion object {
        fun none() = Authorization("", Kind.NONE)
    }

    override fun toString() = "Authorization([redacted], $kind)"
}
