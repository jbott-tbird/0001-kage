// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AccountModelTest {
    @Test
    fun folderCountsFollowHierarchyAndServerDelimiter() {
        val child = Folder("a", Mailbox("Projects.Kage", '.', true, unreadEmails = 3), "Projects")
        val root =
            Folder("a", Mailbox("Projects", '.', true, unreadEmails = 2), null, listOf(child))
        assertEquals("Kage", child.name)
        assertEquals(5, root.aggregatedUnreadCount())
        assertNotEquals(child.id, child.copy(accountId = "b", id = "b:Projects.Kage").id)
    }

    @Test
    fun rolesUseProtocolAttributesRatherThanEnglishFolderNames() {
        assertEquals(MailboxRole.SENT, Mailbox("Gesendet", '/', true, setOf("\\Sent")).role)
        assertEquals(MailboxRole.INBOX, Mailbox("inbox", '/', true).role)
        assertNull(Mailbox("Archive", '/', true).role)
        assertNull(Mailbox("Archive", '/', true).rights.mayDelete)
    }

    @Test
    fun expiryIsInclusiveAndCredentialDescriptionNeverDisclosesSecrets() {
        val time = Instant.parse("2026-09-25T00:00:00Z")
        val auth = Authorization("access-secret", Authorization.Kind.OAUTH2, time, "refresh-secret")
        assertFalse(auth.isExpired(time.minusSeconds(1)))
        assertTrue(auth.isExpired(time))
        assertFalse(auth.toString().contains("access-secret"))
        assertFalse(auth.toString().contains("refresh-secret"))
    }
}
