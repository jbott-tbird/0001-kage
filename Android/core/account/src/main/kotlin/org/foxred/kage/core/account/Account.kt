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
