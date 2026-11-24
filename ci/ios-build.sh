#!/bin/bash
# Build & uji iOS (dipakai job arm64 dan cadangan Intel di .github/workflows/android-build.yml).
set -euo pipefail
mkdir -p build
sudo xcode-select -s "$(ls -d /Applications/Xcode*.app | sort -V | tail -1)"
xcodebuild -version

# 1) Uji unit di Simulator (logika kripto, format Office, rumus, EXE→PDF, geometri TTD, catatan)
UDID=$(xcrun simctl list devices available -j | python3 -c '
import json,sys
d=json.load(sys.stdin)["devices"]
best=None
for rt,devs in d.items():
    if "iOS" not in rt: continue
    for v in devs:
        if v.get("isAvailable") and v["name"].startswith("iPhone"):
            key=(rt,v["name"])
            if best is None or key>best[0]: best=(key,v["udid"])
print(best[1] if best else "")')
echo "Simulator: $UDID"
if [ -z "$UDID" ]; then echo "::error::Tidak ada simulator iPhone"; exit 1; fi
set -o pipefail
xcodebuild -project iosApp/DepupOffice.xcodeproj -scheme DepupOffice -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build/ios-test \
  CODE_SIGNING_ALLOWED=NO test 2>&1 | tee build/ios-test.log | grep -E "Test Case|error:|failed|passed|\*\* TEST" | tail -80

# 2) Build rilis Simulator & perangkat (tanpa tanda tangan) + IPA
xcodebuild -project iosApp/DepupOffice.xcodeproj -scheme DepupOffice -configuration Release \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios-sim CODE_SIGNING_ALLOWED=NO build | tail -20
xcodebuild -project iosApp/DepupOffice.xcodeproj -scheme DepupOffice -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/ios-dev CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build | tail -20
APP=build/ios-dev/Build/Products/Release-iphoneos/DepupOffice.app
test -d "$APP/PlugIns/NotesWidgetExtension.appex" || { echo "::error::Widget tidak tertanam di aplikasi"; exit 1; }
v=$(sed -nE 's/^[[:space:]]*MARKETING_VERSION = ([^;]+);.*/\1/p' iosApp/DepupOffice.xcodeproj/project.pbxproj | head -1)
mkdir -p dist/Payload
cp -R "$APP" dist/Payload/
( cd dist && zip -qry "DepupOffice_iOS_v${v}_unsigned.ipa" Payload && rm -rf Payload && shasum -a 256 *.ipa > SHA256SUMS-ios.txt )
ls -la dist
