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
    fun rejectedFlagsAndMoveNeverReportSuccess() {
        val cases: List<Pair<String, (MailStore, MessageIdentity) -> Unit>> =
            listOf(
                "STORE" to { store, id -> store.markRead(id, true) },
                "STORE" to { store, id -> store.flag(id, true) },
                "MOVE" to { store, id -> store.move(id, "Archive") },
            )
        for ((command, action) in cases) {
            val transcript =
                ImapTranscript(
                    rejectedCommands = setOf(command),
                    singleMessage = true,
                    additionalCapabilities = "MOVE",
                )
            LoopbackServer(context(), transcript::serve).use { server ->
                AngusImapClient().use { client ->
                    client.connect(
                        Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                        Authorization("password"),
                    )
                    val failure =
                        assertThrows(MailFailure::class.java) {
                            action(client, MessageIdentity("INBOX", 77, 1))
                        }
                    assertEquals(command, FailureKind.PROTOCOL, failure.kind)
                    assertEquals(1, client.status("INBOX").messageCount)
                }
                server.awaitCompletion()
            }
        }
    }

    @Test
    fun idleCancellationClosesSocketAndReleasesSessionLock() {
        val transcript = ImapTranscript(idleUntilDisconnect = true)
        val pool = Executors.newSingleThreadExecutor()
        try {
            LoopbackServer(context(), transcript::serve).use { server ->
                AngusImapClient().use { client ->
                    client.connect(
                        Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                        Authorization("password"),
                    )
                    val waiting =
                        pool.submit<MailFailure> {
                            assertThrows(MailFailure::class.java) { client.awaitChange("INBOX") }
                        }
                    assertTrue(transcript.idling.await(3, TimeUnit.SECONDS))
                    client.cancel()
                    assertEquals(FailureKind.CANCELLED, waiting.get(3, TimeUnit.SECONDS).kind)
                    // This takes the session lock; a hung IDLE operation would prevent completion.
                    client.close()
                }
                server.awaitCompletion()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun rejectedMailboxCommandsRemainTypedAndLeaveConnectionUsable() {
        val cases: List<Pair<String, (MailStore) -> Unit>> =
            listOf(
                "LIST" to
                    {
                        it.mailboxes()
                        Unit
                    },
                "CREATE" to { it.createMailbox("New") },
                "RENAME" to { it.renameMailbox("INBOX", "Renamed") },
                "DELETE" to { it.deleteMailbox("INBOX") },
                "EXAMINE" to
                    {
                        it.status("INBOX")
                        Unit
                    },
                "NOOP" to
                    {
                        it.poll("INBOX")
                        Unit
                    },
                "APPEND" to
                    {
                        it.append("INBOX", "Subject: test\r\n\r\nBody".toByteArray())
                        Unit
                    },
            )
        for ((command, action) in cases) {
            val transcript = ImapTranscript(rejectedCommands = setOf(command))
            LoopbackServer(context(), transcript::serve).use { server ->
                AngusImapClient().use { client ->
                    client.connect(
                        Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                        Authorization("password"),
                    )
                    val failure =
                        assertThrows("$command should be rejected", MailFailure::class.java) {
                            action(client)
                        }
                    assertEquals(command, FailureKind.PROTOCOL, failure.kind)
                    if (command == "EXAMINE")
                        assertEquals("INBOX", client.mailboxes().single().name)
                    else assertEquals(77L, client.status("INBOX").uidValidity)
                }
                server.awaitCompletion()
            }
        }
    }

    @Test
    fun commandRejectionIsProtocolFailureAndSessionRemainsUsable() {
        val transcript = ImapTranscript(rejectSubscription = true)
        LoopbackServer(context(), transcript::serve).use { server ->
            AngusImapClient().use { client ->
                client.connect(
                    Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                    Authorization("password"),
                )
                for (subscribed in listOf(true, false)) {
                    val failure =
                        assertThrows(MailFailure::class.java) {
                            client.subscribe("INBOX", subscribed)
                        }
                    assertEquals(FailureKind.PROTOCOL, failure.kind)
                }
                assertEquals(77L, client.poll("INBOX").uidValidity)
            }
            server.awaitCompletion()
        }
    }

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
    fun uidplusMoveFallbackExpungesOnlyTheSelectedUid() {
        val transcript = ImapTranscript(singleMessage = true, additionalCapabilities = "UIDPLUS")
        LoopbackServer(context(), transcript::serve).use { server ->
            AngusImapClient().use { client ->
                client.connect(
                    Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                    Authorization("password"),
                )
                client.move(MessageIdentity("INBOX", 77, 1), "Archive")
            }
            server.awaitCompletion()
        }
        assertTrue(transcript.sawUidExpunge)
        assertFalse(transcript.commands.contains("EXPUNGE"))
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
