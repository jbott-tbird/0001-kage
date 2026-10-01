// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Native mkdir retains ENOSPC; File.mkdirs() only reports a failed boolean. */
internal fun ensurePrivateDirectory(directory: File) {
    if (directory.isDirectory) return
    try { Os.mkdir(directory.absolutePath, 0x1C0) }
    catch (error: ErrnoException) {
        if (error.errno != OsConstants.EEXIST || !directory.isDirectory) throw error
    }
}

/** A full Room database or private file store needs the same free-space recovery path. */
internal fun storageExhausted(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { cause ->
        cause is SQLiteFullException ||
            (cause is ErrnoException && cause.errno == OsConstants.ENOSPC)
    }
