package org.foxred.kage.core.transport

import org.foxred.kage.core.account.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionPropertiesTest {
    @Test
    fun startTlsCannotFallBackToPlaintext() {
        val p =
            connectionProperties(
                Server("localhost", 587, ServerProtocol.SMTP, ConnectionSecurity.STARTTLS, "user"),
                Authorization("secret"),
            )
        assertEquals("true", p.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", p.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertFalse(p.toString().contains("secret"))
        assertFalse(Authorization("secret").toString().contains("secret"))
    }

    @Test
    fun oauthUsesBuiltinMechanismWithoutAndroidSasl() {
        val p =
            connectionProperties(
                Server("imap.gmail.com", 993, ServerProtocol.IMAP, username = "user"),
                Authorization("token", Authorization.Kind.OAUTH2),
            )
        assertEquals("XOAUTH2", p.getProperty("mail.imap.auth.mechanisms"))
        assertEquals("true", p.getProperty("mail.imap.ssl.enable"))
        assertEquals("true", p.getProperty("mail.imap.peek"))
    }
}
