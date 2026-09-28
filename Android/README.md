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
./gradlew :app:connectedDebugAndroidTest  # emulator or device required
./gradlew :app:lintDebug
```

On this Mac, if Java is not on your path, prefix Gradle commands with:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
A dedicated `Kage_API30` emulator was created for verification.

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
to their folders, messages, and attachment rows. The schema is exported under
`app/schemas/`. Version 2 has an explicit migration from version 1 that preserves
existing mail and adds SMTP authentication, attachment storage, and preview fields.
Future schema changes also need explicit Room migrations. There is no
destructive migration fallback.

## Implemented

- Prefilled setup: credentials → IMAP/SMTP selection → ports/TLS → confirmation
- Three seeded accounts, standard folders, custom folders, nested Projects/Design
- Inbox with independent read/new state and unread/flagged/pinned/attachment filters
- Native modal drawer, message reading, details, archive/trash, flag/pin actions
- Plain text and sandboxed HTML rendering with shared typography/colors (JavaScript, remote loads, and file access disabled)
- Search selected account, all accounts, or active message text (highlight/count/previous/next)
- Compose, reply/all, forward, account selection, To/Cc/Bcc, draft save/discard
- Local send to Sent; native file picker, durable file attachments, and on-demand download/open
- Persistent selection, offline preview, attachment policy, unified/thread previews
- Account removal and reset with confirmation
- Emoji, long names/subjects, RTL, Japanese, missing subject/body, legacy encoding examples

## Deliberate prototype boundaries

This is an offline native prototype. Setup validates input and creates sample
mailboxes; it does not verify a provider. Passwords remain transient and are never
written to Room or saved-instance state. "Send" writes to local Sent only.
The system file picker imports selected file bytes into private app storage; draft
metadata survives saved-state restoration. The reader opens imported files or
bundled demonstration attachments through an installed viewer. The find view
highlights matches and provides previous/next controls that scroll to the active match.

The next production layer is an IMAP/SMTP transport behind repository interfaces,
with secure authentication/token storage, MIME parsing/sanitization, background
sync/work scheduling, conflict handling, notifications, real attachment downloads,
and provider integration tests. JMAP remains planned for v2. Related conversations
and unified inbox are opt-in previews. UI text is currently English; multilingual
mail fixtures exercise display behavior, not complete app localization.

[Architecture outline](docs/architecture.html)

## Verification

- Debug APK builds successfully.
- Five domain unit tests cover folder/account/unified search and all four filters.
- Seventeen emulator tests cover Room seeding, account isolation/removal, drafts,
  attachments, preferences, prefilled setup, native drawer/filters, reopening drafts, selection/sorting,
  search-result provenance, automatic attachment downloads, file picking and saved-state
  restoration, reader controls, unified search, database migration/reopening, and list position.
- Android lint passes with zero errors. Remaining warnings concern available
  dependency updates and Android Studio's generated launcher asset variants.
- The web app also passes all eleven tests and its production build from `web/`.

[Web parity audit](docs/port-audit.md) tracks the remaining behavior differences.

## Shared fixtures

Run `npm run export:android-fixtures` from `../web` after changing the web fixture
adapter. This exports its evaluated initial state (three accounts and 66 messages)
and new-account template to `app/src/main/assets/demo-mail.json`. Existing users’
Room data is preserved; Settings → Reset sample data reloads the latest fixtures.
