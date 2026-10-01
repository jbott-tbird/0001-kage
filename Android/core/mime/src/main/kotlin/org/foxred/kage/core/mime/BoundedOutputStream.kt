// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.mime

import java.io.InterruptedIOException
import java.io.OutputStream
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure

/** A borrowed sink: limits writes without closing the caller's output. */
class BoundedOutputStream(private val output: OutputStream, private val limit: Long) :
    OutputStream() {
    init {
        require(limit > 0)
    }

    var count: Long = 0
        private set

    private fun checkWrite(length: Int) {
        if (Thread.currentThread().isInterrupted)
            throw InterruptedIOException("Mail copy interrupted")
        if (length.toLong() > limit - count)
            throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Message exceeds byte limit")
    }

    override fun write(value: Int) {
        checkWrite(1)
        output.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
        checkWrite(length)
        output.write(bytes, offset, length)
        count += length
    }

    override fun flush() = output.flush()
}
