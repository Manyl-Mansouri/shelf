#!/usr/bin/env bash
# Builds code-free stand-in TV apps (one per line of apps.tsv) and installs them on the emulator,
# so Shelf can be tested against a realistic app grid. Never install these on a real TV.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
PY="${PYTHON:-python3}"   # needs Pillow
OUT="${OUT:-$(mktemp -d)}"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
[[ "$SERIAL" == emulator-* ]] || { echo "Refusing: demo apps are for the emulator only."; exit 1; }

mkdir -p "$OUT/banners"
"$PY" banners.py apps.tsv "$OUT/banners"

grep -v '^#' apps.tsv | while IFS=$'\t' read -r pkg label _ _ _; do
  [[ -z "$pkg" ]] && continue
  w="$OUT/$pkg"; mkdir -p "$w/res/drawable-xxxhdpi"
  cp "$OUT/banners/$pkg.png" "$w/res/drawable-xxxhdpi/banner.png"
  cat > "$w/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
  <uses-feature android:name="android.software.leanback" android:required="false" />
  <application android:label="$label" android:banner="@drawable/banner" android:hasCode="false">
    <activity android:name="android.app.Activity" android:exported="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
XML
  "$BT/aapt2" compile --dir "$w/res" -o "$w/res.zip"
  "$BT/aapt2" link -I "$JAR" --manifest "$w/AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 34 \
    -o "$w/unsigned.apk" "$w/res.zip"
  "$BT/zipalign" -f 4 "$w/unsigned.apk" "$w/aligned.apk"
  "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$w/app.apk" "$w/aligned.apk"
  if adb -s "$SERIAL" install -r "$w/app.apk" >/dev/null 2>&1; then echo "installed $label"; else echo "skipped $label"; fi
done
