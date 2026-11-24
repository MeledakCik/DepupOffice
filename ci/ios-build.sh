#!/bin/bash
# Build & uji iOS (dipakai job arm64 dan cadangan Intel di .github/workflows/android-build.yml).
set -euo pipefail
mkdir -p build
sudo xcode-select -s "$(ls -d /Applications/Xcode*.app | sort -V | tail -1)"
xcodebuild -version

# Uji ditandatangani ad-hoc ("Sign to Run Locally") agar Keychain & App Group berfungsi seperti di iPhone.
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
# Baris error dijadikan anotasi GitHub (terbaca tanpa login) agar kegagalan mudah ditelusuri.
annotate() {
  grep -E "error:|XCTAssert|failed \(" "$1" | grep -v "^Testing failed:" | sort -u | head -40 | while IFS= read -r line; do
    echo "::error::${line//$'\n'/ }"
  done
}
if ! xcodebuild -project iosApp/DepupOffice.xcodeproj -scheme DepupOffice -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build/ios-test \
  -only-testing:DepupOfficeTests CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= test > build/ios-test.log 2>&1; then
  annotate build/ios-test.log
  tail -60 build/ios-test.log
  exit 65
fi
grep -E "Test Case .* (passed|failed)|Executed [0-9]+ test" build/ios-test.log | tail -60
echo "::notice title=Uji iOS::$(grep -E 'Executed [0-9]+ test' build/ios-test.log | tail -1 | sed 's/^[[:space:]]*//')"
echo "::notice title=Uji iOS (daftar)::$(grep -oE "Test Case '-\[DepupOfficeTests\.[A-Za-z]+ [A-Za-z0-9]+\]' passed" build/ios-test.log | sed -E "s/Test Case '-\[DepupOfficeTests\.([A-Za-z]+) ([A-Za-z0-9]+)\]' passed/\2/" | tr '\n' ' ')"

# 1b) Uji UI (tur semua layar + alur). Kegagalan dicatat sebagai peringatan; screenshot selalu diekspor.
UI_OK=1
if ! xcodebuild -project iosApp/DepupOffice.xcodeproj -scheme DepupOffice -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build/ios-test -resultBundlePath build/ui.xcresult \
  -only-testing:DepupOfficeUITests CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= test > build/ios-ui.log 2>&1; then
  UI_OK=0
  grep -E "error:|XCTAssert|failed \(" build/ios-ui.log | sort -u | head -40 | while IFS= read -r line; do echo "::warning title=Uji UI::$line"; done
fi
echo "::notice title=Uji UI::$(grep -E 'Executed [0-9]+ test' build/ios-ui.log | tail -1 | sed 's/^[[:space:]]*//')"
mkdir -p build/shots-raw build/shots
if xcrun xcresulttool export attachments --path build/ui.xcresult --output-path build/shots-raw > /dev/null 2>&1; then
  python3 - <<'PY'
import json, os, shutil, re
raw = "build/shots-raw"
manifest = json.load(open(os.path.join(raw, "manifest.json")))
n = 0
for test in manifest:
    for att in test.get("attachments", []):
        name = att.get("suggestedHumanReadableName") or att["exportedFileName"]
        name = re.sub(r"_\d+_[0-9A-F-]{36}", "", name)
        shutil.copy(os.path.join(raw, att["exportedFileName"]), os.path.join("build/shots", name))
        n += 1
print(f"{n} screenshot diekspor")
PY
fi
ls build/shots | head -80
if [ -n "${GITHUB_TOKEN:-}" ] && [ -n "$(ls -A build/shots 2>/dev/null)" ]; then
  ( cd build/shots
    {
      echo "# Screenshot uji UI iOS"
      echo "Commit: ${GITHUB_SHA:-?} · $(date -u '+%Y-%m-%d %H:%M UTC') · UI_OK=$UI_OK"
      echo
      for f in *.png *.jpeg *.jpg; do [ -e "$f" ] && echo "- [$f]($f)"; done
    } > README.md
    git init -q && git checkout -q -b ci-screenshots
    git -c user.name="github-actions" -c user.email="actions@github.com" add -A
    git -c user.name="github-actions" -c user.email="actions@github.com" commit -qm "Screenshot uji UI ${GITHUB_SHA:-}"
    git push -qf "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" ci-screenshots && echo "::notice title=Screenshot::branch ci-screenshots diperbarui" )
fi

# 2) Build rilis Simulator & perangkat (tanpa tanda tangan) + IPA
if ! xcodebuild -project iosApp/DepupOffice.xcodeproj -scheme DepupOffice -configuration Release \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios-sim CODE_SIGNING_ALLOWED=NO build > build/ios-sim.log 2>&1; then
  annotate build/ios-sim.log; tail -40 build/ios-sim.log; exit 65
fi
if ! xcodebuild -project iosApp/DepupOffice.xcodeproj -scheme DepupOffice -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/ios-dev CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build > build/ios-dev.log 2>&1; then
  annotate build/ios-dev.log; tail -40 build/ios-dev.log; exit 65
fi
APP=build/ios-dev/Build/Products/Release-iphoneos/DepupOffice.app
test -d "$APP/PlugIns/NotesWidgetExtension.appex" || { echo "::error::Widget tidak tertanam di aplikasi"; exit 1; }
v=$(sed -nE 's/^[[:space:]]*MARKETING_VERSION = ([^;]+);.*/\1/p' iosApp/DepupOffice.xcodeproj/project.pbxproj | head -1)
mkdir -p dist/Payload
cp -R "$APP" dist/Payload/
( cd dist && zip -qry "DepupOffice_iOS_v${v}_unsigned.ipa" Payload && rm -rf Payload && shasum -a 256 *.ipa > SHA256SUMS-ios.txt )
ls -la dist
echo "::notice title=Widget::NotesWidgetExtension.appex tertanam di DepupOffice.app"
