#!/bin/bash
# Reach the SUPPLIES dialog and prove the category list scrolls.
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

# Stock tab
adb shell input tap 75 213; sleep 2; adb shell input tap 200 606; sleep 3
# MASTER (third button on the stock title row)
adb shell input tap 915 393; sleep 3
shot 01-master
# Distributors tab
adb shell input tap 846 459; sleep 2
shot 02-distributors
# add a distributor
adb shell input tap 273 1095; sleep 1
adb shell input text "SAI"
sleep 1
adb shell input keyevent 4
sleep 1
adb shell input tap 540 1236; sleep 3
shot 03-distributor-added
# SUPPLIES on the row
adb shell input tap 831 882; sleep 3
shot 04-supplies-top
# scroll inside the dialog
adb shell input swipe 540 1500 540 700 400; sleep 2
shot 05-supplies-scrolled
adb shell input swipe 540 1500 540 700 400; sleep 2
shot 06-supplies-bottom

adb shell run-as $PKG cat files/last_crash.txt > screens/crash.txt 2>/dev/null || echo "no crash file" > screens/crash.txt
ls -la screens
