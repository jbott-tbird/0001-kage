# Web to Android parity audit

Scope: port the existing offline web UI in `web/src/components/` and its data
adapter in `web/src/lib/mail.ts` into the native Android app. Material 3 replaces
browser components. Live provider networking is not implemented by either prototype.

## Requirements and evidence

| Requirement | Implementation | Verification |
| --- | --- | --- |
| Prefilled account setup, IMAP/JMAP roadmap, ports/TLS/authentication, confirmation | `ui/setup/SetupScreens.kt`; Account model and Room entity | `setupIsPrefilledAndCreatesPopulatedMailbox` traverses the flow, changes authentication, and checks persisted result |
| Normal/custom/nested folders and multiple accounts | Exported fixtures; `ui/mail/AccountDrawer.kt` | Room seed test verifies all standard folders and exact parent assignments; drawer journey collapses/expands Travel and selects Drafts |
| New vs unread and four conjunctive filters | `domain/usecase/FilterMessages.kt`; `ui/mail/InboxScreen.kt` | Five domain tests and native filter journey; read-change tests preserve independent new state |
| Selected-message actions and sorting | Inbox selection state and native menu | `selectionMarksOnlyChosenMessagesAndSortCanBeReversed` verifies displayed order and isolated read updates |
| Current-account/all-account/unified search | Query model, search controls, result provenance | Domain scope tests; all-account and unified native search tests |
| Reader, details, archive/trash, reply/forward | `ui/message/MessageScreen.kt`, compose routes | Native reader runtime screenshot, Room move isolation test, source inspection of action bindings and compose initialization |
| Find within active email | Fixed find bar, highlight, next/previous, BringIntoViewRequester | Native reader test verifies next/previous wraparound and mark-unread; keyboard inset issue fixed during this test |
| Return to previous list position | Saved LazyListState | `backFromReaderRetainsListPosition` scrolls to old mail, opens it, and verifies visibility after back |
| Related messages and long-body reading | Thread badges and expandable related cards; scrollable full text / sandboxed HTML | `relatedMessagesExpandAndCollapse`; full reader screenshot. The current web reader has no load-more button, and both readers expose the entire body through scrolling |
| Compose, drafts, local send, attachments | Compose fields, repository writes, Android document picker and private-file imports | Native draft-save/reopen and picker/restoration tests; Room draft/send test verifies Sent placement and removed attachment rows |
| Offline and configurable attachment policy | Room preferences and repository cache policy | Automatic-download test covers newly created accounts, offline deferral, and online catch-up; cached seed attachments verified offline |
| Persisted state and safe upgrades | Room schema v2, migration from v1, application-scoped repository | Migration validates actual Room reopening and preserves v1 rows; disk reopen test preserves selected folder/read/flags/preferences; actual app process restart restores Archive |
| Shared mock content | `web/scripts/export-android-fixtures.cjs` exports evaluated web state and new-account template | Export yields 3 accounts / 66 messages; Room seed test checks message counts, subjects, and folder assignments |
| Material 3 and central design system | `DesignTokens.kt`, `Theme.kt`; native M3 controls; HTML typography uses the same tokens | Source inspection plus rendered welcome/inbox/filter/drawer/reader screenshots |
| Clean layers | Framework-free domain models/repository contract, pure filter use case, Room data/repository layer, ViewModel/UI and composition root | Source organization in architecture.html; compiler and Room schema generation pass |
| Website moved into web/ | Source/config/assets/scripts under `web/`, shared designs remain at root | All 11 web tests and production build pass from web/; fixture exporter runs there |

## Final evidence

- Android debug build succeeds.
- 5 domain unit tests pass.
- 17 Android emulator tests pass on Kage_API30 (Android 11).
- Android lint: 0 errors; remaining warnings are dependency-update and generated-asset notices.
- Web: 11 tests pass; production build succeeds.
- Installed app visually inspected in `screenshots/`: welcome, inbox, filters, drawer, message.
- Actual installed-app process was force-stopped and reopened; selected Archive folder restored.
- App left running in Inbox on the emulator.

All required offline website behaviors have native equivalents. Protocol transport,
server authentication, push/sync infrastructure, MIME ingestion, and production
provider downloads remain roadmap work, as they do in the website being ported.
