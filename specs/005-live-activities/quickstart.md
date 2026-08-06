# Quickstart: verifying Live Activities

**Feature**: `specs/005-live-activities` | **Date**: 2026-08-06

How to prove the feature works, from a terminal. Screenshots corroborate; the dump verifies.

Most of this needs **no accessory** — injected advertisements walk the same pipeline a real
one does, which is what makes SC-007 achievable at a desk.

## Prerequisites

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew installDebug

PKG=io.github.andrewkomkov.greenpods.debug
CMP=$PKG/io.github.andrewkomkov.greenpods.debug.GreenPodsDebugReceiver
gp() { adb shell am broadcast --receiver-foreground -n $CMP \
       -a io.github.andrewkomkov.greenpods.DEBUG "$@" >/dev/null; }

adb shell pm grant $PKG android.permission.BLUETOOTH_SCAN
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell am start -n $PKG/io.github.andrewkomkov.greenpods.MainActivity

# The receiver needs the app foregrounded; a sleeping screen breaks long runs.
adb shell svc power stayon usb
```

Read any result with:

```bash
adb logcat -c && gp --es cmd live && sleep 3 && adb logcat -d -s GreenPodsDebug
```

## 1. Does this phone support it at all? (FR-014, User Story 4)

```bash
gp --es cmd live
```

On API 36+: `availability=AVAILABLE`. Below it: `availability=PLATFORM_TOO_OLD` carrying the
reason the settings screen shows, and **the ordinary notification must be unchanged** —
check it is still posted and still says what it said before.

This is the first thing to run on any device, because everything below branches on it.

## 2. Battery and wear, without unlocking (SC-001, User Story 1)

```bash
gp --es cmd monitor --es value on
gp --es cmd inject --es model 0x1420 --ei left 72 --ei right 68 --ei case 90 \
   --es wear in_ear --es address DE:B0:60:00:00:01
gp --es cmd live
```

Expect `posted=true`, both bud levels, the case level, and `wear left=IN_EAR right=IN_EAR`.

Then look at the phone: lock it and read the levels off the lock screen without unlocking.
That is SC-001, and it is the one step a terminal cannot fully answer.

### Unknown is not zero (FR-002)

```bash
gp --es cmd inject --es model 0x1420 --ei left 40 --ei right 30 --es wear in_ear \
   --es address DE:B0:60:00:00:01     # note: no --ei case
gp --es cmd live
```

Expect `case=unknown`, and a dash on the surface — **not** `0%`.

### Out of range is not unknown, and not stale (FR-007)

Stop injecting and wait for the sighting to age out (30 s), then:

```bash
gp --es cmd live
```

Expect `presence=OUT_OF_RANGE` and the levels suppressed. If the last known percentages are
still being shown as current, that is the failure this requirement exists to catch.

### Charging is visible (FR-010)

```bash
gp --es cmd inject --es model 0x1420 --ei left 40 --ei right 30 --ei case 90 \
   --ez charging true --es wear in_case --es address DE:B0:60:00:00:01
gp --es cmd live
```

Expect `charging=[...]` non-empty and a visibly different rendering.

## 3. The gate is obeyed (FR-011, FR-012, User Story 2)

With no accessory paired, or on a phone where the AAP channel does not open:

```bash
gp --es cmd probe
gp --es cmd live
```

Expect `noiseControl=LOCKED` with a reason — **not** absent. Tapping it on the device must
explain rather than silently do nothing.

With the channel open, change the mode from the surface and confirm the surface then shows
what the accessory reports:

```bash
gp --es cmd live       # note the mode before
# tap the action on the device
gp --es cmd live       # the mode must match what the accessory echoed
```

An accepted write that was never echoed must not move the surface. That is Principle I and
it is the characteristic failure of this transport.

## 4. Sensing disclosure and the reading (User Story 3, D1)

```bash
gp --es cmd hr --es value on
sleep 20
gp --es cmd live
```

Expect `sensing=DISCLOSED valueShown=true`. **The command prints no heart rate** — that is
deliberate and is the FR-023 rule, which D1 did not relax for diagnostic paths.

Hide the value and confirm the disclosure survives (FR-018a):

```bash
gp --es cmd set --es key liveActivityShowHeartRate --es value off
gp --es cmd live
```

Expect `sensing=DISCLOSED valueShown=false` — still disclosed. If the disclosure disappears
with the value, that is the failure FR-018a names.

Then stop it from the surface and confirm the sensor really stopped:

```bash
# use the stop action on the device
gp --es cmd hr --es value status     # reports must have ceased
```

With no session, nothing about heart rate may appear at all (FR-017):

```bash
gp --es cmd hr --es value off
gp --es cmd live                      # sensing=IDLE, and no other heart-rate field
```

## 5. It is a view onto monitoring, not a thing of its own (FR-008, FR-019)

```bash
gp --es cmd set --es key liveActivityEnabled --es value off
gp --es cmd live                      # posted=false, and monitoring still running

gp --es cmd set --es key liveActivityEnabled --es value on
gp --es cmd monitor --es value off
gp --es cmd live                      # posted=false — it went with monitoring
```

## 6. Notifications denied

```bash
adb shell pm revoke $PKG android.permission.POST_NOTIFICATIONS
gp --es cmd hr --es value on
gp --es cmd live                      # availability=NOTIFICATIONS_DENIED, posted=false
adb shell dumpsys activity services $PKG | grep isForeground   # still true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
```

The service must still run and sensing must still be honest about itself — nothing may sense
silently because a notification could not be shown.

## 7. Before committing

```bash
./gradlew spotlessCheck lintDebug testDebugUnitTest
```

## Afterwards

```bash
adb shell svc power stayon false
```

## The one thing this cannot prove from a terminal

Whether the platform actually **promotes** the notification. `hasPromotableCharacteristics()`
answers whether it qualifies, and the `live` command reports that — but qualifying and being
promoted are different claims, and only the lock screen settles the second one. Look at the
phone. If it qualifies and is still not promoted, that is a device or OEM decision and
belongs in `docs/protocol-research.md` as a field note.
