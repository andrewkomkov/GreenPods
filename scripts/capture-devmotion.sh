#!/bin/bash
# Captures what the accessory declares about its head-tracking sensor, and one burst of
# the reports themselves.
#
# The offsets and the scale head tracking is decoded with are supposed to come from the
# descriptor rather than from constants. This is how the descriptor gets on disk so a
# decoder can be pinned against it, in the way `hr-report-descriptor.txt` already pins
# the heart-rate one.
#
# Needs: the phone on USB, the accessory connected, the debug build installed.
# Does NOT need the accessory to be worn.
#
#   ./scripts/capture-devmotion.sh [output-directory]
set -euo pipefail

OUT=${1:-captures/$(date +%Y%m%d-%H%M%S)-devmotion}
PKG=io.github.andrewkomkov.greenpods.debug
CMP=$PKG/io.github.andrewkomkov.greenpods.debug.GreenPodsDebugReceiver

gp() {
    adb shell am broadcast --receiver-foreground -n "$CMP" \
        -a io.github.andrewkomkov.greenpods.DEBUG "$@" >/dev/null
}

adb get-state >/dev/null 2>&1 || { echo "no device on adb"; exit 1; }
mkdir -p "$OUT"

echo "==> opening the channel"
adb shell am start -n $PKG/io.github.andrewkomkov.greenpods.MainActivity >/dev/null
adb shell setprop log.tag.AapTransport DEBUG
sleep 2
adb logcat -c
gp --es cmd probe
sleep 6
adb logcat -d -s GreenPodsDebug | tee "$OUT/probe.txt"

echo "==> asking the accessory to describe its sensors"
adb logcat -c
gp --es cmd hid
sleep 8
adb logcat -d -s GreenPodsDebug > "$OUT/hid-services.txt"
grep -c "^" "$OUT/hid-services.txt" || true

# The head-tracking service id, read from what the accessory just said rather than
# assumed — the whole point of FR-002.
SERVICE=$(grep -o "service 0x[0-9A-F]* name=[^ ]* tags=[^ ]*headTracking" "$OUT/hid-services.txt" \
          | grep -o "0x[0-9A-F]*" | head -1 || true)
if [ -z "$SERVICE" ]; then
    echo "!! no head-tracking service announced; nothing to capture"
    echo "   see $OUT/hid-services.txt"
    exit 2
fi
echo "==> head tracking is service $SERVICE"

# Report interval 40000 us = 25 Hz, as a 32-bit LE feature report.
ID=$(printf '%02X' $((SERVICE)))
START="040004001700000010000F000878420B08${ID}10021A0501409C0000"
STOP="040004001700000010000F000878420B08${ID}10021A050100000000"

echo "==> streaming for 20 s — hold the buds still, then turn them slowly"
adb logcat -c
gp --es cmd raw --es hex "$START"
sleep 20
adb logcat -d -s AapTransport > "$OUT/head-tracking-frames.txt"
gp --es cmd raw --es hex "$STOP"
sleep 2

echo "==> stopped"
grep -c "withheld" "$OUT/head-tracking-frames.txt" || true
echo "captured into $OUT"
