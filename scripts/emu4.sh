#!/bin/bash
# Menu rows measured from a real screenshot: Entry 396, Ledger 501, Stock 606,
# Reports 712, Files 817, Settings 922 (device pixels, 1080x2340).
set -x
PKG=com.example.penkhata
mkdir -p screens
shot() { adb exec-out screencap -p > "screens/$1.png"; sleep 1; }
calm() {
  adb shell input keyevent 4 >/dev/null 2>&1
  adb shell am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1
  sleep 1
}
open_screen() {   # $1 = row y
  calm
  adb shell input tap 75 213
  sleep 2
  adb shell input tap 200 "$1"
  sleep 4
}

adb shell settings put global anr_show_background 0 >/dev/null 2>&1
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am start -n $PKG/.MainActivity
sleep 12
calm

open_screen 606
shot 01-stock

# the RECEIVED button sits on the title row of the stock screen
adb shell input tap 1035 360
sleep 4
shot 02-grn-list

# NEW RECEIPT is the full-width button under the title
adb shell input tap 540 520
sleep 4
shot 03-new-receipt

open_screen 922
shot 04-settings

open_screen 712
shot 05-reports

adb shell run-as $PKG cat files/last_crash.txt > screens/crash.txt 2>/dev/null || echo "no crash file" > screens/crash.txt
ls -la screens
