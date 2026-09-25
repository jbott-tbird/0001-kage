# Web to Android parity audit

Source of truth: `web/src/components/` and `web/src/lib/mail.ts`.
This checklist tracks the original offline UI prototype scope. Real provider
networking remains outside that scope for both apps.

## Verified baseline

- Native Material 3 screens for welcome/setup, mail, message, compose, settings.
- Three account fixtures, folders, filters, read/new markers, repository-backed actions.
- Room persistence and schema export; domain/repository/UI separation.
- Shared fonts/colors/spacing/shapes in DesignTokens.kt.
- Website moved into web/; web build and tests pass from that directory.

## Current parity work

- [x] Message selection, selected-message mark-read, oldest/newest ordering: native journey verifies reordering, selection, and preservation of other messages’ read/new states.
- [x] All-account result provenance: native journey verifies three account/folder labels. Unified search defaults to all accounts; runtime scope check remains part of final journey audit.
- [x] Drawer nested-folder expansion/collapse and account/unified unread badges. Native journey verifies collapse/expand before folder selection.
- [ ] Find-in-message previous/next navigation compared to web.
- [ ] Compose device-file attachment selection and rotation restoration compared to web.
- [ ] Setup authentication controls compared to web.
- [ ] Fixture content/folder assignments compared to the actual web adapter.
- [x] Automatic attachment policy applied on initialization and new-account creation. Room test verifies downloads, offline deferral, and catch-up when returning online.
- [ ] Message related-thread expansion and long-body controls compared to web.
- [ ] Final runtime comparison of complete user journeys and persisted state.

Existing build/test success proves the implemented baseline, not yet complete parity.

## Latest verification

Nine Android emulator tests passed on Kage_API30 after these changes. The debug
APK compiled as part of the connected-test task. Remaining unchecked items keep
the port goal open.
