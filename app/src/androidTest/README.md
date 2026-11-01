# Live observation probe

This debug-only instrumentation starts the production activity and observes the same Live pipeline. It does not inject synthetic MotionEvent objects into production. External emulator services supply inputs.

Build with `gradlew :app:assembleDebug :app:assembleDebugAndroidTest`. Install both APKs in the foreground Pleos user and confirm required permissions. The runner is `app.motioncue.test/app.motioncue.probe.LiveSessionProbe`.

Options include `durationMs`, unique `runName`, `sdkSnapshot`, `snapshotAtMs`, `lightingEnabled`, `recoverySeat`, and `recoveryAtMs`. The default observation is not permission-dialog testing. Test-only sources are not included in the release APK.

The final 2026-10-29 observations and their limits are summarized in the root README. Source traces are retained separately in the development workspace; they are not bundled in this submission. SDK callbacks are not proof of physical vehicle movement, audible output or motion-sickness benefit.
