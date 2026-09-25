package org.foxred.kage.core.transport

import java.util.Properties
import org.foxred.kage.core.account.*

/** Shared TLS policy; app passwords are never offered over an unencrypted connection. */
fun connectionProperties(
    server: Server,
    auth: Authorization,
    timeoutMillis: Int = 15000,
): Properties {
    require(timeoutMillis > 0)
    val protocol = server.protocol.name.lowercase()
    return Properties().apply {
        setProperty("mail.$protocol.connectiontimeout", "$timeoutMillis")
        setProperty("mail.$protocol.timeout", "$timeoutMillis")
        setProperty("mail.$protocol.writetimeout", "$timeoutMillis")
        setProperty("mail.$protocol.ssl.checkserveridentity", "true")
        setProperty("mail.$protocol.ssl.protocols", "TLSv1.2 TLSv1.3")
        setProperty("mail.$protocol.ssl.enable", "${server.security == ConnectionSecurity.TLS}")
        setProperty(
            "mail.$protocol.starttls.enable",
            "${server.security == ConnectionSecurity.STARTTLS}",
        )
        setProperty(
            "mail.$protocol.starttls.required",
            "${server.security == ConnectionSecurity.STARTTLS}",
        )
        setProperty("mail.$protocol.auth", "true")
        setProperty(
            "mail.$protocol.auth.mechanisms",
            if (auth.kind == Authorization.Kind.OAUTH2) "XOAUTH2" else "PLAIN LOGIN",
        )
        setProperty("mail.imap.peek", "true")
        setProperty("mail.smtp.sendpartial", "false")
        setProperty("mail.debug", "false")
    }
}
