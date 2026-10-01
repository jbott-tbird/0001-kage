# Kage Android

Native implementation of the mail prototype using Kotlin, Jetpack Compose,
Material 3, Room, coroutines/Flow, and Navigation Compose. Requires Android 11+
(API 30). Uses the existing Android Studio project's SDK and Gradle versions.

## Build and run

Open `./Android` in Android Studio and run `app`.
From this directory:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
python3 ../scripts/android_device_tests.py  # dedicated test emulator required
./gradlew :app:lintDebug
```

On this Mac, if Java is not on your path, prefix Gradle commands with:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
A dedicated `Kage_Isolated_Tests_API30` emulator is used for verification.

Instrumentation uses the normal **Kage** debug build (`org.foxred.kage`).
Isolation comes from the dedicated `Kage_Isolated_Tests_API30` emulator.
Start that AVD in Device Manager, then run:

```sh
python3 ../scripts/android_device_tests.py
# Optional targeted test:
python3 ../scripts/android_device_tests.py -Pandroid.testInstrumentationRunnerArguments.class=org.foxred.kage.ui.NavigationRecoveryTest
```

The launcher verifies the AVD name and pins Gradle to its current serial.
Direct `connectedDebugAndroidTest` runs also require `ANDROID_SERIAL` pointing
to that exact AVD; missing or incorrect targets are rejected before installation.
Do not run instrumentation on the Google sign-in emulator used for manual testing.
Automated Google authorization remains mocked.

## Google Mail authorization setup

The debug Android OAuth client ID is configured in `gradle.properties` for
package `org.foxred.kage` and this machine's debug signing certificate
(`<your signing certificate SHA-1>`).
Override `kageGoogleAndroidClientId` for builds signed with another certificate;
register each debug, pilot, and release certificate in Google Cloud. Google Mail
requires the `https://mail.google.com/` scope, consent configuration, and the
account under test on the OAuth test-user list. Public release may require scope
verification.

Google Play services handles account selection, consent, and foreground access
token renewal. Kage stores only the short-lived access token in Android
Keystore-backed private storage; new on-device refresh tokens are rejected.
An existing app-password Gmail account can switch to Google sign-in from its
Settings details without deleting cached mail or Outbox entries. Demo accounts
remain available in the drawer after connecting a real account. The drawer and
Settings now open real-account setup directly. The Google SDK build passes; live Gmail verification remains open.
Google authorization uses `imap.gmail.com:993` with TLS and either
`smtp.gmail.com:465` with TLS or `smtp.gmail.com:587` with required STARTTLS.
An account with other server settings must be corrected before switching.

## Organization

| Layer | Location | Responsibility |
| --- | --- | --- |
| Design system | `ui/theme/DesignTokens.kt` | Single source for fonts, colors, spacing, shapes, and component dimensions |
| Theme adapter | `ui/theme/Theme.kt` | Applies the tokens to Material 3, with light/dark system theme |
| UI | `ui/welcome`, `ui/account/auth`, `ui/accountlist`, `ui/emaildisplay`, `ui/compose`, `ui/settings` | Compose screens and native Material components |
| Presentation | `ui/MailViewModel.kt` | Lifecycle state, actions, errors, search/filter state |
| Navigation | `ui/navigation/KageApp.kt` | Welcome, setup, inbox, message, compose, settings routes |
| Domain | `domain/model`, `domain/repository`, `domain/usecase` | Framework-free models, repository contract, search/filter rules |
| Data | `data/local` | Room entities, indexed tables, cascading foreign keys, DAO, mappings |
| Repository | `data/repository/RoomMailRepository.kt` | Transactions, preferences, draft/send/move operations, cache policy |
| Fixtures | `data/seed`, `assets/demo-mail.json` | Initial accounts, standard/custom folders, varied example messages |
| Composition root | `di/AppContainer.kt`, `KageApplication.kt` | Application-scoped database and repository wiring |

The UI consumes domain models. Room entities stay in the data layer. Database
reads stream through Flow; writes use suspending DAO calls and transactions.
Seeding happens only for an uninitialized database. Removing accounts cascades
to their folders, messages, and attachment rows. The first complete Room schema
is version 1, exported under `app/schemas/`. It includes the current indexes and
chronological timestamp format. The database now uses `kage-mail-main.db`, so
prototype installs start with a fresh database and do not reuse incompatible
earlier files. Future schema changes need explicit Room migrations. There is no
destructive migration fallback.

## Implemented

- Prefilled setup: credentials → IMAP/SMTP selection → ports/TLS → confirmation
- Three seeded accounts, standard folders, custom folders, nested Projects/Design
- Inbox with independent read/new state and unread/flagged/pinned/attachment filters
- Native modal drawer, message reading, details, archive/trash, flag/pin actions
- Plain text and sandboxed HTML rendering with shared typography/colors (JavaScript, remote loads, and file access disabled)
- Search selected account, all accounts, or active message text (highlight/count/previous/next)
- Fixed-size folder and search result pages; the UI reads real mail bodies and attachments for the selected message
- Draft sync and automatic attachment caching scan bounded database pages
- Account removal stages attachment file cleanup so interrupted deletion can resume on next launch
- Compose, reply/all, forward, account selection, To/Cc/Bcc, draft save/discard
- Durable real-account Outbox and Sent reconciliation; native file picker, durable file attachments, and on-demand download/open
- Persistent selection, offline preview, attachment policy, unified/thread previews
- Opt-in periodic refresh for app-password inboxes with a separate generic new-mail notification setting
- Account removal and reset with confirmation
- Emoji, long names/subjects, RTL, Japanese, missing subject/body, legacy encoding examples

