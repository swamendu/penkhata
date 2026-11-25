#!/bin/bash
# Drive the app in the emulator, with the emulator's own launcher kept out of the way.
# Device is 1080x2340; the hamburger sits at (75, 213).
set -x
PKG=com.example.penkhata
mkdir -p screens
shot() { adb exec-out screencap -p > "screens/$1.png"; sleep 1; }
calm() {
  adb shell input keyevent 4 >/dev/null 2>&1
  adb shell am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1
  sleep 1
}

adb shell settings put global anr_show_background 0 >/dev/null 2>&1
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am start -n $PKG/.MainActivity
sleep 12
calm
shot 01-launch

adb shell input tap 75 213
sleep 3
shot 02-menu

# menu rows: first item near y=300, about 120px apart
row() {
  calm
  adb shell input tap 75 213
  sleep 2
  adb shell input tap 300 "$1"
  sleep 5
  shot "$2"
}
row 300 03-stock
row 420 04-reports
row 540 05-files
row 660 06-settings
row 780 07-ledger
row 180 08-entry

adb shell run-as $PKG cat files/last_crash.txt > screens/crash.txt 2>/dev/null || echo "no crash file" > screens/crash.txt
ls -la screens
