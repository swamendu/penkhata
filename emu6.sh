#!/bin/bash
# Drive the app by widget text, not by guessed coordinates.
set -x
PKG=com.example.penkhata
mkdir -p screens
shot() { adb exec-out screencap -p > "screens/$1.png"; sleep 1; }
calm() {
  adb shell input keyevent 4 >/dev/null 2>&1
  adb shell am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1
  sleep 1
}
tap_text() {
  local out
  out=$(python3 emu_tap.py "$1")
  set -- $out
  if [ "$1" = "NOTFOUND" ]; then
    echo "MISSING: $2"
    return 1
  fi
  adb shell input tap "$1" "$2"
  sleep 3
}

adb shell settings put global anr_show_background 0 >/dev/null 2>&1
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am start -n $PKG/.MainActivity
sleep 12
calm

# Stock, through the menu
tap_text "MENU" >/dev/null 2>&1 || adb shell input tap 75 213
sleep 2
tap_text "Stock"
shot 01-stock

tap_text "RECEIVED"
shot 02-grn-list

tap_text "NEW RECEIPT"
shot 03-new-receipt

tap_text "CHOOSE DISTRIBUTOR"
shot 04-distributor-picker
adb shell input keyevent 4
sleep 2

adb shell input swipe 540 1500 540 600 300
sleep 2
shot 05-receipt-lines

calm
tap_text "MENU" >/dev/null 2>&1 || adb shell input tap 75 213
sleep 2
tap_text "Settings"
shot 06-settings

adb shell run-as $PKG cat files/last_crash.txt > screens/crash.txt 2>/dev/null || echo "no crash file" > screens/crash.txt
ls -la screens
