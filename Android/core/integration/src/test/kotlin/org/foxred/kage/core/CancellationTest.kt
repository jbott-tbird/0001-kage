// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.foxred.kage.core.account.*
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.junit.Assert.*
import org.junit.Test

class CancellationTest {
    /** Accept TCP but never send the greeting; cancellation must close the socket, not wait 15s. */
    private fun stalledConnection(cancel: () -> Unit, connect: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(2)
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val accepted = CountDownLatch(1)
            val peer =
                pool.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        accepted.countDown()
                        socket.inputStream.read() == -1
                    }
                }
            val operation =
                pool.submit<MailFailure> {
                    assertThrows(MailFailure::class.java) { connect(server.localPort) }
                }
            try {
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                cancel()
                assertEquals(FailureKind.CANCELLED, operation.get(3, TimeUnit.SECONDS).kind)
                assertTrue(peer.get(3, TimeUnit.SECONDS))
            } finally {
                cancel()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun cancellingImapInterruptsBlockedGreeting() {
        val client = AngusImapClient()
        stalledConnection(client::cancel) {
            client.connect(
                Server("localhost", it, ServerProtocol.IMAP, ConnectionSecurity.STARTTLS, "test"),
                Authorization("password"),
            )
        }
        client.close()
    }

    @Test
    fun cancellingSmtpBeforeSubmissionIsNotAnUncertainDelivery() {
        val client = AngusSmtpClient()
        stalledConnection(client::cancel) {
            client.send(
                Server("localhost", it, ServerProtocol.SMTP, ConnectionSecurity.STARTTLS, "test"),
                Authorization("password"),
                OutgoingEmail(
                    "<cancel@example.net>",
                    EmailAddress("from@example.net"),
                    listOf(EmailAddress("to@example.net")),
                    subject = "Cancel",
                    body = EmailBody("Body", null),
                ),
            )
        }
    }

    @Test
    fun configuredReadTimeoutBoundsAnUnresponsivePeer() {
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val peer =
                pool.submit {
                    server.accept().use {
                        it.soTimeout = 5000
                        it.inputStream.read()
                    }
                }
            try {
                val failure =
                    assertThrows(MailFailure::class.java) {
                        AngusImapClient(timeoutMillis = 250).use { client ->
                            client.connect(
                                Server(
                                    "localhost",
                                    server.localPort,
                                    ServerProtocol.IMAP,
                                    ConnectionSecurity.STARTTLS,
                                    "test",
                                ),
                                Authorization("password"),
                            )
                        }
                    }
                assertEquals(FailureKind.CONNECTION, failure.kind)
                peer.get(3, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
