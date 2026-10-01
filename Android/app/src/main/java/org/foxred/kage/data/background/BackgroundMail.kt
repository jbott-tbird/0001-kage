// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.foxred.kage.KageApplication
import org.foxred.kage.MainActivity
import org.foxred.kage.data.local.RemoteMailDao
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.domain.model.Account
import org.foxred.kage.domain.repository.MailRepository

enum class BackgroundCheckResult { SUCCESS, RETRYING, NEEDS_ATTENTION }

data class BackgroundCheck(val completedAt: Instant, val result: BackgroundCheckResult)

internal data class BackgroundUidCheckpoint(val uidValidity: Long, val uid: Long)

/** Permanent account/content failures wait for the next periodic check, not backoff retries. */
internal fun retryBackgroundFailure(failure: Exception): Boolean =
    failure is MailFailure && failure.kind == FailureKind.CONNECTION

/** Google access tokens are foreground-only until background authorization has a backend. */
fun backgroundMailEligible(account: Account): Boolean =
    account.mode == "REAL" && !account.usesOAuth

/** The user controls periodic network work separately from mailbox display preferences. */
object BackgroundMailSettings {
    private const val FILE = "background-mail"
    private const val ENABLED = "enabled"
    private const val RUN_TOKEN = "run-token"
    private const val NOTIFICATIONS = "notifications"
    private const val LAST_CHECK_AT = "last-check-at"
    private const val LAST_CHECK_RESULT = "last-check-result"
    private const val UID_PREFIX = "notified-uid:"
    private const val GENERATION_PREFIX = "notified-generation:"
    private const val JOB_ID = 12001
    private const val INTERVAL = 60L * 60 * 1000
    private const val FLEX = 15L * 60 * 1000
    private const val CHANNEL = "new-mail"
    private val lock = Any()

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, false)

    /** An old job cannot write into a later opt-in period after disable and re-enable. */
    internal fun runToken(context: Context): String? = synchronized(lock) {
        preferences(context).let { saved ->
            if (!saved.getBoolean(ENABLED, false)) null
            else saved.getString(RUN_TOKEN, null) ?: "legacy"
        }
    }

    private fun current(context: Context, token: String): Boolean = runToken(context) == token

    fun notificationsEnabled(context: Context): Boolean =
        preferences(context).getBoolean(NOTIFICATIONS, false)

    /** A fixed outcome label keeps protocol errors and account details out of support UI. */
    fun lastCheck(context: Context): BackgroundCheck? {
        val saved = preferences(context)
        if (!saved.contains(LAST_CHECK_AT)) return null
        val result = runCatching {
            BackgroundCheckResult.valueOf(saved.getString(LAST_CHECK_RESULT, "").orEmpty())
        }.getOrNull() ?: return null
        return BackgroundCheck(Instant.ofEpochMilli(saved.getLong(LAST_CHECK_AT, 0)), result)
    }

    internal fun recordCheck(context: Context, token: String, result: BackgroundCheckResult,
        completedAt: Instant = Instant.now()): Boolean = synchronized(lock) {
        if (!current(context, token)) return@synchronized false
        check(preferences(context).edit()
            .putLong(LAST_CHECK_AT, completedAt.toEpochMilli())
            .putString(LAST_CHECK_RESULT, result.name)
            .commit()) { "Could not save background check status" }
        true
    }

    fun setEnabled(context: Context, value: Boolean, hasEligibleAccount: Boolean,
        offline: Boolean) = synchronized(lock) {
        if (value) {
            val savedPreferences = preferences(context)
            val previousEnabled = savedPreferences.getBoolean(ENABLED, false)
            val previousToken = savedPreferences.getString(RUN_TOKEN, null)
            val saved = savedPreferences.edit().putBoolean(ENABLED, true)
                .putString(RUN_TOKEN, UUID.randomUUID().toString()).commit()
            check(saved) { "Could not save background refresh setting" }
            try {
                reconcile(context, true, hasEligibleAccount, offline)
            } catch (failure: Exception) {
                val restore = savedPreferences.edit().putBoolean(ENABLED, previousEnabled)
                if (previousToken == null) restore.remove(RUN_TOKEN)
                else restore.putString(RUN_TOKEN, previousToken)
                if (!restore.commit())
                    failure.addSuppressed(IllegalStateException(
                        "Could not restore background refresh setting"))
                if (!previousEnabled) scheduler(context).cancel(JOB_ID)
                throw failure
            }
        } else {
            check(preferences(context).edit().clear().putBoolean(ENABLED, false).commit()) {
                "Could not save background refresh setting"
            }
            scheduler(context).cancel(JOB_ID)
            context.getSystemService(NotificationManager::class.java).cancel(JOB_ID)
        }
    }

    fun setNotificationsEnabled(context: Context, value: Boolean) = synchronized(lock) {
        check(preferences(context).edit().putBoolean(NOTIFICATIONS, value).commit()) {
            "Could not save notification setting"
        }
        if (!value) context.getSystemService(NotificationManager::class.java).cancel(JOB_ID)
    }

    /** Keep one persisted, network-constrained job; rechecking does not restart its interval. */
    fun reconcile(context: Context, hasEligibleAccount: Boolean, offline: Boolean) =
        synchronized(lock) {
            reconcile(context, enabled(context), hasEligibleAccount, offline)
        }

    private fun reconcile(context: Context, requested: Boolean,
        hasEligibleAccount: Boolean, offline: Boolean) {
        val jobs = scheduler(context)
        if (!requested || !hasEligibleAccount || offline) {
            // JobScheduler cancellation may race an active worker's final checkpoint write.
            if (requested && enabled(context))
                check(preferences(context).edit()
                    .putString(RUN_TOKEN, UUID.randomUUID().toString()).commit()) {
                    "Could not stop the previous background refresh"
                }
            jobs.cancel(JOB_ID)
            return
        }
        if (jobs.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID,
            ComponentName(context, BackgroundMailJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .setPeriodic(INTERVAL, FLEX)
            .build()
        check(jobs.schedule(job) == JobScheduler.RESULT_SUCCESS) {
            "Android could not schedule background refresh"
        }
    }

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

    internal fun lastNotifiedUid(context: Context, folderId: String): Long? =
        preferences(context).let { saved ->
            val key = UID_PREFIX + folderId
            if (saved.contains(key)) saved.getLong(key, 0) else null
        }

    /** Old UID-only checkpoints become a first-run baseline for the current generation. */
    internal fun lastNotifiedCheckpoint(context: Context, folderId: String): BackgroundUidCheckpoint? =
        preferences(context).let { saved ->
            val generationKey = GENERATION_PREFIX + folderId
            val uidKey = UID_PREFIX + folderId
            if (!saved.contains(generationKey) || !saved.contains(uidKey)) null
            else BackgroundUidCheckpoint(saved.getLong(generationKey, 0), saved.getLong(uidKey, 0))
        }

    internal fun saveNotifiedCheckpoints(context: Context, token: String,
        values: Map<String, BackgroundUidCheckpoint>): Boolean = synchronized(lock) {
        if (!current(context, token)) return@synchronized false
        val saved = preferences(context)
        val edit = saved.edit()
        values.forEach { (folderId, checkpoint) ->
            val prior = lastNotifiedCheckpoint(context, folderId)
            val uid = if (prior?.uidValidity == checkpoint.uidValidity)
                maxOf(prior.uid, checkpoint.uid) else checkpoint.uid
            edit.putLong(GENERATION_PREFIX + folderId, checkpoint.uidValidity)
                .putLong(UID_PREFIX + folderId, uid)
        }
        check(edit.commit()) { "Could not save background mail checkpoint" }
        true
    }

    internal fun notifyNewMail(context: Context, token: String) = synchronized(lock) {
        if (!current(context, token) || !notificationsEnabled(context) ||
            (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED)) return@synchronized
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "New mail",
            NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(JOB_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("New mail")
            .setContentText("Open Kage to read it")
            .setContentIntent(intent)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build())
    }
}

/** A bounded foreground-style refresh reused by the periodic system job. */
class BackgroundMailRunner(
    private val repository: MailRepository,
    private val remote: RemoteMailRepository,
    private val dao: RemoteMailDao,
    private val now: () -> Instant = Instant::now,
    private val notify: (Context, String) -> Unit = BackgroundMailSettings::notifyNewMail,
) {
    private val runLock = Mutex()

    suspend fun run(context: Context): Boolean =
        run(context, BackgroundMailSettings.runToken(context))

    internal suspend fun run(context: Context, token: String?): Boolean {
        runLock.lock()
        return try { runLocked(context, token) }
        finally { runLock.unlock() }
    }

    private suspend fun runLocked(context: Context, token: String?): Boolean {
        if (token == null || BackgroundMailSettings.runToken(context) != token) return false
        val mail = repository.mailbox.first()
        if (mail.preferences.offline) return false
        val eligibleIds = mail.accounts.filter(::backgroundMailEligible).mapTo(HashSet()) { it.id }
        val inboxes = mail.folders.filter { it.role == "inbox" && it.accountId in eligibleIds }
        if (inboxes.isEmpty()) return false
        val checked = mutableMapOf<String, BackgroundUidCheckpoint>()
        var hasNewMail = false
        var firstFailure: Exception? = null
        var connectionFailure: Exception? = null
        suspend fun stillEligible(folderId: String, accountId: String): Boolean {
            if (BackgroundMailSettings.runToken(context) != token) return false
            val account = dao.account(accountId)
            val folder = dao.folder(folderId)
            return account?.mode == "REAL" && account.oauthConfigurationJson == null &&
                folder?.accountId == accountId && folder.role == "inbox" &&
                folder.remotePath != null
        }
        fun rememberFailure(failure: Exception) {
            if (firstFailure == null) firstFailure = failure
            if (connectionFailure == null && retryBackgroundFailure(failure))
                connectionFailure = failure
        }
        for (folder in inboxes) {
            currentCoroutineContext().ensureActive()
            if (!stillEligible(folder.id, folder.accountId)) return false
            val beforeGeneration = dao.folder(folder.id)?.uidValidity
            val before = dao.highestCachedUid(folder.id) ?: 0L
            val notified = BackgroundMailSettings.lastNotifiedCheckpoint(context, folder.id)
            val refreshed = try {
                remote.refreshVisibleFolder(folder.id, now().minus(Duration.ofDays(30)))
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                rememberFailure(failure)
                false
            }
            if (!stillEligible(folder.id, folder.accountId)) return false
            // A refresh can commit a page before a later network or body failure.
            // Observe the committed UID even when the pass needs a retry.
            try {
                val generation = dao.folder(folder.id)?.uidValidity
                val after = dao.highestCachedUid(folder.id) ?: 0L
                if (generation != null && generation > 0) {
                    val sameGeneration = notified?.uidValidity == generation &&
                        beforeGeneration == generation
                    if (sameGeneration && after > maxOf(notified.uid, before)) hasNewMail = true
                    // A partial first pass is not an authoritative initial baseline.
                    if (refreshed || notified?.uidValidity == generation)
                        checked[folder.id] = BackgroundUidCheckpoint(generation, after)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                rememberFailure(failure)
            }
        }
        if (repository.mailbox.first().preferences.offline) return false
        for (folder in inboxes)
            if (!stillEligible(folder.id, folder.accountId)) return false
        if (hasNewMail && BackgroundMailSettings.runToken(context) == token) {
            try {
                notify(context, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Refresh already committed; keep its UID checkpoint even if alerts fail.
                rememberFailure(failure)
            }
        }
        if (!BackgroundMailSettings.saveNotifiedCheckpoints(context, token, checked)) return false
        (connectionFailure ?: firstFailure)?.let { throw it }
        return true
    }
}

class BackgroundMailJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running: Job? = null
    private var runningToken: String? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val token = BackgroundMailSettings.runToken(this)
        val previous = running
        running = null
        runningToken = null
        previous?.cancel()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                val checked = withContext(Dispatchers.IO) {
                    (application as KageApplication).container.backgroundMailRunner.run(
                        this@BackgroundMailJobService, token)
                }
                if (checked && token != null)
                    withContext(Dispatchers.IO) {
                        runCatching { BackgroundMailSettings.recordCheck(
                            this@BackgroundMailJobService, token, BackgroundCheckResult.SUCCESS) }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                retry = retryBackgroundFailure(failure)
                if (token != null)
                    withContext(Dispatchers.IO) {
                        runCatching { BackgroundMailSettings.recordCheck(
                            this@BackgroundMailJobService, token,
                            if (retry) BackgroundCheckResult.RETRYING
                            else BackgroundCheckResult.NEEDS_ATTENTION) }
                    }
            } finally {
                if (running === coroutineContext[Job]) {
                    running = null
                    runningToken = null
                    if (BackgroundMailSettings.runToken(this@BackgroundMailJobService) != token)
                        retry = false
                    jobFinished(params, retry)
                }
            }
        }
        running = task
        runningToken = token
        // Finish on a later main-loop turn, after onStartJob has returned true.
        if (!mainHandler.post { if (running === task) task.start() }) {
            running = null
            runningToken = null
            task.cancel()
            return false
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val token = runningToken
        running?.cancel()
        running = null
        runningToken = null
        return token != null && BackgroundMailSettings.runToken(this) == token
    }

    override fun onDestroy() {
        running?.cancel()
        running = null
        runningToken = null
        scope.cancel()
        super.onDestroy()
    }
}
