package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.foxred.kage.core.account.*
import org.foxred.kage.core.demo.DemoMailStore
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.setup.RealAccountSetup
import org.foxred.kage.data.setup.ServerSuggestions
import org.foxred.kage.data.setup.SetupConnectionException
import org.foxred.kage.data.setup.ProviderAutoconfig
import org.foxred.kage.data.sync.AccountSessions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealAccountSetupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val credentials by lazy { AndroidCredentialStore(context, "real-setup-test") }
    private lateinit var db: MailDatabase
    private lateinit var repository: RemoteMailRepository
    private var repositorySessions = 0

    @Before fun start() {
        credentials.clear()
        repositorySessions = 0
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        repository = RemoteMailRepository(db,
            AccountSessions(RemoteMailRepository.incomingServer(db), credentials) {
                repositorySessions++
                DemoMailStore()
            },
            credentials, DurableOutbox(db, context, AngusMimeCodec()))
    }

    @After fun stop() { db.close(); credentials.clear() }

    private fun account() = Account(
        "real", "Real", listOf(EmailAddress("setup@example.test")),
        Server("imap.example.test", 993, ServerProtocol.IMAP, username = "setup@example.test"),
        Server("smtp.example.test", 465, ServerProtocol.SMTP, username = "setup@example.test"),
    )

    @Test fun suggestedGmailSettingsUseSecurePortsAndManualProvidersStayUnspecified() {
        val (incoming, outgoing) = ServerSuggestions.gmail(" KAGE.TEST-ONLY.AUTOCONFIG@GMAIL.COM ")!!
        assertEquals("imap.gmail.com", incoming.hostname)
        assertEquals(993, incoming.port)
        assertEquals("smtp.gmail.com", outgoing.hostname)
        assertEquals(465, outgoing.port)
        assertEquals(ConnectionSecurity.TLS, outgoing.security)
        assertNull(ServerSuggestions.gmail("real@other.test"))
    }

    @Test fun providerAutoconfigUsesHttpsAndSubstitutesUsernames() = runBlocking {
        val visited = mutableListOf<String>()
        val xml = """<clientConfig version="1.1"><emailProvider id="other.test">
            <incomingServer type="imap"><hostname>imap.other.test</hostname><port>993</port>
            <socketType>SSL</socketType><authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username></incomingServer>
            <outgoingServer type="smtp"><hostname>smtp.other.test</hostname><port>587</port>
            <socketType>STARTTLS</socketType><authentication>password-cleartext</authentication>
            <username>%EMAILLOCALPART%</username></outgoingServer>
            </emailProvider></clientConfig>"""
        val discovery = ProviderAutoconfig { url ->
            visited += url
            if (visited.size == 2) xml else null
        }
        val result = discovery.discover("user@other.test")!!
        assertEquals(2, visited.size)
        assertTrue(visited.all { it.startsWith("https://") })
        assertEquals("user@other.test", result.first.username)
        assertEquals(ConnectionSecurity.TLS, result.first.security)
        assertEquals("user", result.second.username)
        assertEquals(ConnectionSecurity.STARTTLS, result.second.security)
    }

    @Test fun autoconfigRejectsPlaintextServersAndFallsBackToManual() = runBlocking {
        val xml = """<clientConfig><emailProvider>
            <incomingServer type="imap"><hostname>imap.other.test</hostname><port>143</port>
            <socketType>plain</socketType><authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username></incomingServer>
            <outgoingServer type="smtp"><hostname>smtp.other.test</hostname><port>25</port>
            <socketType>plain</socketType><authentication>password-cleartext</authentication>
            <username>%EMAILADDRESS%</username></outgoingServer>
            </emailProvider></clientConfig>"""
        assertNull(ProviderAutoconfig { xml }.discover("user@other.test"))
    }

    @Test fun incomingFailureDoesNotSaveCredentialsOrTrySmtp() = runBlocking {
        var smtpCalls = 0
        val setup = RealAccountSetup(repository, {
            object : MailStore by DemoMailStore() {
                override fun connect(server: Server, authorization: Authorization) {
                    throw MailFailure(FailureKind.AUTHENTICATION, "Wrong app password")
                }
            }
        }, { _, _ -> smtpCalls++ })
        val failure = runCatching { setup.add(account(), Authorization("wrong"), Authorization("wrong")) }.exceptionOrNull()
        assertEquals(ServerProtocol.IMAP, (failure as SetupConnectionException).protocol)
        assertEquals(0, smtpCalls)
        assertNull(db.remoteMailDao().account("real"))
        assertNull(credentials.authorization("real", ServerProtocol.IMAP))
    }

    @Test fun smtpFailureDoesNotSaveVerifiedIncomingCredential() = runBlocking {
        val setup = RealAccountSetup(repository, { DemoMailStore() }, { _, _ ->
            throw MailFailure(FailureKind.AUTHENTICATION, "SMTP credentials rejected")
        })
        val failure = runCatching { setup.add(account(), Authorization("good"), Authorization("wrong")) }.exceptionOrNull()
        assertEquals(ServerProtocol.SMTP, (failure as SetupConnectionException).protocol)
        assertNull(db.remoteMailDao().account("real"))
        assertNull(credentials.authorization("real", ServerProtocol.IMAP))
        assertNull(credentials.authorization("real", ServerProtocol.SMTP))
    }

    @Test fun validatedAccountPersistsBothCredentialsAndInbox() = runBlocking {
        var smtpCalls = 0
        val setup = RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> smtpCalls++ })
        val inboxId = setup.add(account(), Authorization("incoming"), Authorization("outgoing"))
        assertEquals(1, smtpCalls)
        assertEquals(repository.inboxFolder("real"), inboxId)
        assertNotNull(inboxId)
        assertEquals("Setup persists the validated folder list without reconnecting", 0, repositorySessions)
        assertEquals("REAL", db.remoteMailDao().account("real")?.mode)
        assertEquals("incoming", credentials.authorization("real", ServerProtocol.IMAP)?.secret)
        assertEquals("outgoing", credentials.authorization("real", ServerProtocol.SMTP)?.secret)
    }
}
