package org.foxred.kage.ui.compose

import androidx.compose.runtime.saveable.listSaver
import org.foxred.kage.domain.model.Attachment

/** Only small metadata is saved in the Bundle; imported bytes remain in app storage. */
internal val AttachmentListSaver =
    listSaver<List<Attachment>, String>(
        save = { attachments ->
            attachments.flatMap {
                listOf(
                    it.id,
                    it.messageId,
                    it.filename,
                    it.mimeType,
                    it.sizeBytes.toString(),
                    it.cached.toString(),
                    it.asset,
                    it.localFile.orEmpty(),
                )
            }
        },
        restore = { values ->
            values.chunked(8).map {
                Attachment(
                    it[0],
                    it[1],
                    it[2],
                    it[3],
                    it[4].toLong(),
                    it[5].toBoolean(),
                    it[6],
                    it[7].ifEmpty { null },
                )
            }
        },
    )
