// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.smtp

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.transport.ConnectionControl
import org.foxred.kage.core.transport.connectionProperties

class AngusSmtpClient(
    private val codec: MimeCodec = AngusMimeCodec(),
    private val timeoutMillis: Int = 15000,
) : MailSubmission, RawMailSubmission {
    @Volatile private var control: ConnectionControl? = null
    @Volatile private var cancellationRequested = false

    override fun cancel() {
        cancellationRequested = true
        control?.cancel()
    }

    /** Authenticate and negotiate TLS without issuing MAIL FROM or delivering a message. */
    @Synchronized
    fun verifyConnection(server: Server, authorization: Authorization) {
        require(server.protocol == ServerProtocol.SMTP)
        val operation = ConnectionControl()
        val properties = connectionProperties(server, authorization, timeoutMillis)
        operation.install(properties, "smtp")
        val transport = Session.getInstance(properties).getTransport("smtp")
        control = operation
        try {
            transport.connect(
                server.hostname,
                server.port,
                server.username.takeUnless { authorization.kind == Authorization.Kind.NONE },
                authorization.secret.takeUnless { authorization.kind == Authorization.Kind.NONE },
            )
        } catch (error: AuthenticationFailedException) {
            throw MailFailure(FailureKind.AUTHENTICATION, "SMTP authentication failed", error)
        } catch (error: Exception) {
            throw MailFailure(
                if (operation.cancelled) FailureKind.CANCELLED else FailureKind.CONNECTION,
                "SMTP connection failed",
                error,
            )
        } finally {
            operation.cancel()
            runCatching { transport.close() }
            control = null
        }
    }

    @Synchronized
    override fun send(server: Server, authorization: Authorization, email: OutgoingEmail) {
        sendRaw(server, authorization, codec.encode(email), email.to + email.cc + email.bcc)
    }

    @Synchronized
    override fun sendRaw(
        server: Server,
        authorization: Authorization,
        raw: ByteArray,
        recipients: List<EmailAddress>,
    ) = sendRaw(server, authorization, raw, recipients) { }

    @Synchronized
    override fun sendRaw(
        server: Server,
        authorization: Authorization,
        raw: ByteArray,
        recipients: List<EmailAddress>,
        onSubmissionStart: () -> Unit,
    ) {
        if (server.protocol != ServerProtocol.SMTP)
            throw MailFailure(FailureKind.PROTOCOL, "Outgoing server is not SMTP")
        if (recipients.isEmpty())
            throw MailFailure(FailureKind.INVALID_MESSAGE, "At least one recipient is required")
        if (cancellationRequested)
            throw MailFailure(FailureKind.CANCELLED, "SMTP submission was cancelled")
        val operation = ConnectionControl()
        val properties = connectionProperties(server, authorization, timeoutMillis)
        val session = try {
            operation.install(properties, "smtp")
            Session.getInstance(properties)
        } catch (error: Exception) {
            throw MailFailure(FailureKind.PROTOCOL, "SMTP transport could not be prepared", error)
        }
        // Parsing and address validation happen before any SMTP command is sent.
        val message = try { MimeMessage(session, raw.inputStream()) }
            catch (error: Exception) {
                throw MailFailure(FailureKind.INVALID_MESSAGE, "Queued message is invalid", error)
            }
        val addresses = try {
            recipients
                .map { InternetAddress(it.address).apply { validate() } }
                .toTypedArray()
        } catch (error: Exception) {
            throw MailFailure(FailureKind.INVALID_MESSAGE, "Recipient address is invalid", error)
        }
        val transport = try { session.getTransport("smtp") }
            catch (error: Exception) {
                throw MailFailure(FailureKind.PROTOCOL, "SMTP transport is unavailable", error)
            }
        var submitting = false
        control = operation
        if (cancellationRequested) operation.cancel()
        try {
            transport.connect(
                server.hostname,
                server.port,
                server.username.takeUnless { authorization.kind == Authorization.Kind.NONE },
                authorization.secret.takeUnless { authorization.kind == Authorization.Kind.NONE },
            )
            submitting = true
            onSubmissionStart()
            transport.sendMessage(message, addresses)
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
                if (submitting) FailureKind.UNCERTAIN_DELIVERY
                else if (operation.cancelled) FailureKind.CANCELLED else FailureKind.CONNECTION,
                if (submitting) "Delivery outcome is unknown; check Sent before retrying"
                else "SMTP connection failed",
                e,
            )
        } finally {
            operation.cancel()
            runCatching { transport.close() }
            control = null
        }
    }
}
