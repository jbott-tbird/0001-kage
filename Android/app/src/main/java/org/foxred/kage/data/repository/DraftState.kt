// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import org.foxred.kage.data.local.MessageEntity
import org.json.JSONObject

/** Local edits win over later server pages until remote draft upload is reconciled. */
internal fun locallyEditedDraft(row: MessageEntity): Boolean = row.draft &&
    (row.uid == null || JSONObject(row.envelopeJson).optBoolean("localDraftDirty"))
