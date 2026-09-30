# Android test isolation

- The user's app is `org.foxred.kage`. Never clear its data or uninstall it for tests.
- Instrumentation targets the `instrumented` build type: **Kage Tests**, application ID `org.foxred.kage.instrumented`, runner package `org.foxred.kage.instrumented.test`.
- Build with `:app:assembleInstrumented :app:assembleInstrumentedAndroidTest`; run with `:app:connectedInstrumentedAndroidTest`.
- Use a dedicated test emulator and explicitly select its serial (`ANDROID_SERIAL` for Gradle or `adb -s` for device commands). Do not run UI tests on the user's Google sign-in emulator.
- For direct instrumentation, install only the instrumented APKs and invoke `org.foxred.kage.instrumented.test/androidx.test.runner.AndroidJUnitRunner`.
- Keep automated Google authorization mocked. Live OAuth verification uses the normal app and requires the user's sign-in.
