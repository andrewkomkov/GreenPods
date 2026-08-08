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

**The phone must be paired to an Apple or Beats audio accessory**, though nothing has to be in
range. "No accessory" below means none nearby, not none ever paired: an advertisement that
resolves to no bond is somebody else's and `BondedPodResolver` discards it, injected ones
included. On a phone that has never paired one, `inject` reports `accepted=true`, `dump` shows
`"pods":[]` with an `Ignoring a nearby …` diagnostic, and every `cal` action that names an
accessory refuses. There is no adb path around that filter, and there should not be — it is
what stops a stranger's earbuds taking the bonded key.

**Every `--es samples` value below is quoted twice.** `adb shell` runs the command through a
shell on the device, and that shell splits at `;`, so `--es samples "a;b"` delivers only `a`
and the rest fails silently. The `'"…"'` form is what survives.

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
gp --es cmd inject --es model 0x1420 --ei left 70 --ei right 70
gp --es cmd cal --es value start
gp --es cmd cal --es value feed --es samples '"60@0,0,0;60@6290,0,0;60@0,10920,0;60@0,0,10920"'
gp --es cmd cal --es value status
```

The run is fed to the end because verdicts only exist once it reaches `REVIEWING` — mid-run
every axis reads `pending`, deliberately, since a placeholder number is the one thing this
feature must never print. `feed` advances the poses itself, so the `advance` before each
segment is optional.

Expect `yaw = MEASURED … field=O1 delta=6290`, and a `degreesPerUnit` of roughly
`90 / 6290 ≈ 0.0143`. Two things must hold: the scale is derived from the **difference**
against the neutral hold (FR-010), so a neutral of `60@1000,0,0` before `60@6290,0,0`
must give a delta of 5290 and not 6290; and no other axis acquires a number (FR-011).

```bash
# the same run against a non-zero neutral: yaw must read delta=5290
gp --es cmd cal --es value start
gp --es cmd cal --es value feed --es samples '"60@1000,0,0;60@6290,0,0;60@0,10920,0;60@0,0,10920"'
gp --es cmd cal --es value status
```

## 3. Every refusal, one at a time (User Story 3, SC-004)

Each of these must produce a verdict and **no number**. Run them as separate sessions —
`abandon` between each — so one failure cannot be mistaken for a leftover of the previous.

A pose that fails to hold **stays current**: the wizard does not walk past it, so that it can
be repeated or skipped (FR-015). Two consequences, and both were found by walking this page
rather than by reading it. A following segment lands on the same pose again, so a whole run
cannot be fed as one spec once a pose has failed; and the run never reaches `REVIEWING` on its
own, so `cal skip` is what turns the refusal into a printed verdict. Mid-run, `cal status`
names the failure on the `AWAITING` line.

```bash
# Never settles: the jitter is wider than the tolerance -> NOT_HELD
gp --es cmd cal --es value start
gp --es cmd cal --es value feed --es samples '"60@0,0,0;60@6290,0,0~4000"'
gp --es cmd cal --es value status          # AWAITING YAW, with what went wrong attached
gp --es cmd cal --es value skip
gp --es cmd cal --es value feed --es samples '"60@0,10920,0;60@0,0,10920"'
gp --es cmd cal --es value status          # yaw = NOT_HELD, and the siblings survive
gp --es cmd cal --es value abandon

# Held too briefly -> NOT_HELD, the same way
gp --es cmd cal --es value start
gp --es cmd cal --es value feed --es samples '"60@0,0,0;8@6290,0,0"'
gp --es cmd cal --es value skip
gp --es cmd cal --es value feed --es samples '"60@0,10920,0;60@0,0,10920"'
gp --es cmd cal --es value status
gp --es cmd cal --es value abandon

# Two fields responding comparably -> INCONCLUSIVE, not the larger of the two
gp --es cmd cal --es value start
gp --es cmd cal --es value feed --es samples '"60@0,0,0;60@5900,6290,0;60@0,10920,0;60@0,0,10920"'
gp --es cmd cal --es value status          # yaw = INCONCLUSIVE contenders=O1,O2
gp --es cmd cal --es value abandon

