package org.foxred.kage.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.foxred.kage.core.account.*
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.testkit.*
import org.junit.Assert.*
import org.junit.Test

class IdleIntegrationTest {
    private fun context() =
        testTlsContext(File(System.getProperty("greenmail.tls.keystore.file")).inputStream())

    @Test
    fun fragmentedResponsesAndIdleChangeCompleteWithoutLosingSession() {
        val transcript = ImapTranscript(fragmented = true)
        val pool = Executors.newSingleThreadExecutor()
        try {
            LoopbackServer(context(), transcript::serve).use { server ->
                AngusImapClient().use { client ->
                    client.connect(
                        Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                        Authorization("password"),
                    )
                    assertEquals("INBOX", client.mailboxes().single().name)
                    val wait = pool.submit { client.awaitChange("INBOX") }
                    assertTrue(transcript.idling.await(3, TimeUnit.SECONDS))
                    transcript.change.countDown()
                    wait.get(5, TimeUnit.SECONDS)
                    assertEquals(77L, client.poll("INBOX").uidValidity)
                }
                server.awaitCompletion()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun unavailableIdleHasExplicitPollingFallback() {
        val transcript = ImapTranscript(idleEnabled = false)
        LoopbackServer(context(), transcript::serve).use { server ->
            AngusImapClient().use { client ->
                client.connect(
                    Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                    Authorization("password"),
                )
                val failure = assertThrows(MailFailure::class.java) { client.awaitChange("INBOX") }
                assertEquals(FailureKind.PROTOCOL, failure.kind)
                assertEquals(77L, client.poll("INBOX").uidValidity)
            }
            server.awaitCompletion()
        }
    }

    @Test
    fun missingMoveCapabilityNeverChangesMailbox() {
        val transcript = ImapTranscript()
        LoopbackServer(context(), transcript::serve).use { server ->
            AngusImapClient().use { client ->
                client.connect(
                    Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                    Authorization("password"),
                )
                val failure =
                    assertThrows(MailFailure::class.java) {
                        client.move(MessageIdentity("INBOX", 77, 1), "Archive")
                    }
                assertEquals(FailureKind.PROTOCOL, failure.kind)
            }
            server.awaitCompletion()
        }
        assertFalse(
            transcript.commands.any { it in listOf("SELECT", "STORE", "COPY", "EXPUNGE", "MOVE") }
        )
    }

    @Test
    fun malformedGreetingFailsWithoutAnUnboundedRead() {
        LoopbackServer(context()) { socket ->
                socket.outputStream.write("invalid IMAP greeting\r\n".toByteArray())
                socket.outputStream.flush()
            }
            .use { server ->
                AngusImapClient(timeoutMillis = 500).use { client ->
                    val failure =
                        assertThrows(MailFailure::class.java) {
                            client.connect(
                                Server(
                                    "localhost",
                                    server.port,
                                    ServerProtocol.IMAP,
                                    username = "user",
                                ),
                                Authorization("password"),
                            )
                        }
                    assertEquals(FailureKind.CONNECTION, failure.kind)
                }
                server.awaitCompletion()
            }
    }
}
