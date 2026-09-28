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
