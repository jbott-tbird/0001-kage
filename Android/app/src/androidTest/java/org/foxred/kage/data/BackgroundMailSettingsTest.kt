// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.foxred.kage.data.background.BackgroundMailJobService
import org.foxred.kage.data.background.BackgroundMailSettings
import org.foxred.kage.data.background.BackgroundCheck
import org.foxred.kage.data.background.BackgroundCheckResult
import org.foxred.kage.data.background.BackgroundUidCheckpoint
import org.foxred.kage.data.background.retryBackgroundFailure
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundMailSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun pending(): JobInfo? =
        context.getSystemService(JobScheduler::class.java).allPendingJobs.firstOrNull {
            it.service.className == BackgroundMailJobService::class.java.name
        }

    @Before fun clearBefore() {
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
    }

    @After fun clearAfter() {
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
    }

    @Test fun backgroundWorkIsOptInAndStopsForOfflinePreview() {
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = false, offline = false)
        assertTrue(BackgroundMailSettings.enabled(context))
        assertNull(pending())

        BackgroundMailSettings.reconcile(context, hasEligibleAccount = true, offline = false)
        val scheduled = requireNotNull(pending())
        assertTrue(scheduled.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, scheduled.networkType)
        assertTrue(scheduled.intervalMillis >= 60L * 60 * 1000)

        val beforeOffline = checkNotNull(BackgroundMailSettings.runToken(context))
        BackgroundMailSettings.reconcile(context, hasEligibleAccount = true, offline = true)
        assertNull(pending())
        val afterOffline = checkNotNull(BackgroundMailSettings.runToken(context))
        assertNotEquals(beforeOffline, afterOffline)
        assertFalse(BackgroundMailSettings.saveNotifiedCheckpoints(context, beforeOffline,
            mapOf("stale-inbox" to BackgroundUidCheckpoint(1L, 9L))))
        BackgroundMailSettings.reconcile(context, hasEligibleAccount = true, offline = false)
        assertNotNull(pending())
        assertEquals(afterOffline, BackgroundMailSettings.runToken(context))

        BackgroundMailSettings.reconcile(context, hasEligibleAccount = false, offline = false)
        assertNull(pending())
        assertNotEquals(afterOffline, BackgroundMailSettings.runToken(context))
        BackgroundMailSettings.reconcile(context, hasEligibleAccount = true, offline = false)
        assertNotNull(pending())

        BackgroundMailSettings.setNotificationsEnabled(context, true)
        val checkedAt = Instant.parse("2026-09-26T12:00:00Z")
        val token = checkNotNull(BackgroundMailSettings.runToken(context))
        assertTrue(BackgroundMailSettings.recordCheck(context, token,
            BackgroundCheckResult.SUCCESS, checkedAt))
        assertEquals(BackgroundCheck(checkedAt, BackgroundCheckResult.SUCCESS),
            BackgroundMailSettings.lastCheck(context))
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = true, offline = false)
        assertNull(pending())
        assertFalse(BackgroundMailSettings.enabled(context))
        assertFalse(BackgroundMailSettings.notificationsEnabled(context))
        assertNull(BackgroundMailSettings.lastCheck(context))
    }

    @Test fun aStoppedRunCannotRestoreCheckpointsAfterReenable() {
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = false, offline = false)
        val stoppedToken = checkNotNull(BackgroundMailSettings.runToken(context))
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = false, offline = false)
        val currentToken = checkNotNull(BackgroundMailSettings.runToken(context))
        assertNotEquals(stoppedToken, currentToken)

        assertFalse(BackgroundMailSettings.saveNotifiedCheckpoints(context, stoppedToken,
            mapOf("old-inbox" to BackgroundUidCheckpoint(7L, 42L))))
        assertFalse(BackgroundMailSettings.recordCheck(context, stoppedToken,
            BackgroundCheckResult.RETRYING))
        assertNull(BackgroundMailSettings.lastNotifiedUid(context, "old-inbox"))
        assertNull(BackgroundMailSettings.lastCheck(context))

        assertTrue(BackgroundMailSettings.saveNotifiedCheckpoints(context, currentToken,
            mapOf("new-inbox" to BackgroundUidCheckpoint(8L, 43L))))
        assertEquals(43L, BackgroundMailSettings.lastNotifiedUid(context, "new-inbox"))
    }

    @Test fun turningOffAlertsKeepsBackgroundRefreshAndItsCheckpoint() {
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = true, offline = false)
        val token = checkNotNull(BackgroundMailSettings.runToken(context))
        val checkedAt = Instant.parse("2026-09-26T12:00:00Z")
        assertTrue(BackgroundMailSettings.saveNotifiedCheckpoints(context, token,
            mapOf("inbox" to BackgroundUidCheckpoint(3L, 17L))))
        assertTrue(BackgroundMailSettings.recordCheck(context, token,
            BackgroundCheckResult.SUCCESS, checkedAt))

        BackgroundMailSettings.setNotificationsEnabled(context, true)
        BackgroundMailSettings.setNotificationsEnabled(context, false)

        assertTrue(BackgroundMailSettings.enabled(context))
        assertFalse(BackgroundMailSettings.notificationsEnabled(context))
        assertNotNull(pending())
        assertEquals(token, BackgroundMailSettings.runToken(context))
        assertEquals(BackgroundUidCheckpoint(3L, 17L),
            BackgroundMailSettings.lastNotifiedCheckpoint(context, "inbox"))
        assertEquals(BackgroundCheck(checkedAt, BackgroundCheckResult.SUCCESS),
            BackgroundMailSettings.lastCheck(context))
    }

    @Test fun notificationCheckpointTracksMailboxGenerationAndNeverRegressesWithinIt() {
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = false, offline = false)
        val token = checkNotNull(BackgroundMailSettings.runToken(context))
        fun save(generation: Long, uid: Long) =
            BackgroundMailSettings.saveNotifiedCheckpoints(context, token,
                mapOf("inbox" to BackgroundUidCheckpoint(generation, uid)))

        assertTrue(save(7L, 42L))
        assertTrue(save(7L, 40L))
        assertEquals(BackgroundUidCheckpoint(7L, 42L),
            BackgroundMailSettings.lastNotifiedCheckpoint(context, "inbox"))
        assertTrue(save(8L, 2L))
        assertEquals(BackgroundUidCheckpoint(8L, 2L),
            BackgroundMailSettings.lastNotifiedCheckpoint(context, "inbox"))
    }

    @Test fun onlyConnectionFailureRequestsEarlyRetry() {
        assertTrue(retryBackgroundFailure(MailFailure(FailureKind.CONNECTION, "offline")))
        assertFalse(retryBackgroundFailure(MailFailure(FailureKind.AUTHENTICATION, "expired")))
        assertFalse(retryBackgroundFailure(MailFailure(FailureKind.LIMIT_EXCEEDED, "storage")))
        assertFalse(retryBackgroundFailure(IllegalStateException("bad state")))
    }
}
