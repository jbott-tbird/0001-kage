package org.foxred.kage.core.smtp

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.transport.connectionProperties

class AngusSmtpClient(private val codec: MimeCodec = AngusMimeCodec()) : MailSubmission {
    override fun send(server: Server, authorization: Authorization, email: OutgoingEmail) {
        require(server.protocol == ServerProtocol.SMTP)
        val session = Session.getInstance(connectionProperties(server, authorization))
        val raw = codec.encode(email)
        val message = MimeMessage(session, raw.inputStream())
        val recipients =
            (email.to + email.cc + email.bcc)
                .map { InternetAddress(it.address).apply { validate() } }
                .toTypedArray()
        val transport = session.getTransport("smtp")
        var submitting = false
        try {
            transport.connect(server.hostname, server.port, server.username, authorization.secret)
            submitting = true
            transport.sendMessage(message, recipients)
        } catch (e: AuthenticationFailedException) {
            throw MailFailure(FailureKind.AUTHENTICATION, "SMTP authentication failed", e)
        } catch (e: SendFailedException) {
            // Some recipients may already have been accepted; callers must not blindly retry.
            val kind =
                if (!e.validSentAddresses.isNullOrEmpty()) FailureKind.UNCERTAIN_DELIVERY
                else FailureKind.PROTOCOL
            throw MailFailure(kind, "SMTP rejected delivery for one or more recipients", e)
        } catch (e: Exception) {
            throw MailFailure(
                if (submitting) FailureKind.UNCERTAIN_DELIVERY else FailureKind.CONNECTION,
                if (submitting) "Delivery outcome is unknown; check Sent before retrying"
                else "SMTP connection failed",
                e,
            )
        } finally {
            runCatching { transport.close() }
        }
    }
}
