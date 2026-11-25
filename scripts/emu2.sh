#!/bin/bash
# Drive the app in the emulator and capture what each screen looks like.
# Device is 1080x2340; the hamburger sits at (75, 213).
set -x
PKG=com.example.penkhata
mkdir -p screens
shot() { adb exec-out screencap -p > "screens/$1.png"; sleep 1; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n $PKG/.MainActivity
sleep 12
shot 01-launch

adb shell input tap 75 213
sleep 3
shot 02-menu

row() {
  adb shell input tap 75 213
  sleep 2
  adb shell input tap 300 "$1"
  sleep 4
  shot "$2"
}
row 300 03-row1
row 380 04-row2
row 460 05-row3
row 540 06-row4
row 620 07-row5
row 700 08-row6

adb shell run-as $PKG cat files/last_crash.txt > screens/crash.txt 2>/dev/null || echo "no crash file" > screens/crash.txt
ls -la screens
