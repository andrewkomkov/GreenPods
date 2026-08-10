# Quickstart: verifying Head Tracking Calibration

**Feature**: `specs/004-head-tracking-calibration` | **Date**: 2026-08-06

How to prove the feature works, from a terminal. Screenshots corroborate; the dump verifies.

Sections 1–6 need **no accessory and no head** — injected orientation samples walk the same
pipeline real frames do, which is what makes SC-005 achievable at a desk. Section 7 is the
part only a neck can answer, and it is deliberately last: everything that can be checked
without a wearer is checked before anyone is asked to hold a pose.

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
adb shell am start -n $PKG/io.github.andrewkomkov.greenpods.MainActivity

# A sleeping screen breaks a long run and looks like a stalled wizard.
adb shell svc power stayon usb
```

Read any result with:

```bash
adb logcat -c && gp --es cmd cal --es value status && sleep 2 && adb logcat -d -s GreenPodsDebug
```

## 1. What is stored right now? (FR-027)

```bash
gp --es cmd cal --es value show
```

On a fresh install, every axis must read `UNCALIBRATED` and be **present** — an absent axis
and a never-measured one are different facts and the dump has to keep them apart.

```bash
gp --es cmd dump | python3 -m json.tool | grep -A20 headCalibration
```

Run this first on any device. Everything below changes it, and section 6 puts it back.

## 2. A clean measurement, with no hardware (User Story 1, SC-005)

```bash
gp --es cmd cal --es value start
gp --es cmd cal --es value advance                                    # neutral
gp --es cmd cal --es value feed --es samples "60@0,0,0"
gp --es cmd cal --es value advance                                    # yaw
gp --es cmd cal --es value feed --es samples "60@6290,0,0"
gp --es cmd cal --es value status
```

Expect `yaw = MEASURED … field=O1 delta=6290`, and a `degreesPerUnit` of roughly
`90 / 6290 ≈ 0.0143`. Two things must hold: the scale is derived from the **difference**
against the neutral hold (FR-010), so feeding `60@6290,0,0` after a neutral of `60@1000,0,0`
must give a delta of 5290 and not 6290; and no other axis acquires a number (FR-011).

## 3. Every refusal, one at a time (User Story 3, SC-004)

Each of these must produce a verdict and **no number**. Run them as separate sessions —
`abandon` between each — so one failure cannot be mistaken for a leftover of the previous.

```bash
# Never settles: the jitter is wider than the tolerance -> NOT_HELD
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@6290,0,0~4000"

# Held too briefly -> NOT_HELD
gp --es cmd cal --es value feed --es samples "60@0,0,0;8@6290,0,0"

# Two fields responding comparably -> INCONCLUSIVE, not the larger of the two
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@5900,6290,0"

# The stream stops mid-run -> STOPPED, nothing stored
gp --es cmd cal --es value start && gp --es cmd cal --es value advance
gp --es cmd monitor --es value off
gp --es cmd cal --es value status
```

Then `cal show` after each: **the stored calibration must be unchanged** in every case.

## 4. The axis check (User Story 2, FR-012, FR-013)

```bash
# A pose that moves O3 while the app expects O2 for pitch
gp --es cmd cal --es value start
gp --es cmd cal --es value advance && gp --es cmd cal --es value feed --es samples "60@0,0,0"
gp --es cmd cal --es value advance && gp --es cmd cal --es value feed --es samples "60@0,0,0"
gp --es cmd cal --es value advance && gp --es cmd cal --es value feed --es samples "60@0,0,10920"
gp --es cmd cal --es value status
```

Expect `pitch = MISMATCHED expected=O2 responding=O3`, and `finish` must **not** store a
pitch scale. This is the outcome the motivating capture could not settle, so it is the one
whose behaviour matters most: a wrong axis has to be louder than a wrong number, not quieter.

## 5. A suspect result needs saying yes twice (FR-018)

```bash
# 90° from 40 units implies 2.25°/unit — a full turn from a rounding error
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@40,0,0"
gp --es cmd cal --es value finish        # must refuse, and say why
gp --es cmd cal --es value confirm
gp --es cmd cal --es value finish        # now stores, still flagged SUSPECT
```

The numbers must be visible in the refusal. "This looks wrong" without the arithmetic is an
opinion; with it, it is a measurement the reader can check.

## 6. Storing, applying, and getting back out (FR-020, FR-021, FR-023)

```bash
gp --es cmd cal --es value finish
gp --es cmd cal --es value export        # paste this into docs/protocol-research.md
gp --es cmd cal --es value show          # applied=true for the connected model only
gp --es cmd cal --es value clear
gp --es cmd cal --es value show          # back to UNCALIBRATED, fallback labelled as such
```

Inject a **different model** and confirm the stored calibration is not applied to it (FR-021):

```bash
gp --es cmd inject --es model 0x2014 --ei left 70 --ei right 70
gp --es cmd cal --es value show
```

## 7. On a head (User Story 1's Independent Test, SC-002, SC-003)

Only now, and only with AirPods Pro 3 in the ears and the AAP channel live:

```bash
gp --es cmd probe --ez force true        # confirm AAP is open before blaming the wizard
gp --es cmd hid                          # confirm the motion service is described
```

Open the calibration screen and run it properly. Then, on the head-tracking screen:

- turn the head a deliberate quarter-turn and read the yaw. **SC-002**: within 15° of 90°,
  where before calibration it was wrong by more than double that.
- move through the full range of all three axes and watch for an angle a neck cannot reach.
  **SC-003**: the 195° pitch the 2026-08-05 capture produced must not recur.
- check the gesture thresholds still fire (FR-024). They are expressed in degrees, so
  calibration changes what they mean physically — this is intended, and it is also the
  change most likely to be experienced as "gestures stopped working". If a nod no longer
  registers, that is a finding to record, not a bug in the wizard.

Record what was actually run in `docs/protocol-research.md`, with the export from section 6
attached. A green test suite is not a substitute: two instrumented tests in this project pass
only when no AirPods are nearby, and the whole point of this feature is a number that came
off real hardware.

## What this cannot verify

The wizard cannot see the true angle of a pose — only that the values were steady. Every
scale it produces inherits the accuracy of "chin toward the shoulder is about 90°". Sections
2–6 verify the *machinery* exactly; section 7 verifies the *result* only to within how well
the poses were performed. That limit is the feature's accuracy floor, it is stated on screen
(FR-019), and it should be stated again by anyone quoting a number this produced.
