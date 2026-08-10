# Quickstart: verifying gravity-derived orientation

**Feature**: `specs/006-gravity-orientation` | **Date**: 2026-08-09

This is the first head-tracking claim in this project that **one person can check alone, with no
head involved**. Gravity does not care whose ear the bud was in: take it out, put it on a table,
tilt it against something straight, and the reported angle either matches or it does not.

That is worth saying plainly, because every previous verification here needed a wearer holding a
pose they could only estimate, and "chin toward your shoulder is about 45°" was the accuracy
floor of the whole feature. A table edge is better than a neck.

## Prerequisites

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew installDebug

PKG=io.github.andrewkomkov.greenpods.debug
CMP=$PKG/io.github.andrewkomkov.greenpods.debug.GreenPodsDebugReceiver
gp() { adb shell am broadcast --receiver-foreground -n $CMP \
       -a io.github.andrewkomkov.greenpods.DEBUG "$@" >/dev/null; }

adb shell svc power stayon usb
```

The accessory must be **connected and out of its case**, so the motion stream runs — but it does
not have to be worn. The same bond requirement as everywhere else applies: this phone must be
paired to it.

## 1. Is the vector there at all?

```bash
gp --es cmd cal --es value start        # any command that starts the motion stream
adb shell setprop log.tag.AapMotionRaw DEBUG
adb logcat -d -s AapMotionRaw | tail -3
```

Each line is one 58-byte report. The gravity vector is the three little-endian int16 at byte
offsets 44, 46 and 48, and its magnitude should sit near **1034**. If it does not, stop: the
offsets have moved and nothing below means anything.

## 2. A tilt of a known angle (SC-001)

```bash
gp --es cmd tilt --es value reference    # bud resting flat and still
# tilt it 45° against a book, a protractor, or a phone running a spirit level
gp --es cmd tilt --es value read
```

Expect the reported tilt within **5°** of what you set. Two things make this a real check rather
than a self-consistent one: the reference is captured deliberately rather than taken from the
first sample (FR-003), and the angle is a ratio, so no assumption about what a unit is worth
enters the arithmetic.

## 3. The rotation it must ignore (SC-002)

```bash
# put it flat again, then rotate it on the spot without tilting
gp --es cmd tilt --es value read
```

Expect **under 3°** of change. This is the assertion the whole feature rests on: rotation about
the gravity axis cannot change the direction of gravity, which is exactly why yaw is not
available here and must be said to be missing rather than reported as zero.

## 4. Nothing is laundered (SC-004)

```bash
gp --es cmd dump | python3 -m json.tool | grep -A12 orientation
```

Pitch and roll must be marked as derived from gravity, yaw as uncalibrated. A measured angle
sitting beside a guessed one without saying which is which would be worse than today's honest
approximation, because today at least the approximation is labelled.

## 5. Against the capture, with no hardware at all

```bash
./gradlew :core:bluetooth:testDebugUnitTest --tests '*DevmotionGravityTest*'
```

Pins the decode against `core/bluetooth/src/test/resources/aap/devmotion-report-bodies.txt` —
1268 reports recorded on 2026-08-09, labelled `REST`, `YAW`, `PITCH` and `ROLL`. The assertion
that matters is that the `YAW` segment moves the vector *less* than either tilt segment does. If
that ever fails, the offsets have moved and the feature is reading something else.

## What this cannot verify

Which part of a tilt is pitch and which is roll. The bud sits at an angle in the ear and the
resting vector encodes that mounting rotation, so total tilt is the claim being made here and
the split is deliberately out of scope. Anyone quoting a number from this should quote it as
tilt from upright, not as pitch.
