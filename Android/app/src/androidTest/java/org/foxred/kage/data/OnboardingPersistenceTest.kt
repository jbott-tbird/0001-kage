package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.seed.DemoMail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingPersistenceTest {
    private lateinit var db: MailDatabase
    private lateinit var repository: RoomMailRepository

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        repository = RoomMailRepository(db, context, DemoMail(context),
            AndroidCredentialStore(context, "onboarding-test"))
    }

    @After fun close() = db.close()

    @Test fun freshDemoInstallationStillNeedsOnboarding() = runBlocking {
        repository.initialize()
        assertFalse(repository.mailbox.first().preferences.started)
    }

    @Test fun existingRealAccountRepairsCompletionBeforeStartup() = runBlocking {
        repository.initialize()
        val dao = db.mailDao()
        val sample = dao.accounts().first().first()
        dao.insertAccounts(listOf(sample.copy(id = "real", address = "real@example.test", mode = "REAL")))
        assertFalse(dao.getPreferences()!!.started)
        repository.initialize()
        assertTrue(dao.getPreferences()!!.started)
        assertTrue(repository.mailbox.first().accounts.any { it.mode == "DEMO" })
    }

    @Test fun resettingDemoWithDemoFolderSelectedKeepsRealAccountCompletion() = runBlocking {
        repository.initialize()
        val dao = db.mailDao()
        val sample = dao.accounts().first().first()
        dao.insertAccounts(listOf(sample.copy(id = "real", address = "real@example.test", mode = "REAL")))
        repository.resetDemo()
        repository.initialize()
        assertTrue(dao.getPreferences()!!.started)
        assertTrue(repository.mailbox.first().accounts.any { it.id == "real" })
    }

    @Test fun demoOnlyInstallationClearsStaleCompletionOnStartup() = runBlocking {
        repository.initialize()
        val dao = db.mailDao()
        dao.savePreferences(dao.getPreferences()!!.copy(started = true))
        repository.initialize()
        assertFalse(dao.getPreferences()!!.started)
        assertTrue(repository.mailbox.first().accounts.isNotEmpty())
    }

    @Test fun removingLastRealAccountReturnsToOnboardingWithDemoAccountsRemaining() = runBlocking {
        repository.initialize()
        val dao = db.mailDao()
        val sample = dao.accounts().first().first()
        dao.insertAccounts(listOf(sample.copy(id = "real", address = "real@example.test", mode = "REAL")))
        repository.initialize()
        repository.removeAccount("real")
        assertFalse(dao.getPreferences()!!.started)
        assertTrue(repository.mailbox.first().accounts.isNotEmpty())
    }

    @Test fun removingOneOfTwoRealAccountsKeepsOnboardingComplete() = runBlocking {
        repository.initialize()
        val dao = db.mailDao()
        val sample = dao.accounts().first().first()
        dao.insertAccounts(listOf(sample.copy(id = "real-one", address = "one@example.test", mode = "REAL"),
            sample.copy(id = "real-two", address = "two@example.test", mode = "REAL")))
        repository.initialize()
        repository.removeAccount("real-one")
        assertTrue(dao.getPreferences()!!.started)
    }

    @Test fun emptyInstallationClearsStaleCompletionOnStartup() = runBlocking {
        repository.initialize()
        val dao = db.mailDao()
        dao.accounts().first().forEach { dao.removeAccount(it.id) }
        dao.savePreferences(dao.getPreferences()!!.copy(started = true))
        repository.initialize()
        assertFalse(dao.getPreferences()!!.started)
    }

}
