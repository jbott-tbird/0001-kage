# Kage mail prototypes

- `Android/` — native Android app: Jetpack Compose, Material 3, Room, repository architecture
- `web/` — interactive React design prototype, deployed to your deployment URL
- `designs/` — shared screen references

## Android

Open `./Android` in Android Studio.
Build and run the `app` configuration on an emulator or device (Android 11+).
See the [Android guide](Android/README.md) and [iOS ↔ Android code map](Android/docs/architecture.html).

## Website

```sh
cd web
npm install
npm run dev
```

The website's build, tests, and Vercel deployment commands must now be run from
`web/`. Its Vercel project remains `tfa` in `YOUR_VERCEL_SCOPE`.
See [web guide](web/README.md).

The web app uses mock mail. Android includes IMAP/SMTP account, sync, and sending
code. The debug build, app/core unit tests, and Android lint pass. Live Gmail
and physical-device acceptance remain manual checks; see the Android guide for
the validation scope.
