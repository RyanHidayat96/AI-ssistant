#!/usr/bin/env bash
# Install both apps on the phone and leave the module zip where the root manager can see it.
# Use when ADB was offline during a build.
set -u

SERIAL="${SERIAL:-6a0706f0}"
CAU="/c/Users/exery/Documents/tools/Causentry"
AI="/c/Users/exery/Documents/tools/AI-ssistants"

adb -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "device $SERIAL not connected"; exit 1; }

echo "== installing AI-ssistants =="
adb -s "$SERIAL" install -r "$AI/release/AI-ssistants-v1.0.0.apk" | tail -1
adb -s "$SERIAL" push "$AI/release/AI-ssistants-v1.0.0.apk" /sdcard/Download/ >/dev/null 2>&1 \
  && echo "   apk copied to /sdcard/Download"

echo "== installing Causentry control UI =="
adb -s "$SERIAL" install -r "$CAU/root-module/payload/Causentry.apk" | tail -1

echo "== pushing the Causentry module zip =="
adb -s "$SERIAL" push "$CAU/release/Causentry-KSUN-v1.1.0.zip" /sdcard/Download/ >/dev/null 2>&1 \
  && echo "   zip copied to /sdcard/Download"

echo "== runtime state =="
adb -s "$SERIAL" shell 'su -c "cat /data/adb/causentry/runtime.state 2>/dev/null; echo; printf \"heartbeat age: \"; echo \$(( \$(date +%s) - \$(cat /data/adb/causentry/heartbeat 2>/dev/null || echo 0) ))s"' | tr -d '\r'

echo "done"
