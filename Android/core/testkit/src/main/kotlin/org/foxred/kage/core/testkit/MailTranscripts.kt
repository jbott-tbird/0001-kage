package org.foxred.kage.core.testkit

import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class SmtpTranscript(
    private val startTls: SSLContext? = null,
    private val rejectRecipient: String? = null,
    private val dropAfterData: Boolean = false,
) {
    val commands = mutableListOf<String>()

    fun serve(original: Socket) {
        var socket = original
        var reader = socket.inputStream.bufferedReader(Charsets.US_ASCII)
        var writer = socket.outputStream.bufferedWriter(Charsets.US_ASCII)
        fun reply(value: String) {
            writer.write(value + "\r\n")
            writer.flush()
        }
        reply("220 localhost test")
        check(reader.readLine().startsWith("EHLO "))
        if (startTls != null) {
            reply("250-localhost\r\n250 STARTTLS")
            check(reader.readLine() == "STARTTLS")
            reply("220 Upgrade")
            socket =
                (startTls.socketFactory.createSocket(original, "localhost", original.port, false)
                        as SSLSocket)
                    .apply {
                        useClientMode = false
                        startHandshake()
                    }
            reader = socket.inputStream.bufferedReader(Charsets.US_ASCII)
            writer = socket.outputStream.bufferedWriter(Charsets.US_ASCII)
            check(reader.readLine().startsWith("EHLO "))
        }
        reply("250-localhost\r\n250 AUTH PLAIN")
        val auth = reader.readLine()
        check(auth.startsWith("AUTH PLAIN "))
        check(
            String(Base64.getDecoder().decode(auth.substringAfterLast(' ')))
                .endsWith("\u0000password")
        )
        reply("235 Authenticated")
        try {
            while (true) {
                val line = reader.readLine() ?: break
                commands += line
                when {
                    line.startsWith("MAIL FROM:") -> reply("250 OK")
                    line.startsWith("RCPT TO:") ->
                        reply(
                            if (rejectRecipient != null && line.contains(rejectRecipient))
                                "550 No such recipient"
                            else "250 OK"
                        )
                    line == "RSET" -> reply("250 Reset")
                    line == "DATA" -> {
                        reply("354 Continue")
                        while (true) {
                            val part = reader.readLine() ?: error("Truncated DATA")
                            if (part == ".") break
                            commands += part
                        }
                        if (dropAfterData) break
                        reply("250 Accepted")
                    }
                    line == "QUIT" -> {
                        reply("221 Bye")
                        break
                    }
                    else -> error("Unexpected SMTP command: $line")
                }
            }
        } finally {
            socket.close()
        }
    }
}

/** Small strict IMAP transcript for greeting/authentication, folder state and IDLE. */
class ImapTranscript(
    private val idleEnabled: Boolean = true,
    private val fragmented: Boolean = false,
) {
    val idling = CountDownLatch(1)
    val change = CountDownLatch(1)
    val commands = mutableListOf<String>()

    fun serve(socket: Socket) {
        val reader = socket.inputStream.bufferedReader(Charsets.US_ASCII)
        val output = socket.outputStream
        fun reply(value: String) {
            val bytes = (value + "\r\n").toByteArray(Charsets.US_ASCII)
            if (fragmented)
                bytes.forEach {
                    output.write(it.toInt())
                    output.flush()
                }
            else {
                output.write(bytes)
                output.flush()
            }
        }
        val capabilities = "IMAP4rev1 AUTH=PLAIN" + if (idleEnabled) " IDLE" else ""
        reply("* OK [CAPABILITY $capabilities] localhost ready")
        while (true) {
            val line = reader.readLine() ?: break
            val tag = line.substringBefore(' ')
            val command = line.substringAfter(' ')
            commands += command.substringBefore(' ') // Do not record credentials.
            when {
                command == "CAPABILITY" -> {
                    reply("* CAPABILITY $capabilities")
                    reply("$tag OK capability")
                }
                command.startsWith("AUTHENTICATE PLAIN") -> {
                    reply("+")
                    check(
                        String(Base64.getDecoder().decode(reader.readLine()))
                            .endsWith("\u0000password")
                    )
                    reply("$tag OK authenticated")
                }
                command.startsWith("LSUB ") -> {
                    reply("* LSUB () \"/\" \"INBOX\"")
                    reply("$tag OK subscribed")
                }
                command.startsWith("LIST ") -> {
                    reply("* LIST (\\HasNoChildren) \"/\" \"INBOX\"")
                    reply("$tag OK listed")
                }
                command.startsWith("EXAMINE ") || command.startsWith("SELECT ") -> {
                    reply("* FLAGS (\\Seen \\Flagged \\Deleted)")
                    reply("* 0 EXISTS")
                    reply("* 0 RECENT")
                    reply("* OK [UIDVALIDITY 77] generation")
                    reply("* OK [UIDNEXT 1] next")
                    reply("$tag OK [READ-ONLY] selected")
                }
                command.startsWith("SEARCH ") -> {
                    reply("* SEARCH")
                    reply("$tag OK search")
                }
                command == "IDLE" -> {
                    check(idleEnabled)
                    reply("+ idling")
                    idling.countDown()
                    check(change.await(10, TimeUnit.SECONDS)) { "Test did not signal a change" }
                    reply("* 1 EXISTS")
                    check(reader.readLine() == "DONE")
                    reply("$tag OK idle finished")
                }
                command == "NOOP" || command == "CLOSE" || command == "UNSELECT" ->
                    reply("$tag OK done")
                command == "LOGOUT" -> {
                    reply("* BYE closing")
                    reply("$tag OK logout")
                    break
                }
                else -> error("Unexpected IMAP command: $command")
            }
        }
    }
}
