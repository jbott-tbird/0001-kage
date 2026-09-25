package org.foxred.kage.core.demo

import java.util.concurrent.atomic.AtomicBoolean
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec

/** Delivers serialized mail only to an explicitly supplied local demo sink. */
class DemoMailSubmission(
    private val deliver: (ByteArray) -> Unit,
    private val codec: MimeCodec = AngusMimeCodec(),
) : MailSubmission {
    @Volatile private var operation: AtomicBoolean? = null

    override fun cancel() {
        operation?.set(true)
    }

    @Synchronized
    override fun send(server: Server, authorization: Authorization, email: OutgoingEmail) {
        require(server.protocol == ServerProtocol.SMTP)
        val cancelled = AtomicBoolean(false)
        operation = cancelled
        try {
            val raw = codec.encode(email)
            if (cancelled.get())
                throw MailFailure(FailureKind.CANCELLED, "Demo submission cancelled")
            deliver(raw)
        } finally {
            operation = null
        }
    }
}
