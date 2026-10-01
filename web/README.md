# Mail app UI prototype

A responsive React website based on the supplied Thunderbird designs. Includes local mock data; account setup and mail actions do not contact a provider or send email.

## Run locally

From `./web`:

```sh
npm install
npm run dev
```

The development server is not started automatically. Open the address printed by Vite in your browser.

```sh
npm run build  # TypeScript check and production output in dist/
npm test       # Eleven interaction and data-behavior checks in jsdom
npm run format
```

## Explore

- Getting started → email/password → IMAP selection → incoming/outgoing settings → confirmation → the new account's inbox
- **Explore the demo inbox** opens populated sample accounts immediately
- Hamburger → accounts and nested folders; unread counts follow message state
- Inbox → reading → reply/reply all/forward; archive, trash, and mark read work locally
- Compose → save draft or send to the local Sent folder; file selection records attachment metadata only
- Search current account, all accounts, or text within an open message
- Settings → attachment policy, offline preview, optional unified inbox and related-message views, reset data

JMAP is labeled for v2.0 and cannot be selected. Unified inbox and related-message previews are off by default. Attachments default to on demand and can be changed to automatic downloads in Settings.

The demo uses a fixed September 24, 2026 message timeline. New-since-last-visit and read/unread are independent fixture states; reading a new message does not clear its new indicator. The future production app still needs a persisted visit policy. Outgoing demo messages use the current time.

## Files

- [App and routes](src/App.tsx)
- [Screens](src/components)
- [Design styles](src/styles.css)
- [Shared shadcn-style primitives](src/components/ui)
- [Mail models and mock-data adapter](src/lib/mail.ts)
- [Original starter fixtures](src/data/email-app-mock-data.json)
- [Interaction tests](src/App.test.tsx)
- [Design references](../designs)

React, TypeScript, Vite, Tailwind, Radix-based shadcn-style UI components, and Lucide outline icons. Mock changes persist in browser localStorage under `kage-mail-v1`; passwords are never persisted. Reset in Settings restores the sample accounts. HTML email is rendered in a sandboxed iframe with external content disabled.

## Design references and assets

The landing, setup, confirmation, inbox, message, drawer, and optional related-message screens follow the PNGs in the shared ../designs folder. Compose, settings, and search extend the same styling because no separate reference screens were supplied for them.

The Thunderbird logo and welcome wallpaper were copied from the existing iOS project's Welcome asset catalog at `../thunderbird-ios/Thunderbird/Thunderbird/Assets.xcassets/Welcome`. The PDF is a generated fictional sample. Attachment preview downloads use these bundled demonstration files, not uploaded file contents.

Folder and toolbar glyphs currently use Lucide. They are centralized in the shared
components so contributors can replace them with design exports. Design-tool
account details and browser-session notes belong in private workspace documentation.

### Populated demo mailboxes

The demo starts with Personal, Work, and Community accounts. Each includes Inbox,
Drafts, Sent, Archive, Spam, and Trash, plus Travel, Upcoming trips, and Receipts.
Work also retains its Projects / Design folders. Accounts added through setup are
populated immediately. Existing saved demos receive the new samples once on reload;
subsequent visits preserve message changes and deletions.

`src/data/design-message-samples.json` contains examples based on the message-list
and message-view variations in `../designs`: long names and subjects, Japanese,
Arabic, Hebrew, emoji, missing subjects and bodies, mailing lists, and deliberately
broken legacy encoding. These are display fixtures, not a MIME decoder.

## Production deployment

`vercel.json` builds with Vite and serves `dist/`. Link the checkout to your own
Vercel project and account before deploying:

```sh
vercel link
npm test
npm run build
vercel deploy --prod
```

Local Vercel metadata and environment files are ignored by Git. Design reference
images are excluded from deployment; app assets in `public/` are included.

## Android fixture export

`npm run export:android-fixtures` writes the evaluated web initial state and
new-account template to the Android asset. Run it after changing mock data or its
adapter so both prototypes retain the same account/folder/message examples.
