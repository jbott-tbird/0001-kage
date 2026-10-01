// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

import java.util.UUID

enum class ConnectionSecurity {
    TLS,
    STARTTLS,
}

enum class ServerProtocol {
    IMAP,
    SMTP,
}

data class Server(
    val hostname: String,
    val port: Int,
    val protocol: ServerProtocol,
    val security: ConnectionSecurity = ConnectionSecurity.TLS,
    val username: String,
    val authenticationType: AuthenticationType = AuthenticationType.PASSWORD,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(hostname.isNotBlank())
        require(port in 1..65535)
        require(username.isNotBlank() || authenticationType == AuthenticationType.NONE)
    }
}
