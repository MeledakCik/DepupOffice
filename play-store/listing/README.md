# Play Store listing mockups

Four Indonesian portrait screenshots for the store listing. Each final image is **1080 × 1920 px (9:16), RGB PNG without alpha**, within Google Play's current phone screenshot dimension and encoding limits ([official requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)). Each uses a real DepUp Office screen captured from the release QA build.

1. `screenshots/01-workspace.png` — home workspace and scan entry point
2. `screenshots/02-word.png` — Word editor and a formatted sample document
3. `screenshots/03-spreadsheet.png` — spreadsheet with automatic totals
4. `screenshots/04-pdf-tools.png` — PDF management tools

`preview.png` is a contact sheet for review; upload the four individual PNGs from `screenshots/` to Play Console. Original app captures are in `source/`. Rebuild the composites from the repository root with:

```sh
swift play-store/listing/generate_mockups.swift .
```
