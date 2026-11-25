#!/bin/bash
# Drive the app in the emulator and capture what each screen looks like.
# Pixel 5 at 1080x2340, density 440.
set -x
PKG=com.example.penkhata
mkdir -p screens

shot() { adb exec-out screencap -p > "screens/$1.png"; sleep 1; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n $PKG/.MainActivity
sleep 12
shot 01-launch

# the menu panel
adb shell input tap 60 90
sleep 3
shot 02-menu
adb shell input keyevent 4
sleep 2

# menu items, measured from the panel: first item sits ~205px down, ~124px apart
tap_menu_item() {
  adb shell input tap 60 90
  sleep 2
  adb shell input tap 200 "$1"
  sleep 4
  shot "$2"
}

tap_menu_item 330 03-stock
tap_menu_item 578 04-reports
tap_menu_item 826 05-settings
tap_menu_item 206 06-entry

# inside Stock, open the GRN list
adb shell input tap 60 90
sleep 2
adb shell input tap 200 330
sleep 3
shot 07-stock-tab
adb shell input tap 560 330
sleep 4
shot 08-after-tap

# anything the app recorded about a crash
adb shell run-as $PKG cat files/last_crash.txt > screens/crash.txt 2>/dev/null || echo "no crash file" > screens/crash.txt
ls -la screens
