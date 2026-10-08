# Google Play launch checklist (must complete before listing)

Owner decisions recorded 2026-09-27. Nothing leaves the phone in the sideload builds; these items
are deferred until the Play launch and are blocking for it.

## Data & consent
- [ ] **Remove tester diagnostics sharing**: set `TEST_SHARING` to `false` in `android/app/build.gradle.kts`
      (or drop the `buildConfigField` and the `shareDiagnostics` bridge) and confirm My Log no longer shows the
      "share diagnostics" box. The promise is that nothing leaves the phone.
- [ ] **Cloud save for My Log** — wire Google Play Games Services Saved Games (Snapshots API); keep the
      existing JSON + merge rules (`findLogEntry`, newest-per-field, IVs never lost) as the conflict resolver.
      Until then `android:allowBackup="false"` stays (set in the manifest on purpose).
- [ ] Privacy policy URL matching the in-app explainer (`capture_explainer_message`): screen read
      on-device for SCAN / AUTO, log stored on device (and in cloud save once wired), version check
      contacts the update host, no screen content uploaded. Bump `CAPTURE_EXPLAINER_VERSION` when the text changes.
- [ ] Play Console declaration for the **MediaProjection foreground service** and the reason for
      `REQUEST_INSTALL_PACKAGES` (or remove the self-updater on the Play flavour — Play handles updates).
- [ ] `POST_NOTIFICATIONS` request before `startForegroundService` on Android 13+ and a monochrome small icon.

## Signing & delivery
- [ ] Enrol in **Play App Signing**; upload key = the permanent key created by `tools/setup-signing.sh`
      (`~/.pogo-signing`, backed up off-machine). The pre-2026-09-22 key was published by the old
      workflow and is burned; v2.0.0 installs must uninstall once.
- [ ] Remove or gate `UpdateChecker` (self-update) in the Play build; keep it for the sideload site.
- [ ] Bundle the ML Kit Latin model (`com.google.mlkit:text-recognition`) or add the
      `com.google.mlkit.vision.DEPENDENCIES` meta-data + module-install check, so a fresh install
      can scan without a silent "NOTHING TO READ".

## Accessibility & UX
- [ ] Pill controls: 48 dp minimum touch targets (TouchDelegate) and `stateDescription` for AUTO.
- [ ] Web page: buttons instead of clickable divs for cards/chips/tabs, dialog roles, zoom enabled.

## Localisation
- [ ] The scanner anchors on English game text (LUCKY, CANDY, EVOLVE, POWER UP, PURIFY, Attack/Defense,
      "This X was caught"). Either ship per-language anchor tables or show a one-time
      "set Pokémon GO to English for scanning" notice.
