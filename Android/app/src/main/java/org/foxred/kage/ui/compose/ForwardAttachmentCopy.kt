// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.compose

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Publish a complete private copy before a forwarded draft points at it. */
internal suspend fun copyForwardAttachment(
    original: File,
    targetName: String,
    maxBytes: Long = 25L * 1024 * 1024,
): File {
    var published: File? = null
    try {
        return withContext(Dispatchers.IO) {
            require(maxBytes > 0)
            val source = original.canonicalFile
            val directory = checkNotNull(source.parentFile).canonicalFile
            require(source.isFile) { "Download the attachment again before forwarding" }
            require(source.length() <= maxBytes) { "Forwarded attachment exceeds 25 MB" }
            val target = File(directory, targetName).canonicalFile
            require(target.parentFile == directory && target != source) {
                "Forwarded attachment path is outside private storage"
            }
            if (target.exists()) {
                require(target.isFile && target.length() == source.length()) {
                    "Forwarded attachment copy is incomplete; attach it again"
                }
                source.inputStream().use { sourceInput ->
                    target.inputStream().use { targetInput ->
                        val sourceBytes = ByteArray(8192)
                        val targetBytes = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = sourceInput.read(sourceBytes)
                            if (count < 0) break
                            var checked = 0
                            while (checked < count) {
                                val read = targetInput.read(targetBytes, checked, count - checked)
                                require(read > 0) {
                                    "Forwarded attachment copy changed; attach it again"
                                }
                                checked += read
                            }
                            for (index in 0 until count) require(sourceBytes[index] == targetBytes[index]) {
                                "Forwarded attachment copy changed; attach it again"
                            }
                        }
                        require(targetInput.read() < 0) {
                            "Forwarded attachment copy changed; attach it again"
                        }
                    }
                }
                return@withContext target
            }
            val temporary = File(directory, "$targetName.${UUID.randomUUID()}.tmp").canonicalFile
            require(temporary.parentFile == directory) {
                "Forwarded attachment path is outside private storage"
            }
            try {
                source.inputStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(8192)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= maxBytes) { "Forwarded attachment exceeds 25 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!temporary.renameTo(target))
                    throw IOException("Could not prepare forwarded attachment")
                published = target
                target
            } finally {
                temporary.delete()
            }
        }
    } catch (failure: Exception) {
        // A canceled return from the IO dispatcher can happen after rename but before
        // Compose records the copy for draft cleanup.
        published?.let {
            if (it.exists() && !it.delete())
                failure.addSuppressed(IOException("Could not remove untracked forwarded attachment"))
        }
        throw failure
    }
}
