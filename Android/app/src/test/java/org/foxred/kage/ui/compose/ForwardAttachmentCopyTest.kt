// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.compose

import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ForwardAttachmentCopyTest {
    @Test fun copyPublishesCompleteBytesAndReusesTheSavedCopy() = runBlocking {
        val directory = Files.createTempDirectory("forward-copy").toFile()
        try {
            val original = directory.resolve("source").apply { writeBytes(ByteArray(20_000) { it.toByte() }) }
            val target = copyForwardAttachment(original, "draft-part", maxBytes = 25_000)
            assertArrayEquals(original.readBytes(), target.readBytes())
            assertEquals(target, copyForwardAttachment(original, "draft-part", maxBytes = 25_000))
            assertEquals(setOf("source", "draft-part"), directory.listFiles()!!.map { it.name }.toSet())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun oversizedAndEscapingCopiesLeaveNoPublishedFile() = runBlocking {
        val directory = Files.createTempDirectory("forward-copy").toFile()
        try {
            val original = directory.resolve("source").apply { writeBytes(ByteArray(20_000)) }
            assertTrue(runCatching {
                copyForwardAttachment(original, "oversized", maxBytes = 10_000)
            }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching {
                copyForwardAttachment(original, "../outside", maxBytes = 25_000)
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(setOf("source"), directory.listFiles()!!.map { it.name }.toSet())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun sameLengthStaleCopyIsRejectedWithoutChangingEitherFile() = runBlocking {
        val directory = Files.createTempDirectory("forward-copy").toFile()
        try {
            val expected = ByteArray(20_000) { it.toByte() }
            val staleBytes = expected.copyOf().apply { this[9_000] = 42 }
            val original = directory.resolve("source").apply { writeBytes(expected) }
            val stale = directory.resolve("draft-part").apply { writeBytes(staleBytes) }
            assertTrue(runCatching {
                copyForwardAttachment(original, stale.name)
            }.exceptionOrNull() is IllegalArgumentException)
            assertArrayEquals(expected, original.readBytes())
            assertArrayEquals(staleBytes, stale.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationBeforeCallerResumesRemovesPublishedCopy() = runBlocking {
        val directory = Files.createTempDirectory("forward-copy").toFile()
        try {
            val original = directory.resolve("source").apply { writeBytes(ByteArray(20_000)) }
            val target = directory.resolve("draft-part")
            val copy = launch(start = CoroutineStart.UNDISPATCHED) {
                copyForwardAttachment(original, target.name)
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!target.exists() && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("IO copy never published", target.exists())
            copy.cancel()
            copy.join()
            assertFalse("Canceled copy was left untracked", target.exists())
            assertEquals(setOf("source"), directory.listFiles()!!.map { it.name }.toSet())
        } finally {
            directory.deleteRecursively()
        }
    }
}
