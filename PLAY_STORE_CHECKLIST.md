# Depup Office 4.0.1 — Play production checklist

## Release artifact

- Package: `cloud.depup.office`
- Version: `4.0.1` (`versionCode` `40001`)
- Target SDK: 36; min SDK: 29.
- Local AAB: `app/build/outputs/bundle/release/DepupOffice_v4.0.1-release.aab`
- Play API confirmed alpha already uses `40000`; production track is empty.
- AAB upload was accepted by Play during an edit, but the production track update returned `400 Precondition check failed`. The edit was discarded; there is no committed production draft and no rollout.
- The service account's Play Console screen shows app access **Admin (all permissions)**, so a missing app permission is not the current finding.
- Play Console reports an account owner transfer in progress to this service account. A retry still got `400 Precondition check failed`; no production draft was committed. Resolve/cancel the pending owner transfer with the real human account owner, then retry the production draft upload.

## Privacy and policy declarations

- In-app privacy policy: `app/src/main/assets/privacy.html`.
- Public policy URL verified accessible on 2026-11-17: <https://raw.githubusercontent.com/MeledakCik/DepupOffice/main/app/src/main/assets/privacy.html>.
- Google Play Console still needs a human check of Data safety, content rating, target audience, and Ads declaration. The Publisher API used for this release attempt could not read those declarations.
- Source has no ads SDK, removes the `INTERNET` permission from the merged manifest, requests camera for scanning, and uses ML Kit for OCR/barcode scanning. Confirm the Console declarations match the behavior and current policy text before rollout.

## Store listing

- Publisher API confirmed: 1 app icon, 1 feature graphic, and 4 phone screenshots are present in Indonesian listing assets. The four new workspace, Word, spreadsheet, and PDF mockups were uploaded and committed on 2026-11-17; their remote SHA-1 hashes match the PNGs in `play-store/listing/screenshots/`.
- New phone screenshots are 1080 × 1920 RGB PNGs without alpha. Editable source captures, generator, and preview are in `play-store/listing/`.
- Tablet screenshots are absent; add them only if tablet listing coverage is desired.

## Verification completed

- `./gradlew testDebugUnitTest --rerun-tasks`: 5 tests passed.
- `./gradlew lint test bundleRelease assembleRelease`: successful.
- Release APK signature verified; 16 KB alignment check passed for 64-bit native libraries.
- Release installed on Android emulator and opened a one-page PDF; zoom buttons reached 200% without a crash. Scanner/crop screen launched on the emulator virtual camera, and exporting a scan to PDF succeeded. Android share sheet opened with the exported PDF attached.
- Emulator OCR action completed; the virtual camera scene had no readable document text, so OCR accuracy was not validated against a text page.
- Pinch zoom refresh behavior was previously checked on the user's phone and reported fixed. This pass only had Android emulators connected; physical-device regression is still needed for camera/OCR with real photos, DOCX/XLSX open and edit, and end-to-end import/export/share.

## Remaining quality work

- Release lint currently reports 301 warnings and zero Error/Fatal findings. Main groups are `UseKtx` (121), `SetTextI18n` (100), `ObsoleteSdkInt` (18), and `UnusedResources` (14). Do not suppress the report wholesale; address accessibility and localization warnings in focused changes.
- No automated coverage yet for complete DOCX/XLSX editing, camera/OCR accuracy, and export flows.
