// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.content.Context
import java.net.URI
import java.time.Instant
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.foxred.kage.core.account.*
import org.foxred.kage.core.demo.DemoMailStore
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.local.AttachmentEntity
import org.foxred.kage.data.local.MessageEntity
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.data.security.OAuthGrantRevoker
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
            AccountSessions(RemoteMailRepository.incomingServer(db), credentials, factory = {
                repositorySessions++
                DemoMailStore()
            }),
            credentials, DurableOutbox(db, context, AngusMimeCodec()))
    }

    @After fun stop() { db.close(); credentials.clear() }

    private fun account() = Account(
        "real", "Real", listOf(EmailAddress("setup@example.test")),
        Server("imap.example.test", 993, ServerProtocol.IMAP, username = "setup@example.test"),
        Server("smtp.example.test", 465, ServerProtocol.SMTP, username = "setup@example.test"),
    )

    @Test fun suggestedGmailSettingsUseSecurePortsAndManualProvidersStayUnspecified() {
        // A Gmail suffix is required for this pure, network-free auto-detection check.
        val (incoming, outgoing) = ServerSuggestions.gmail(" KAGE.TEST-ONLY.AUTOCONFIG@GMAIL.COM ")!!
        assertEquals("imap.gmail.com", incoming.hostname)
        assertEquals(993, incoming.port)
        assertEquals("smtp.gmail.com", outgoing.hostname)
        assertEquals(465, outgoing.port)
        assertEquals(ConnectionSecurity.TLS, outgoing.security)
        assertNull(ServerSuggestions.gmail("real@other.test"))
    }

    @Test fun googleMigrationReadsTheStoredImapLoginWhenTheFirstIdentityIsAnAlias() = runBlocking {
        val aliased = account().copy(
            identities = listOf(EmailAddress("alias@example.test"),
                EmailAddress("primary@example.test")),
            incomingServer = account().incomingServer.copy(hostname = "imap.gmail.com",
                username = "primary@example.test"),
            outgoingServer = account().outgoingServer.copy(hostname = "smtp.gmail.com",
                username = "primary@example.test"),
        )
        val setup = RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> })
        setup.add(aliased, Authorization("old-imap"), Authorization("old-smtp"))

        assertEquals("primary@example.test", setup.googleAccountName("real"))
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

    @Test fun appPasswordAccountSwitchesToOauthWithoutDeletingCachedMail() = runBlocking {
        val gmail = account().copy(
            incomingServer = account().incomingServer.copy(hostname = "imap.gmail.com"),
            outgoingServer = account().outgoingServer.copy(hostname = "smtp.gmail.com"),
        )
        val setup = RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> })
        val folderId = checkNotNull(setup.add(gmail, Authorization("old-imap"),
            Authorization("old-smtp")))
        db.mailDao().saveMessages(listOf(MessageEntity(
            "cached", "real", folderId, "Sender", "sender@example.test", "setup@example.test",
            "", "", "Saved mail", "Body", null, "2026-01-01T00:00:00Z",
            false, false, false, false, false, null,
        )))
        val config = OAuthConfiguration("client-id",
            URI("https://accounts.google.com/o/oauth2/v2/auth"),
            URI("https://oauth2.googleapis.com/token"), URI("http://localhost/callback"),
            listOf("https://mail.google.com/"))
        val expires = Instant.parse("2099-01-01T00:00:00Z")
        setup.replaceAuthorization("real",
            Authorization("new-imap", Authorization.Kind.OAUTH2, expires),
            Authorization("new-smtp", Authorization.Kind.OAUTH2, expires), config)

        assertEquals("cached", db.mailDao().message("cached")?.id)
        assertEquals(folderId, repository.inboxFolder("real"))
        assertEquals(AuthenticationType.OAUTH2, repository.account("real")?.incomingServer?.authenticationType)
        assertEquals(AuthenticationType.OAUTH2, repository.account("real")?.outgoingServer?.authenticationType)
        assertEquals(config, repository.account("real")?.authConfig)
        assertEquals("new-imap", credentials.authorization("real", ServerProtocol.IMAP)?.secret)
        assertEquals("new-smtp", credentials.authorization("real", ServerProtocol.SMTP)?.secret)
        assertNull(credentials.authorization("real", ServerProtocol.IMAP)?.refreshToken)
    }

    @Test fun rejectedReplacementLeavesCachedAccountAndOldCredentials() = runBlocking {
        val initial = RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> })
        val folderId = initial.add(account(), Authorization("old-imap"),
            Authorization("old-smtp"))
        val rejecting = RealAccountSetup(repository, { DemoMailStore() }, { _, _ ->
            throw MailFailure(FailureKind.AUTHENTICATION, "Rejected")
        })
        val failure = runCatching {
            rejecting.replaceAuthorization("real", Authorization("new-imap"),
                Authorization("new-smtp"))
        }.exceptionOrNull()
        assertEquals(ServerProtocol.SMTP, (failure as SetupConnectionException).protocol)
        assertEquals(folderId, repository.inboxFolder("real"))
        assertEquals(AuthenticationType.PASSWORD,
            repository.account("real")?.incomingServer?.authenticationType)
        assertEquals("old-imap", credentials.authorization("real", ServerProtocol.IMAP)?.secret)
        assertEquals("old-smtp", credentials.authorization("real", ServerProtocol.SMTP)?.secret)
    }

    @Test fun googleTokenIsRejectedBeforeContactingANonGoogleMailbox() = runBlocking {
        val initial = RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> })
        initial.add(account(), Authorization("old-imap"), Authorization("old-smtp"))
        var probes = 0
        val setup = RealAccountSetup(repository, {
            probes++
            DemoMailStore()
        }, { _, _ -> probes++ })
        val config = OAuthConfiguration("client-id",
            URI("https://accounts.google.com/o/oauth2/v2/auth"),
            URI("https://oauth2.googleapis.com/token"), URI("http://localhost/callback"),
            listOf("https://mail.google.com/"))
        val grant = Authorization("google-token", Authorization.Kind.OAUTH2,
            Instant.parse("2099-01-01T00:00:00Z"))
        val failure = runCatching {
            setup.replaceAuthorization("real", grant, grant, config)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, probes)
        assertEquals("old-imap", credentials.authorization("real", ServerProtocol.IMAP)?.secret)
    }

    @Test fun googleTokenIsRejectedBeforeProbingAnUnsupportedTlsPort() = runBlocking {
        val gmail = account().copy(
            incomingServer = account().incomingServer.copy(hostname = "imap.gmail.com"),
            outgoingServer = account().outgoingServer.copy(hostname = "smtp.gmail.com",
                port = 2525),
        )
        RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> }).add(
            gmail, Authorization("old-imap"), Authorization("old-smtp"))
        var probes = 0
        val setup = RealAccountSetup(repository, {
            probes++
            DemoMailStore()
        }, { _, _ -> probes++ })
        val config = OAuthConfiguration("client-id",
            URI("https://accounts.google.com/o/oauth2/v2/auth"),
            URI("https://oauth2.googleapis.com/token"), URI("http://localhost/callback"),
            listOf("https://mail.google.com/"))
        val grant = Authorization("google-token", Authorization.Kind.OAUTH2,
            Instant.parse("2099-01-01T00:00:00Z"))

        assertTrue(runCatching {
            setup.replaceAuthorization("real", grant, grant, config)
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, probes)
        assertEquals("old-imap", credentials.authorization("real", ServerProtocol.IMAP)?.secret)
    }

    @Test fun failedGoogleRevocationKeepsTheAccountAndRetryRemovesItsCache() = runBlocking {
        val gmail = account().copy(
            incomingServer = account().incomingServer.copy(hostname = "imap.gmail.com"),
            outgoingServer = account().outgoingServer.copy(hostname = "smtp.gmail.com"),
        )
        val setup = RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> })
        val folderId = checkNotNull(setup.add(gmail, Authorization("old"), Authorization("old")))
        db.mailDao().saveMessages(listOf(MessageEntity(
            "cached-before-revoke", "real", folderId, "Sender", "sender@example.test",
            "setup@example.test", "", "", "Saved mail", "Body", null,
            "2026-01-01T00:00:00Z", false, false, false, false, false, null,
        )))
        val file = java.io.File(context.filesDir, "attachments/revoke-${System.nanoTime()}")
        file.parentFile?.mkdirs()
        file.writeText("cached attachment")
        db.remoteMailDao().saveAttachments(listOf(AttachmentEntity(
            "cached-attachment", "cached-before-revoke", "attachment.txt", "text/plain",
            file.length(), true, "", localFile = file.name,
        )))
        val config = OAuthConfiguration("client-id",
            URI("https://accounts.google.com/o/oauth2/v2/auth"),
            URI("https://oauth2.googleapis.com/token"), URI("http://localhost/callback"),
            listOf("https://mail.google.com/"))
        val grant = Authorization("access-token", Authorization.Kind.OAUTH2,
            Instant.parse("2099-01-01T00:00:00Z"))
        setup.replaceAuthorization("real", grant,
            Authorization("smtp-access-token", Authorization.Kind.OAUTH2,
                Instant.parse("2099-01-01T00:00:00Z")), config)
        var reject = true
        val revoked = mutableListOf<String>()
        val revokingRepository = RemoteMailRepository(db,
            AccountSessions(RemoteMailRepository.incomingServer(db), credentials,
                factory = { DemoMailStore() }),
            credentials, DurableOutbox(db, context, AngusMimeCodec()),
            grantRevoker = OAuthGrantRevoker { token ->
                revoked += token
                if (reject) error("Revocation unavailable")
            })
        val mail = RoomMailRepository(db, context, DemoMail(context), credentials,
            revokingRepository)
        assertNotNull(runCatching {
            mail.revokeAndRemoveGoogleAccount("real")
        }.exceptionOrNull())
        assertEquals("real", db.remoteMailDao().account("real")?.id)
        assertEquals("cached-before-revoke", db.mailDao().message("cached-before-revoke")?.id)
        assertTrue(file.isFile)
        assertNull(credentials.authorization("real", ServerProtocol.IMAP)?.refreshToken)

        reject = false
        mail.revokeAndRemoveGoogleAccount("real")
        assertEquals(listOf("access-token", "access-token", "smtp-access-token"), revoked)
        assertNull(db.remoteMailDao().account("real"))
        assertNull(db.mailDao().message("cached-before-revoke"))
        assertFalse(file.exists())
        assertNull(credentials.authorization("real", ServerProtocol.IMAP))
    }

    @Test fun accessOnlyGoogleGrantCanBeRevokedButExpiredGrantKeepsLocalMail() = runBlocking {
        val gmail = account().copy(
            incomingServer = account().incomingServer.copy(hostname = "imap.gmail.com"),
            outgoingServer = account().outgoingServer.copy(hostname = "smtp.gmail.com"),
        )
        val setup = RealAccountSetup(repository, { DemoMailStore() }, { _, _ -> })
        val folderId = checkNotNull(setup.add(gmail, Authorization("old"), Authorization("old")))
        db.mailDao().saveMessages(listOf(MessageEntity(
            "access-only-cached", "real", folderId, "Sender", "sender@example.test",
            "setup@example.test", "", "", "Saved mail", "Body", null,
            "2026-01-01T00:00:00Z", false, false, false, false, false, null,
        )))
        val config = OAuthConfiguration("client-id",
            URI("https://accounts.google.com/o/oauth2/v2/auth"),
            URI("https://oauth2.googleapis.com/token"), URI("http://localhost/callback"),
            listOf("https://mail.google.com/"))
        val current = Authorization("current-access", Authorization.Kind.OAUTH2,
            Instant.parse("2099-01-01T00:00:00Z"))
        setup.replaceAuthorization("real", current, current, config)
        val expired = Authorization("expired-access", Authorization.Kind.OAUTH2,
            Instant.parse("2000-01-01T00:00:00Z"))
        credentials.save("real", ServerProtocol.IMAP, expired)
        credentials.save("real", ServerProtocol.SMTP, expired)
        val revoked = mutableListOf<String>()
        val revokingRepository = RemoteMailRepository(db,
            AccountSessions(RemoteMailRepository.incomingServer(db), credentials,
                factory = { DemoMailStore() }),
            credentials, DurableOutbox(db, context, AngusMimeCodec()),
            grantRevoker = OAuthGrantRevoker { revoked += it })
        val mail = RoomMailRepository(db, context, DemoMail(context), credentials,
            revokingRepository)
        val cleanupDir = java.io.File(context.filesDir, "attachment-cleanup")
        fun pendingManifests() = cleanupDir.listFiles()?.filter { it.name.endsWith(".ready") }
            ?.map { it.name }?.toSet().orEmpty()
        val pendingBefore = pendingManifests()
        assertNotNull(runCatching {
            mail.revokeAndRemoveGoogleAccount("real")
        }.exceptionOrNull())
        assertTrue(revoked.isEmpty())
        assertEquals("access-only-cached", db.mailDao().message("access-only-cached")?.id)
        assertEquals(pendingBefore, pendingManifests())

        credentials.save("real", ServerProtocol.IMAP, current)
        credentials.save("real", ServerProtocol.SMTP, current)
        mail.revokeAndRemoveGoogleAccount("real")
        assertEquals(listOf("current-access"), revoked)
        assertNull(db.remoteMailDao().account("real"))
        assertNull(db.mailDao().message("access-only-cached"))
    }
}
