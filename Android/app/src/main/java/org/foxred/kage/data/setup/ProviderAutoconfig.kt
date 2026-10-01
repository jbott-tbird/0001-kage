// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.setup

import android.util.Xml
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foxred.kage.core.account.ConnectionSecurity
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.xmlpull.v1.XmlPullParser

/** HTTPS Thunderbird autoconfig lookup. Email is sent only to the provider; ISPDB gets the domain. */
class ProviderAutoconfig(private val fetch: (String) -> String? = ::loadHttps) {
    suspend fun discover(address: String): Pair<Server, Server>? = withContext(Dispatchers.IO) {
        val email = address.trim().lowercase()
        val domain = email.substringAfterLast('@', "")
        if (!email.contains('@') || !domain.matches(Regex("[a-z0-9.-]{1,253}")) ||
            domain.startsWith('.') || domain.endsWith('.') || !domain.contains('.')) return@withContext null
        val host = runCatching { IDN.toASCII(domain) }.getOrNull() ?: return@withContext null
        val encoded = java.net.URLEncoder.encode(email, "UTF-8")
        val urls = listOf(
            "https://autoconfig.$host/mail/config-v1.1.xml?emailaddress=$encoded",
            "https://$host/.well-known/autoconfig/mail/config-v1.1.xml?emailaddress=$encoded",
            "https://autoconfig.thunderbird.net/v1.1/$host",
        )
        urls.firstNotNullOfOrNull { url ->
            fetch(url)?.let { runCatching { parse(it, email) }.getOrNull() }
        }
    }

    private fun parse(xml: String, email: String): Pair<Server, Server>? {
        if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) return null
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        var kind: ServerProtocol? = null
        var fields = mutableMapOf<String, String>()
        var incoming: Server? = null
        var outgoing: Server? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == "incomingServer" || tag == "outgoingServer") {
                        kind = when {
                            tag == "incomingServer" && parser.getAttributeValue(null, "type") == "imap" -> ServerProtocol.IMAP
                            tag == "outgoingServer" && parser.getAttributeValue(null, "type") == "smtp" -> ServerProtocol.SMTP
                            else -> null
                        }
                        fields = mutableMapOf()
                    } else if (kind != null && tag in setOf("hostname", "port", "socketType", "authentication", "username")) {
                        fields[tag] = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "incomingServer" || parser.name == "outgoingServer") {
                    kind?.let { protocol ->
                        val security = when (fields["socketType"]?.uppercase()) {
                            "SSL" -> ConnectionSecurity.TLS
                            "STARTTLS" -> ConnectionSecurity.STARTTLS
                            else -> null
                        }
                        val auth = fields["authentication"]?.lowercase()
                        val port = fields["port"]?.toIntOrNull()
                        val hostname = fields["hostname"].orEmpty()
                        val username = fields["username"].orEmpty()
                            .replace("%EMAILADDRESS%", email, true)
                            .replace("%EMAILLOCALPART%", email.substringBefore('@'), true)
                            .replace("%EMAILDOMAIN%", email.substringAfter('@'), true)
                        if (security != null && port != null && port in 1..65535 &&
                            hostname.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) &&
                            username.isNotBlank() && auth in setOf("password-cleartext", "password-encrypted")) {
                            val server = Server(hostname, port, protocol, security, username)
                            if (protocol == ServerProtocol.IMAP && incoming == null) incoming = server
                            if (protocol == ServerProtocol.SMTP && outgoing == null) outgoing = server
                        }
                    }
                    kind = null
                }
            }
            parser.next()
        }
        return if (incoming != null && outgoing != null) incoming!! to outgoing!! else null
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024

        fun loadHttps(url: String): String? = runCatching {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode != 200) return@runCatching null
                val bytes = ByteArrayOutputStream()
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > MAX_BYTES) return@runCatching null
                        bytes.write(buffer, 0, count)
                    }
                }
                bytes.toString("UTF-8")
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