# The stream stops mid-run -> STOPPED, nothing stored
gp --es cmd cal --es value start && gp --es cmd cal --es value advance
gp --es cmd monitor --es value off
gp --es cmd cal --es value status
```

Then `cal show` after each: **the stored calibration must be unchanged** in every case.

`INCONCLUSIVE` with **no** contenders is a different finding from `INCONCLUSIVE contenders=…`:
it means no field moved past the plateau tolerance at all, so the pose measured nothing. Both
say so in words; neither carries a number.

## 4. The axis check (User Story 2, FR-012, FR-013)

```bash
# A pose that moves O3 while the app expects O2 for pitch
gp --es cmd cal --es value start
gp --es cmd cal --es value feed --es samples '"60@0,0,0;60@6290,0,0;60@0,0,10920;60@0,0,10920"'
gp --es cmd cal --es value status
```

Expect `pitch = MISMATCHED expected=O2 responding=O3`, and `finish` must **not** store a
pitch scale. This is the outcome the motivating capture could not settle, so it is the one
whose behaviour matters most: a wrong axis has to be louder than a wrong number, not quieter.

## 5. A suspect result needs saying yes twice (FR-018)

```bash
# 90° from 1000 units implies 0.09°/unit, outside the plausible 0.0015..0.05
gp --es cmd cal --es value start
gp --es cmd cal --es value feed --es samples '"60@0,0,0;60@1000,0,0;60@0,10920,0;60@0,0,10920"'
gp --es cmd cal --es value finish        # must refuse, and say why
gp --es cmd cal --es value confirm
gp --es cmd cal --es value finish        # now stores, still flagged SUSPECT
```

The numbers must be visible in the refusal. "This looks wrong" without the arithmetic is an
opinion; with it, it is a measurement the reader can check.

**The delta has to sit in a narrow band, and an earlier draft of this page put it outside.**
A delta under 900 units is discarded as noise before any scale is derived, and the plausible
range ends at `0.05°/unit`, so for the 90° yaw pose only roughly 900–1800 units lands between
the two. The 40 units this section used to suggest yields `INCONCLUSIVE` with no contenders
and `finish` stores without asking — which looks like the suspect path passing, and is the
suspect path never running.

## 6. Storing, applying, and getting back out (FR-020, FR-021, FR-023)

```bash
gp --es cmd cal --es value finish
gp --es cmd cal --es value export        # paste this into docs/protocol-research.md
gp --es cmd cal --es value show          # applied=true for the connected model only
gp --es cmd cal --es value clear
gp --es cmd cal --es value show          # back to UNCALIBRATED, fallback labelled as such
```

Inject a **different model** and confirm the stored calibration is not applied to it (FR-021).
The id has to be one `PodModel` knows — an unknown one injects as `UNKNOWN`, which is filtered
out of `pods` and so proves nothing:

```bash
gp --es cmd inject --es model 0x2720 --ei left 70 --ei right 70   # AirPods Pro 3
gp --es cmd cal --es value show
```

The stored model must still be listed, with every axis `applied=false`, and the connected one
must read `UNCALIBRATED`.

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

## What was actually run

**2026-08-08, sections 1–6, no accessory and no head.** Android 17 emulator (API 37,
`google_apis_ps16k`, arm64, Pixel 8 profile), debug build `0.5.2-debug`, every pose injected
with `cal feed`. The AAP transport is unavailable there, so `cal start` reported
`cannot stream — UNAVAILABLE` on every run — which is the refusal SC-005 requires the wizard to
survive, and it survived it.

Reproduced: `cal show` on a fresh install with all three axes present and `UNCALIBRATED`, and
the same set under `headCalibration` in `dump`; a clean run giving
`yaw = MEASURED 0.01431 deg/unit field=O1 delta=6290`; a neutral of 1000 units giving
`delta=5290`, so the scale is a difference (FR-010); `INCONCLUSIVE contenders=O1,O2` for two
comparable responders; `MISMATCHED expected=O2 field=O3` with no pitch scale (FR-013);
`NOT_HELD` carrying what moved and by how much; `SUSPECT` with `finish` refusing until
`confirm` (FR-018); `export`, a 240-row `fixture` labelled `SYNTHETIC`, `clear`, and a stored
AirPods Pro 2 record reading `applied=false` while a connected AirPods Pro 3 read
`UNCALIBRATED` (FR-021).

Five things did **not** work as this page described them, and all five are now fixed above
rather than left as folklore:

1. `inject` needs the phone to be **paired** to an Apple or Beats accessory. Without a bond the
   sighting is discarded as a stranger's and every `cal` action that names an accessory
   refuses. A bond record was hand-written into the emulator's `bt_config.conf` to get past it.
2. `--es samples "a;b"` is split by the **device's** shell, so only the first segment arrived.
   The value needs quoting twice.
3. A pose that fails to hold stays current, so the documented refusal runs never reached
   `REVIEWING` and printed no verdict. `cal skip` is what completes them.
4. `cal status` did not print the refusal carried by the `AWAITING` state, so an attempted-and
   -failed pose looked identical to an untouched one. Fixed in `CalibrationDriver`.
5. The suspect example used a 40-unit delta, which is below the 900-unit noise floor: it yields
   `INCONCLUSIVE` and `finish` stores without asking. A delta of ~1000 units is what exercises
   FR-018, and `INCONCLUSIVE` with an empty contender set now says what it means instead of
   printing `contenders=` with nothing after it.

One thing was found and **not** fixed, because it is older and wider than this feature: when
the BLE scanner is unavailable — Bluetooth off, or an emulator whose scanner has gone away —
`dump` reports `scanFailure`, and `inject` stops working too. `PodRepository.sighted` merges
the injected sightings into the scanner's flow, so the failure that ends one ends both, and
`inject` still answers `accepted=true` while the sighting goes nowhere. Cycling Bluetooth and
restarting the app clears it. Worth recording because it is the one condition under which the
whole "verifiable with no hardware" claim quietly stops holding.

**Section 7 has not been run.** It needs AirPods Pro 3, a live AAP channel and a neck, and no
number above is a measurement of any accessory — every sample was injected.

## What this cannot verify

The wizard cannot see the true angle of a pose — only that the values were steady. Every
scale it produces inherits the accuracy of "chin toward the shoulder is about 90°". Sections
2–6 verify the *machinery* exactly; section 7 verifies the *result* only to within how well
the poses were performed. That limit is the feature's accuracy floor, it is stated on screen
(FR-019), and it should be stated again by anyone quoting a number this produced.
