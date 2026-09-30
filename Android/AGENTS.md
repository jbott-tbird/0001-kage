# Android test isolation

- Use the normal Kage debug app (`org.foxred.kage`) for instrumentation; do not create a separate app identity.
- Run device tests only on the dedicated `Kage_Isolated_Tests_API30` AVD. Never use the Google sign-in emulator or a personal device.
- Start the test AVD, then run `python3 scripts/android_device_tests.py` from the repository root. Extra arguments are passed to Gradle.
- The launcher selects the AVD by name, not by a hard-coded serial. Direct `:app:connectedDebugAndroidTest` requires `ANDROID_SERIAL` and verifies that AVD name before installation.
- Before any direct adb install, instrumentation, data clearing, or uninstall for tests, verify the selected serial with `adb -s SERIAL emu avd name`. Test cleanup is permitted only on the dedicated test AVD.
- Keep automated Google authorization mocked. Live OAuth verification uses the manual emulator and the user's sign-in.
