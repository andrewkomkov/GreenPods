# Quickstart: validating heart rate

How to prove this feature works, in the order the proofs get harder. Screenshots
corroborate; the dump verifies.

## Prerequisites

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
PKG=io.github.andrewkomkov.greenpods.debug
CMP=$PKG/io.github.andrewkomkov.greenpods.debug.GreenPodsDebugReceiver
gp() { adb shell am broadcast --receiver-foreground -n $CMP \
       -a io.github.andrewkomkov.greenpods.DEBUG "$@" >/dev/null; }
```

Hardware: AirPods Pro 3, paired and connected, on a phone where the AAP channel opens —
`gp --es cmd hiddenapi` must answer `Granted`. Health Connect installed for the last two
sections.

**No Powerbeats Pro 2 is available**, so the GATT route has no section here. It is
exercised off-device against a fake source (T057–T061a) and ships unverified on hardware,
which the spec's Assumptions record.

## 1 — Off device: the whole decode path

```bash
./gradlew :core:bluetooth:testDebugUnitTest :core:data:testDebugUnitTest :core:model:testDebugUnitTest
```

What must pass here before anything is installed, per [contracts/aap-hid.md](./contracts/aap-hid.md):

- The captured descriptor frame parses to four services with ids `0x10`..`0x13`, and the
  heart-rate one is found **by name**, not by id.
- The same frame with the ids renumbered still finds the heart-rate service. This is
  FR-002, and it is the test that fails if anyone hard-codes `0x13`.
- The captured report descriptor yields the field offsets in the contract table.
- The captured report series decodes to the documented BPM/confidence pairs, and the four
  settling readings are classified as untrusted.
- A descriptor with no confidence field yields no usable layout (R-2).
- A frame delivered in two halves reassembles; a read carrying two frames yields two.
- A BPM of 0, or of 251, is discarded as implausible, not shown.

## 2 — On device: it does not run unasked

```bash
adb shell pm clear $PKG
./gradlew installDebug
adb shell setprop log.tag.AapTransport DEBUG
adb logcat -c
gp --es cmd hr --es value status
```

Expect `state=OFF enabled=false`. Then, after a minute of the app running:

```bash
adb logcat -d -s AapTransport | grep "tx" | grep " 17 00 "
```

Expect nothing. FR-011: no start frame is ever sent for a feature the user did not enable.

## 3 — A reading you can trust

Wear both buds, then:

```bash
gp --es cmd hr --es value on
sleep 5  && gp --es cmd hr --es value status   # expect SETTLING, no number anywhere
sleep 25 && gp --es cmd hr --es value status   # expect MEASURING
```

- SC-001: `MEASURING` within 30 seconds, and no state before it exposes a value.
- FR-008: `SETTLING` is visibly not a measurement, on screen and in the status line.
- FR-002: `service=0x13` in the status line came from discovery — confirm by checking the
  descriptor frame in the log rather than trusting the number.

Then exert yourself for a minute and watch the on-screen value follow, and stop:

```bash
gp --es cmd hr --es value off
adb logcat -d -s AapTransport | grep "tx" | tail -2   # interval 00 00 00 00
```

FR-014: the last frame out is a stop, and reports stop arriving. A UI that stops showing a
number while the sensor keeps running fails this step even though it looks identical.

## 4 — It stops when it should

```bash
gp --es cmd hr --es value on
# take one bud out, or:
gp --es cmd inject --es model 0x2720 --es wear out --es address <address>
gp --es cmd hr --es value status      # expect UNAVAILABLE, lastStop=notWorn
# put it back
gp --es cmd hr --es value status      # expect STARTING, then SETTLING, then MEASURING
```

FR-015 and the disconnect edge case: sensing resumes on its own, and the displayed value
was cleared rather than frozen while it was gone.

## 5 — The health store

```bash
gp --es cmd health --es value status          # availability and permission
gp --es cmd set --es key hrHealthConnect --es value on
gp --es cmd hr --es value on
sleep 600
gp --es cmd hr --es value status              # note trusted=N
gp --es cmd health --es value count --el minutes 10
```

- SC-003: open any health app and confirm the records are attributed to GreenPods **and**
  to AirPods Pro 3, with times correct to the second.
- SC-009: `trusted=N` equals the sample count reported by `health count`.
- FR-019: repeat with an interruption — `hr off`, wait 20 s, `hr on` inside the same
  minute — and confirm the counts still match, with no duplicate records for that window.
- FR-018: revoke the permission in system settings mid-session. Writing stops, the reading
  keeps working, and nothing prompts.

Without permission granted, run the same session and confirm the count is zero while the
screen still shows a heart rate (US3 scenario 1).

## 6 — Nothing leaks

After a measuring session, with values known to have been on screen:

```bash
python3 scripts/readdump.py | grep -iE "bpm|heartRateBpm|[0-9]{2,3}\"?\s*bpm"
gp --es cmd hr --es value status
python3 scripts/readdump.py | python3 -c "import sys,json; \
  [print(e['category'], e['message'], e['detail']) for e in json.load(sys.stdin)['diagnostics']]"
```

SC-005 and FR-023: no plausible heart rate appears in any of it. The dump carries the
state and the counters; the diagnostics carry unknown traffic by shape. If a number is
found, the feature is not shippable regardless of how well the rest works.

FR-024 as well — readings leave the device only through the health store. The app's one
outbound network path is `UpdateChecker`, so confirm nothing else opened a socket during
the session:

```bash
adb shell dumpsys netstats detail | grep -A3 "$(adb shell dumpsys package $PKG | grep userId= | head -1)"
```

Also check the timestamps you just wrote, because this is where a wrong epoch shows up
plainly: the records in the health app must sit at the time of the session, not fifteen
hours earlier. See research.md R-4a.

## 7 — Locked, not hidden

On a phone where the channel does not open, or with the buds unpaired:

```bash
gp --es cmd probe
gp --es cmd hr --es value status     # expect LOCKED, with the transport's own reason
```

And on a model with no sensor — inject one:

```bash
gp --es cmd inject --es model 0x1420 --es address DE:B0:60:00:00:01
gp --es cmd hr --es value status     # expect UNSUPPORTED
```

SC-008: the two are different states with different sentences. "This phone can't" and
"these earbuds don't have it" must never be shown as the same thing.

## 8 — What cannot be claimed yet

SC-002 requires a reference heart-rate monitor worn simultaneously for ten minutes at
rest, and the two series compared. Until that is done and recorded in
`docs/protocol-research.md`, the accuracy criterion is unmet — not "probably fine". Record
the comparison as a capture, with the reference device named.

SC-006 requires an hour with the feature disabled and the buds' battery drain compared
against the app not being installed. The `hrIntervalMs` setting exists so the cost of the
enabled case can be measured at more than one cadence.