## Pilot boundaries

Sample accounts remain local. Real accounts use IMAP/SMTP and save credentials
encrypted with Android Keystore, outside Room and Android backup. Drafts, pending
actions, and queued MIME persist on the device. A disconnected send is reconciled
against Sent before retry. The system file picker imports selected files into
private app storage. The reader opens imported files or downloaded attachments
through an installed viewer.

Real Gmail and physical-device acceptance remain open. Full-history download now
has a separate resumable checkpoint and foreground controls, but still needs physical-device validation. Background refresh checks app-password inboxes roughly hourly
when Android permits, requires network and sufficient battery, and pauses in Offline
preview. Google-authorized accounts remain foreground-only until their background
authorization path is decided. Notifications require a second opt-in and show no sender, subject or body;
Android 13+ also asks for notification permission. The first background check sets
the notification baseline without alerting. Disabling background refresh clears
its notification setting and checkpoints. Settings shows the last check time and
a fixed success, connection-retry or needs-attention label without server errors
or message details. Scheduling, Doze, reboot and battery
behavior still need emulator and physical-device validation. Google account
authorization now has a foreground implementation but still needs registered
release client registration and live Gmail verification.
JMAP is planned for a later release. Related conversations
and unified inbox are opt-in
previews. UI text is currently English; multilingual mail fixtures exercise display
behavior, not complete app localization.

[Pilot recovery and support guide](docs/pilot-recovery.html)

[iOS ↔ Android architecture and code map](docs/architecture.html)

## Current validation

- Debug app and instrumentation APKs build successfully.
- All 54 app unit tests and 84 core tests pass, including OAuth protocol and IMAP pagination coverage.
- Android lint passes with zero errors and 12 warnings.
- All eight Python tooling tests, mail provenance verification, and the Room schema check pass.
- The SQLite benchmark smoke test completes with 10,000 synthetic messages.
- Dedicated API 30 emulator: 250 tests, 15 failures, 0 errors, 0 skipped. This checkpoint does not pass device acceptance.

Failing instrumentation cases (follow-up required):

- `DurableOutboxTest.cancellationDuringSmtpConnectionLeavesTheClaimQueued`
- `RemoteMailRepositoryTest.shortenedLocalDraftAttachmentCannotBeOpenedSavedOrQueued`
- `RemoteMailRepositoryTest.fullScanRemovesExpungesOnlyAfterResumedPassCompletes`
- `RoomMailRepositoryTest.reopeningDatabaseKeepsLastFolderReadStateAndPreferences`
- `MailJourneyTest.readerAlignsArabicAndHebrewParagraphsByContent`
- `MailJourneyTest.unifiedInboxSearchUsesAllAccounts`
- `MailJourneyTest.backFromReaderRetainsListPosition`
- `MailJourneyTest.allAccountSearchShowsAccountAndFolderForResults`
- `MailJourneyTest.relatedMessagesExpandAndCollapse`
- `MailJourneyTest.outboxPagerReachesOlderFailedSend`
- `MessageScrollingTest.bodySwipesMoveHeaderAndEmailTogether`
- `RealSetupJourneyTest.failedValidationStaysOnSetupThenRetryOpensRealInbox`
- `RealSetupJourneyTest.settingsReplacesAppPasswordWithoutDeletingCachedMail`
- `RealSetupJourneyTest.acceptedSendOffersAnExplicitSentCopyForOtherSmtpServers`
- `RealSetupJourneyTest.realComposeQueuesMailWhileOfflineWithoutSubmittingSmtp`

- Live Google sign-in, real-mail sending, and physical-device behavior remain manual acceptance checks.

## Earlier prototype verification

- A debug APK built at the earlier prototype checkpoint; current mail-engine changes still require an Android build and device run.
- Five domain unit tests cover folder/account/unified search and all four filters.
- Seventeen emulator tests cover Room seeding, account isolation/removal, drafts,
  attachments, preferences, prefilled setup, native drawer/filters, reopening drafts, selection/sorting,
  search-result provenance, automatic attachment downloads, file picking and saved-state
  restoration, reader controls, unified search, database baseline/reopening, and list position.
- Android lint passes with zero errors. Remaining warnings concern available
  dependency updates and Android Studio's generated launcher asset variants.
- The web app also passes all eleven tests and its production build from `web/`.

[Web parity audit](docs/port-audit.md) tracks the remaining behavior differences.

## Shared fixtures

Run `npm run export:android-fixtures` from `../web` after changing the web fixture
adapter. This exports its evaluated initial state (three accounts and 66 messages)
and new-account template to `app/src/main/assets/demo-mail.json`. Existing users’
Room data is preserved; Settings → Reset sample data reloads the latest fixtures.
