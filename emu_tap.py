#!/usr/bin/env python3
"""Find a widget by its text in the emulator and print its centre as "x y".

Used instead of guessed coordinates: the keyboard and scroll position move things
around, but the label on a button does not.
"""
import re
import subprocess
import sys

label = sys.argv[1]
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"],
                     capture_output=True, text=True).stdout

best = None
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
    if text.strip().lower() == label.strip().lower():
        best = ((x1 + x2) // 2, (y1 + y2) // 2)
        break
if best is None:
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        if label.strip().lower() in text.strip().lower():
            best = ((x1 + x2) // 2, (y1 + y2) // 2)
            break

if best is None:
    print("NOTFOUND 0 0")
    sys.exit(1)
print(best[0], best[1])
