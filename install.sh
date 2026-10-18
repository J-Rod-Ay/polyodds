#!/bin/bash
# Usage: ./install.sh <tv-ip>   (ADB debugging must be ON; accept the prompt on the TV the first time)
if command -v adb >/dev/null; then ADB=adb
else ADB="$(wslpath "$(cmd.exe /c 'echo %LOCALAPPDATA%' 2>/dev/null | tr -d '\r')")/Android/Sdk/platform-tools/adb.exe"; fi
cd "$(dirname "$0")"
APK=PolyOdds.apk
[ -f "$APK" ] || APK=build/PolyOdds.apk
set -e
$ADB connect "$1:5555"
A="$ADB -s $1:5555"
$A install -r "$APK"
# 1) draw over other apps (hidden setting on Fire TV / Google TV)
$A shell appops set app.polyodds SYSTEM_ALERT_WINDOW allow
# 2) accessibility service that reads the on-screen channel/programme title for auto-detect (appended, never replaces others)
SVC=app.polyodds/app.polyodds.DetectService
CUR=$($A shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$SVC"*) NEW="$CUR" ;; ""|null) NEW="$SVC" ;; *) NEW="$CUR:$SVC" ;; esac
$A shell settings put secure enabled_accessibility_services "$NEW"
$A shell settings put secure accessibility_enabled 1
IP=$($A shell ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
echo "done — open Poly Odds from your apps. Phone remote: http://${IP:-$1}:8765"
