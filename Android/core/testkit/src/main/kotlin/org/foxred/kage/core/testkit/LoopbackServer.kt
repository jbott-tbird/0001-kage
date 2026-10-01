// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.testkit

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** Test-only local socket server shared by JVM and Android protocol tests. Never forwards mail. */
class LoopbackServer(context: SSLContext? = null, handler: (Socket) -> Unit) : AutoCloseable {
    private val server =
        if (context == null) ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        else
            context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var peer: Socket? = null
    val port: Int
        get() = server.localPort

    private val finished =
        executor.submit {
            server.soTimeout = 10000
            server.accept().use { socket ->
                peer = socket
                socket.soTimeout = 10000
                if (socket is SSLSocket) socket.startHandshake()
                handler(socket)
            }
        }

    fun awaitCompletion() {
        finished.get(15, TimeUnit.SECONDS)
    }

    override fun close() {
        runCatching { peer?.close() }
        server.close()
        executor.shutdownNow()
    }
}

fun testTlsContext(identity: InputStream): SSLContext {
    val password = "test-password".toCharArray()
    val keys = KeyStore.getInstance("PKCS12").apply { identity.use { load(it, password) } }
    val km =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, password)
        }
    val tm =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keys)
        }
    return SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
}
