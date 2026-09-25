package org.foxred.kage.core

import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.foxred.kage.core.account.*
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.junit.Assert.*
import org.junit.Test

/** A transcript server exercises the STARTTLS upgrade that GreenMail does not advertise. */
class StartTlsSubmissionTest {
    private fun runServer(dropAfterData: Boolean, action: (Int) -> Unit): List<String> {
        val keyStore = KeyStore.getInstance("PKCS12")
        java.io.File(System.getProperty("greenmail.tls.keystore.file")).inputStream().use {
            keyStore.load(it, "test-password".toCharArray())
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        km.init(keyStore, "test-password".toCharArray())
        val context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10000
            val future =
                executor.submit<List<String>> {
                    server.accept().use { plain ->
                        plain.soTimeout = 10000
                        var reader = plain.inputStream.bufferedReader(Charsets.US_ASCII)
                        var writer = plain.outputStream.bufferedWriter(Charsets.US_ASCII)
                        fun reply(value: String) {
                            writer.write(value + "\r\n")
                            writer.flush()
                        }
                        reply("220 localhost test")
                        assertTrue(reader.readLine().startsWith("EHLO "))
                        reply("250-localhost\r\n250 STARTTLS")
                        assertEquals("STARTTLS", reader.readLine())
                        reply("220 Upgrade")
                        val secure =
                            context.socketFactory.createSocket(
                                plain,
                                "localhost",
                                plain.port,
                                false,
                            ) as SSLSocket
                        secure.useClientMode = false
                        secure.startHandshake()
                        reader = secure.inputStream.bufferedReader(Charsets.US_ASCII)
                        writer = secure.outputStream.bufferedWriter(Charsets.US_ASCII)
                        assertTrue(reader.readLine().startsWith("EHLO "))
                        reply("250-localhost\r\n250 AUTH PLAIN")
                        val auth = reader.readLine()
                        assertTrue(auth.startsWith("AUTH PLAIN "))
                        assertTrue(
                            String(
                                    java.util.Base64.getDecoder()
                                        .decode(auth.substringAfterLast(' '))
                                )
                                .endsWith("\u0000password")
                        )
                        reply("235 Authenticated")
                        val transcript = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            transcript += line
                            when {
                                line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:") ->
                                    reply("250 OK")
                                line == "DATA" -> {
                                    reply("354 Continue")
                                    while (true) {
                                        val body = reader.readLine() ?: error("Truncated DATA")
                                        if (body == ".") break
                                        transcript += body
                                    }
                                    if (dropAfterData) {
                                        secure.close()
                                        break
                                    }
                                    reply("250 Accepted")
                                }
                                line == "QUIT" -> {
                                    reply("221 Bye")
                                    break
                                }
                                else -> error("Unexpected command: $line")
                            }
                        }
                        secure.close()
                        transcript
                    }
                }
            try {
                action(server.localPort)
                return future.get(15, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun submit(port: Int) =
        AngusSmtpClient()
            .send(
                Server("localhost", port, ServerProtocol.SMTP, ConnectionSecurity.STARTTLS, "user"),
                Authorization("password"),
                OutgoingEmail(
                    "<tls-test@example.net>",
                    EmailAddress("from@example.net"),
                    listOf(EmailAddress("to@example.net")),
                    bcc = listOf(EmailAddress("hidden@example.net")),
                    subject = "TLS",
                    body = EmailBody("first\r\n.leading dot\r\nlast", null),
                ),
            )

    @Test
    fun upgradesBeforeAuthenticationAndSubmitsBccOnlyInEnvelope() {
        val transcript = runServer(false) { submit(it) }
        assertTrue(transcript.any { it.contains("RCPT TO:<hidden@example.net>") })
        assertFalse(transcript.any { it.startsWith("Bcc:", true) })
        assertTrue(transcript.contains("..leading dot"))
    }

    @Test
    fun disconnectAfterDataNeverReportsConfirmedDelivery() {
        runServer(true) { port ->
            val error = assertThrows(MailFailure::class.java) { submit(port) }
            assertEquals(FailureKind.UNCERTAIN_DELIVERY, error.kind)
        }
    }
}
