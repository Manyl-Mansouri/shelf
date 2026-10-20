#!/usr/bin/env bash
# Shelf: build, install and switch the home screen on a Google TV over Wi-Fi adb.
set -euo pipefail
# An APK given to install is relative to where you ran this, so pin it down before moving here.
if [[ "${1:-}" == install && -n "${2:-}" ]]; then
  [[ -f "$2" ]] || { echo "No such APK: $2"; exit 1; }
  set -- "$1" "$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
fi
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

APK=app/build/outputs/apk/release/app-release.apk
PKG=com.manyl.shelf
SERVICE="$PKG/$PKG.HomeOverrideService"
# Stock home screens (Google TV, classic Android TV) and the Chromecast setup screen that grabs Home when they're off.
STOCK=(com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.tungsten.setupwraith)

installed() { adb shell pm list packages "$1" | grep -qx "package:$1"; }
services() { adb shell settings get secure enabled_accessibility_services | tr -d '\r' | grep -vx null || true; }
# Switches Shelf's accessibility helper on, or rebinds it: Android drops the binding when Shelf is reinstalled.
helper_on() {
  sleep 2  # right after an install, Android is still swapping the package in and won't bind
  local current; current=$(services | tr ':' '\n' | grep -vx "$SERVICE" | paste -sd: - || true)
  adb shell settings put secure enabled_accessibility_services "${current:-\"\"}"
  sleep 1
  adb shell settings put secure enabled_accessibility_services "${current:+$current:}$SERVICE"
  adb shell settings put secure accessibility_enabled 1
}

case "${1:-help}" in
  pair)      adb pair "$2" ;;                     # ./tv.sh pair 192.168.1.50:37123  (then type the 6-digit code)
  connect)   adb connect "$2" ;;                  # ./tv.sh connect 192.168.1.50:41234
  install)   # ./tv.sh install [shelf.apk]  (builds from source when no APK is given)
    if [[ -n "${2:-}" ]]; then APK=$2; else ./gradlew assembleRelease -q; fi
    adb install -r "$APK"
    # Lets the Top Shelf read what apps publish for the home screen (also asked on first launch).
    adb shell pm grant "$PKG" android.permission.READ_TV_LISTINGS || true
    # TCL only lets apps it allows "auto start" have services bound while they aren't running,
    # and Shelf's accessibility helper is one. Harmless (and ignored) on other TVs.
    adb shell appops set "$PKG" AUTO_START allow 2>/dev/null || true
    # Compile ahead-of-time so the first scroll is as smooth as the hundredth.
    adb shell cmd package compile -m speed -f "$PKG" >/dev/null
    adb shell am start -n "$PKG/.MainActivity" >/dev/null
    [[ ":$(services):" == *":$SERVICE:"* ]] && helper_on
    echo "Installed. Next: ./tv.sh takeover (or ./tv.sh override), and ./tv.sh helper for tvOS-style app switching"
    ;;
  takeover)  # Option A: turn the stock home screen off. Cleanest; fully reversible with `restore`.
    for p in "${STOCK[@]}"; do installed "$p" && adb shell pm disable-user --user 0 "$p"; done
    adb shell input keyevent KEYCODE_HOME
    ;;
  helper)    # Shelf's accessibility helper: apps reopen from where you left them, with no black flash.
    helper_on
    ;;
  override)  # Option B: keep the stock home screen, bounce Home to Shelf via an accessibility service.
    helper_on
    adb shell input keyevent KEYCODE_HOME
    ;;
  restore)   # Undo both options: Google TV home is back exactly as before.
    for p in "${STOCK[@]}"; do installed "$p" && adb shell pm enable "$p"; done
    current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
    cleaned=$(echo "$current" | tr ':' '\n' | grep -vx "$SERVICE" | grep -vx null | paste -sd: - || true)
    adb shell settings put secure enabled_accessibility_services "${cleaned:-\"\"}"
    adb shell input keyevent KEYCODE_HOME
    ;;
  uninstall) "$0" restore; adb uninstall "$PKG" ;;
  shot)      adb exec-out screencap -p > "${2:-screenshot.png}" && echo "Saved ${2:-screenshot.png}" ;;
  *)
    sed -n '2p' "$0"
    echo "Usage: ./tv.sh pair <ip:port> | connect <ip:port> | install [apk] | takeover | override | helper | restore | uninstall | shot [file]"
    ;;
esac
