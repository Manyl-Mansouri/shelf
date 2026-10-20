#!/usr/bin/env bash
# Rebuilds a stand-in app (from apps.tsv) with code that publishes preview rows and a "continue
# watching" entry from content/<package>.tsv, installs it on the emulator and runs it once.
# Usage: PYTHON=… ./publisher.sh com.netflix.ninja
set -euo pipefail
cd "$(dirname "$0")"
PKG="$1"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
PY="${PYTHON:-python3}"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
[[ "$SERIAL" == emulator-* ]] || { echo "Refusing: demo apps are for the emulator only."; exit 1; }
LINE=$(awk -F"\t" -v p="$PKG" '$1==p' apps.tsv)
LABEL=$(echo "$LINE" | cut -f2)
W=$(mktemp -d)
mkdir -p "$W/res/drawable-xxxhdpi" "$W/assets" "$W/classes"
echo "$LINE" > "$W/one.tsv"
"$PY" banners.py "$W/one.tsv" "$W"
cp "$W/$PKG.png" "$W/res/drawable-xxxhdpi/banner.png"
cp "content/$PKG.tsv" "$W/assets/content.tsv"
cat > "$W/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG">
  <uses-permission android:name="com.android.providers.tv.permission.WRITE_EPG_DATA" />
  <uses-feature android:name="android.software.leanback" android:required="false" />
  <application android:label="$LABEL" android:banner="@drawable/banner">
    <activity android:name="demo.Publish" android:exported="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
XML
javac -source 8 -target 8 -cp "$JAR" -d "$W/classes" publisher/src/demo/Publish.java 2>/dev/null
"$BT/d8" --lib "$JAR" --min-api 28 --output "$W" "$W"/classes/demo/*.class
"$BT/aapt2" compile --dir "$W/res" -o "$W/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$W/AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 34 -A "$W/assets" -o "$W/unsigned.apk" "$W/res.zip"
(cd "$W" && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$W/app.apk" "$W/aligned.apk"
adb -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
adb -s "$SERIAL" install "$W/app.apk" >/dev/null
adb -s "$SERIAL" shell am start -W -n "$PKG/demo.Publish" >/dev/null
echo "published $LABEL"
