package org.foxred.kage.core

import java.io.File
import org.foxred.kage.core.account.*
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.foxred.kage.core.testkit.*
import org.junit.Assert.*
import org.junit.Test

class StartTlsSubmissionTest {
    private fun runServer(
        dropAfterData: Boolean = false,
        reject: String? = null,
        action: (Int) -> Unit,
    ): List<String> {
        val context =
            testTlsContext(File(System.getProperty("greenmail.tls.keystore.file")).inputStream())
        val transcript = SmtpTranscript(context, reject, dropAfterData)
        LoopbackServer(handler = transcript::serve).use { server ->
            action(server.port)
            server.awaitCompletion()
        }
        return transcript.commands
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
        val transcript = runServer { submit(it) }
        assertTrue(transcript.any { it.contains("RCPT TO:<hidden@example.net>") })
        assertFalse(transcript.any { it.startsWith("Bcc:", true) })
        assertTrue(transcript.contains("..leading dot"))
    }

    @Test
    fun disconnectAfterDataNeverReportsConfirmedDelivery() {
        runServer(dropAfterData = true) { port ->
            val error = assertThrows(MailFailure::class.java) { submit(port) }
            assertEquals(FailureKind.UNCERTAIN_DELIVERY, error.kind)
        }
    }

    @Test
    fun rejectedRecipientAbortsAllRecipientsBeforeData() {
        val transcript =
            runServer(reject = "hidden@example.net") { port ->
                val error = assertThrows(MailFailure::class.java) { submit(port) }
                assertEquals(FailureKind.PROTOCOL, error.kind)
            }
        assertFalse(transcript.contains("DATA"))
        assertTrue(transcript.contains("RSET"))
    }
}
