// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

data class Account(
    val id: String,
    val name: String,
    val identities: List<EmailAddress>,
    val incomingServer: Server,
    val outgoingServer: Server,
    val deletePolicy: DeletePolicy = DeletePolicy.Never,
    val avatarColor: String = "user-blue",
    val authConfig: OAuthConfiguration? = null,
) {
    val servers: List<Server>
        get() = listOf(incomingServer, outgoingServer)

    val emailAddress: EmailAddress?
        get() = identities.firstOrNull()

    fun server(protocol: ServerProtocol): Server? = servers.firstOrNull { it.protocol == protocol }
}
