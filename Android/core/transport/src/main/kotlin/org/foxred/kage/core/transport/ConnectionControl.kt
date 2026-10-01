// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.transport

import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * Closing the actual sockets interrupts Angus blocking reads without waiting for its session lock.
 */
class ConnectionControl {
    private val stopped = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val cancelled: Boolean
        get() = stopped.get()

    fun cancel() {
        stopped.set(true)
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun track(socket: Socket): Socket {
        sockets.removeIf { it.isClosed }
        sockets.add(socket)
        if (stopped.get()) {
            runCatching { socket.close() }
            sockets.remove(socket)
            throw SocketException("Mail operation cancelled")
        }
        return socket
    }

    fun install(properties: Properties, protocol: String) {
        val plain = SocketFactory.getDefault()
        properties["mail.$protocol.socketFactory"] =
            object : SocketFactory() {
                override fun createSocket(): Socket = track(plain.createSocket())

                override fun createSocket(host: String, port: Int): Socket =
                    track(plain.createSocket(host, port))

                override fun createSocket(
                    host: String,
                    port: Int,
                    local: InetAddress,
                    localPort: Int,
                ): Socket = track(plain.createSocket(host, port, local, localPort))

                override fun createSocket(host: InetAddress, port: Int): Socket =
                    track(plain.createSocket(host, port))

                override fun createSocket(
                    host: InetAddress,
                    port: Int,
                    local: InetAddress,
                    localPort: Int,
                ): Socket = track(plain.createSocket(host, port, local, localPort))
            }
        val tls = SSLSocketFactory.getDefault() as SSLSocketFactory
        properties["mail.$protocol.ssl.socketFactory"] =
            object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = tls.defaultCipherSuites

                override fun getSupportedCipherSuites(): Array<String> = tls.supportedCipherSuites

                override fun createSocket(): Socket = track(tls.createSocket())

                override fun createSocket(
                    socket: Socket,
                    host: String,
                    port: Int,
                    autoClose: Boolean,
                ): Socket = track(tls.createSocket(socket, host, port, autoClose))

                override fun createSocket(host: String, port: Int): Socket =
                    track(tls.createSocket(host, port))

                override fun createSocket(
                    host: String,
                    port: Int,
                    local: InetAddress,
                    localPort: Int,
                ): Socket = track(tls.createSocket(host, port, local, localPort))

                override fun createSocket(host: InetAddress, port: Int): Socket =
                    track(tls.createSocket(host, port))

                override fun createSocket(
                    host: InetAddress,
                    port: Int,
                    local: InetAddress,
                    localPort: Int,
                ): Socket = track(tls.createSocket(host, port, local, localPort))
            }
        properties.setProperty("mail.$protocol.socketFactory.fallback", "false")
        properties.setProperty("mail.$protocol.ssl.socketFactory.fallback", "false")
    }
}
