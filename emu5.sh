#!/bin/bash
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
adb shell input tap 75 213; sleep 2; adb shell input tap 200 606; sleep 4
shot 01-stock
adb shell input tap 592 393
sleep 4
shot 02-grn-list
adb shell input tap 540 500
sleep 4
shot 03-new-receipt
adb shell input swipe 540 1600 540 700 300
sleep 2
shot 04-receipt-scrolled
calm
adb shell input tap 75 213; sleep 2; adb shell input tap 200 922; sleep 4
shot 05-settings
adb shell run-as $PKG cat files/last_crash.txt > screens/crash.txt 2>/dev/null || echo "no crash file" > screens/crash.txt
ls -la screens
