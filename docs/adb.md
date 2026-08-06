# Driving GreenPods from adb

Every feature is exercisable, and every piece of state readable, without touching the
screen. This is not a convenience: the states that matter are physical — a bud leaving
an ear, a case closing, a battery crossing a threshold — and the transport that matters
most is unavailable on nearly every phone. Tapping at coordinates in a screenshot is
guessing; this is verification.

The command surface lives in the **debug build only** (`app/src/debug`). Release builds
ship no such entry point.

## Setup

```bash
PKG=io.github.andrewkomkov.greenpods.debug
CMP=$PKG/io.github.andrewkomkov.greenpods.debug.GreenPodsDebugReceiver

./gradlew installDebug
adb shell pm grant $PKG android.permission.BLUETOOTH_SCAN
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell am start -n $PKG/io.github.andrewkomkov.greenpods.MainActivity
```

A helper, because every command is the same shape:

```bash
gp() { adb shell am broadcast --receiver-foreground -n $CMP \
       -a io.github.andrewkomkov.greenpods.DEBUG "$@" >/dev/null; }
```

`--receiver-foreground` matters. Without it, modern Android defers the broadcast while
the app is not in the foreground and the command silently never arrives.

## Reading state

```bash
adb logcat -c && gp --es cmd dump && sleep 3 && adb logcat -d -s GreenPodsDebug
```

Prints one JSON document: every accessory with battery, wear state, RSSI, the status
**and reason** of all three transports, which features are usable and which are locked
with why, plus all settings, the stored head calibration per model, and the diagnostics
log.

Dumps longer than logcat's per-message limit are emitted as `[1/3] …` chunks; the
document is the chunks concatenated in order. `scripts/readdump.py` does that:

```bash
adb logcat -c && gp --es cmd dump && sleep 3 && python3 scripts/readdump.py
```

Options: `--el waitMs 8000` — how long to wait for the scanner to see something before
reporting an empty list. The pods flow emits an empty list the moment it is subscribed,
so taking the first emission would report "nothing nearby" for an accessory that is
simply one advertisement interval away.

## Transports

```bash
gp --es cmd probe                 # force a probe on the nearest accessory
gp --es cmd probe --ez force false  # honour the probe-once cache instead
gp --es cmd hiddenapi             # can this phone reach the Apple protocol at all?
```

`hiddenapi` answers with no accessory present, which is the point: it separates "this
phone will never open the channel" from "the buds are not here". The channel needs a
hidden `BluetoothSocket` constructor that Android's non-SDK blocklist denies by
default, so `Granted` is the precondition for everything the Apple protocol carries.

Prints, for example:

```
probe: 68:55:DE:44:54:6C -> AVAILABLE :: Channel open. Settings can be read and written.
```

On failure the route in brackets says which socket API got that far — the single most
useful fact in a bug report about the Apple protocol.

## Writing over the Apple protocol

```bash
gp --es cmd anc --es value TRANSPARENCY   # OFF | NOISE_CANCELLATION | TRANSPARENCY | ADAPTIVE
```

```
anc: wrote TRANSPARENCY to 68:55:DE:44:54:6C -> accepted=true, accessory reports TRANSPARENCY
```

Two separate facts, and the second is the one that matters. `accepted=true` only means
the bytes reached the socket; `accessory reports …` is the accessory's own control
update coming back through the decoder. A write that is accepted and never echoed is
the characteristic failure of this transport — it is what both a raced write and a
transport built without a `BluetoothAdapter` look like.

### Sending a frame the app does not know how to build

```bash
gp --es cmd raw --es hex "040004001700000010000F000878420B081310021A050140420F00"
```

Hex with **no spaces** — `am` splits its arguments on whitespace, so a spaced string
arrives as its first byte alone.

That particular frame starts the heart-rate sensor at 1 Hz; the same frame with the
interval set to zero stops it. This is how undecoded protocol behaviour gets
characterised, and it goes over the ordinary session on purpose — an experiment on a
private channel would prove nothing about the real one.

## The live status surface

```bash
gp --es cmd live
```

Prints what the lock-screen surface would show, and why it would show nothing:

```
live: availability=AVAILABLE reason="none" posted=true
live: accessory="AirPods Pro" presence=IN_RANGE
live: battery left=72% right=68% case=unknown charging=[CASE]
live: wear left=IN_EAR right=IN_EAR
live: noiseControl=OFFERED mode=TRANSPARENCY
live: sensing=DISCLOSED valueShown=true
```

Unavailable, with the reason the settings screen shows:

```
live: availability=PLATFORM_TOO_OLD reason="API 34, needs 36" posted=false withheld=…
```

`availability` separates four situations that all look like a missing feature from the
outside: `PLATFORM_TOO_OLD` (nothing can be done), `PROMOTION_REFUSED` (the user turned it
off, and there is a settings screen to go to), `NOTIFICATIONS_DENIED`, and `NOT_PROMOTABLE`
(the device examined the notification and declined). `withheld` says why a surface was not
posted on a phone that could have shown one — monitoring off, switched off, no accessory,
or dismissed.

**It prints no heart rate.** `valueShown=true` says the surface would carry a number; it
never says which. That looks inconsistent with allowing the value on a lock screen, and is
not: no reading may appear in any diagnostic path, and this output is the most diagnostic
thing in the app — it gets pasted into bug reports. A lock screen shows a user their own
body's data; this does not.

Drive it without any hardware, which is the point:

```bash
gp --es cmd monitor --es value on
gp --es cmd inject --es model 0x1420 --ei left 72 --ei right 68 --ei case 90 \
   --es wear in_ear --es address DE:B0:60:00:00:01
gp --es cmd live

gp --es cmd set --es key liveActivityEnabled --es value off        # posted=false
gp --es cmd set --es key liveActivityShowHeartRate --es value off  # valueShown=false
```

Stop injecting and wait 30 s: `presence` becomes `OUT_OF_RANGE` and the levels are
**dropped** rather than carried forward. A stale percentage shown as current is the failure
that check exists to catch.

### Pressing its buttons

```bash
gp --es cmd live --es action cycle     # ask the accessory for the next listening mode
gp --es cmd live --es action stop      # stop heart-rate sensing, in the earbuds
gp --es cmd live --es action dismiss   # swipe the surface away
```

These send the **same intents the buttons send**, to the same receiver and the same service.
Not a shortcut past them into the gateway: a route that reached the gateway directly would
prove the gateway works and say nothing about whether the button reaches it, which is the
half that is new.

`cycle` is the one to watch carefully, because it is where this transport's characteristic
failure shows up:

```bash
gp --es cmd live                       # note the mode
gp --es cmd live --es action cycle
gp --es cmd live                       # the mode moves only once the accessory echoes it
```

An accepted write is not a change. If the second `live` shows the requested mode before the
accessory has confirmed it, that is the bug — not a fast UI.

`dismiss` exists because the alternative is swiping a notification, which a script cannot do,
and it is the only way to reach the dismissal rule at all. After it, `live` must report
`withheld=Dismissed` and keep reporting it across several state ticks — a surface that
returns by itself is the failure the rule exists to prevent. Starting heart-rate sensing, or
a different accessory arriving, are the two things allowed to bring it back.

What the ordinary notification does after a dismissal is **not** disappear: a foreground
service must have one. The dismissal buys the quiet notification instead of the promoted
surface, and nothing more.

### Reading what the accessory says about its own sensors

```bash
gp --es cmd hid
```

Prints every HID service the accessory announced — id, name, the report descriptor in
hex, and the descriptor walked into fields:

```
hid: service 0x10 name=devmotion tags=headTracking descriptor=… bytes
hid: 0x10 report 1 input=… bytes
hid: 0x10   in  page=0x0020 usage=0x0201 bits=16 x1 at byte 1
```

This is the ground truth for every offset and every scale the app reads out of a sensor
report. When a decoded value looks wrong — a head tilt of 195°, say — the first question
is what the descriptor actually declares, and without this the only way to answer it was
to guess. Descriptors carry no measurements: no heart rate, no orientation, only the
shape of the reports that will carry them.

To see the raw frames behind any of this:

```bash
adb shell setprop log.tag.AapTransport DEBUG
adb logcat -s AapTransport          # tx/rx, hex
```

Off by default: these frames carry serial numbers and the accessory's whole
configuration.

## Injecting an accessory

The point of the whole surface: drive states that cannot be produced on demand. An
injected sighting goes through exactly the same accumulate → age → decorate pipeline as
a real advertisement, and is labelled `(injected)` so it can never be mistaken for real
hardware. It ages out after 30 seconds like anything else.

```bash
# AirPods Pro 2, both buds in the ears, 40 % / 30 %, case at 90 %
gp --es cmd inject --es model 0x1420 --ei left 40 --ei right 30 --ei case 90 \
   --es wear in_ear --es address DE:B0:60:00:00:01
```

| Extra | Meaning |
|---|---|
| `--es model 0x1420` | model id, as it appears on the air |
| `--ei left/right/case` | percent; omit `case` for the "unknown" sentinel |
| `--es wear` | `in_ear`, `one_out`, `out`, `in_case` |
| `--ez charging true` | set the charging flags |
| `--es address` | which accessory this is; reuse it to drive transitions |
| `--ei rssi -45` | signal strength, which decides ranking |
| `--es payload "07 19 …"` | a full 27-byte payload in hex, bypassing the builder |

### Auto-pause, end to end

```bash
gp --es cmd inject --es model 0x1420 --es wear in_ear  --es address DE:B0:60:00:00:01
sleep 3
gp --es cmd inject --es model 0x1420 --es wear one_out --es address DE:B0:60:00:00:01
sleep 4   # music pauses
gp --es cmd inject --es model 0x1420 --es wear in_ear  --es address DE:B0:60:00:00:01
sleep 4   # music resumes
```

Check what actually happened, including the decisions that chose to do nothing:

```bash
adb shell dumpsys media_session | grep -A9 "package=com.spotify.music" | grep -o "state=[A-Z]*("
python3 scripts/readdump.py | python3 -c "import sys,json; \
  [print(e['message'], '|', e['detail']) for e in json.load(sys.stdin)['diagnostics'] \
   if e['category']=='MEDIA']"
```

Each decision records its inputs — `playing`, `pausedBy`, `bluetoothOutput`, and the
relevant settings — which is what turns "it didn't pause" into something diagnosable.

### Low-battery warnings

```bash
gp --es cmd monitor --es value on
gp --es cmd set --es key lowBatteryThreshold --es value 25
gp --es cmd inject --es model 0x1420 --ei left 90 --es address DE:B0:60:00:00:01
gp --es cmd inject --es model 0x1420 --ei left 10 --es address DE:B0:60:00:00:01
adb shell dumpsys notification --noredact | grep -A3 GreenPods
```

Injecting 10 % twice must produce **one** notification: the warning fires per crossing,
not per advertisement.

## Settings

```bash
gp --es cmd set --es key autoPause --es value off
gp --es cmd set --es key scanMode --es value LOW_LATENCY
gp --es cmd set --es key lowBatteryThreshold --es value 30
```

Keys: `autoPause`, `autoResume`, `pauseOnlyWhenBothOut`, `backgroundMonitoring`,
`lowBatteryWarning`, `lowBatteryThreshold`, `headGestures`, `scanMode`,
`hrIntervalMs`, `hrConfidenceThreshold`, `hrHealthConnect`, `liveActivityEnabled`,
`liveActivityShowHeartRate`.

## Heart rate

```bash
gp --es cmd hr --es value on       # enables sensing, and starts the monitoring service
gp --es cmd hr --es value status
gp --es cmd hr --es value off      # sends interval 0 — the sensor stops in the earbuds
```

`hr status` prints one line and **no values**:

```
hr: state=MEASURING enabled=true source=AAP service=0x13 interval=1000ms \
    reports=63 trusted=59 discarded=0 lastStop=none
```

`state` is the gated state, so it reads `UNSUPPORTED` on a model with no sensor and
`LOCKED` where this phone cannot open the Apple protocol channel — two different
situations that must never be shown as one.

The confidence threshold ships **provisional** (128), bounded only between 21 and 156 by
the one capture in hand. Calibrating it is a measurement, not a rebuild:

```bash
gp --es cmd set --es key hrConfidenceThreshold --es value 100
gp --es cmd set --es key hrIntervalMs --es value 2000     # for battery measurement
```

To watch the frames themselves, including the start and stop writes:

```bash
adb shell setprop log.tag.AapTransport DEBUG
adb logcat -d -s AapTransport | grep "tx" | grep " 17 00 "
```

Heart-rate **report bodies are excluded from that log**; everything else on the channel
is not, and it still carries serial numbers and the accessory's whole configuration.

## Health Connect

```bash
gp --es cmd set --es key hrHealthConnect --es value on
gp --es cmd health --es value status
gp --es cmd health --es value count --el minutes 10
gp --es cmd health --es value clear
```

`count` prints counts, never samples, and filters on GreenPods' own `DataOrigin` —
counting everything in the window would report another app's chest strap as our output:

```
health: own records in last 10m: 10 records, 59 samples
```

`trusted=59` from `hr status` against `59 samples` here is SC-009, checked in two
commands.

**No command prints a heart rate.** Not `hr status`, not `dump`, not a diagnostic
detail. `dump` carries a `heartRate` object holding the state and the counters — the
old `heartRateBpm` field is gone.

## Head tracking calibration

The wizard that measures what a raw orientation unit is worth in degrees — or says why it
could not. Every action is one of the session's own, so this is the same wizard the screen
runs, driven from a terminal instead of a neck.

```bash
gp --es cmd cal --es value start      # begin a run against the connected accessory
gp --es cmd cal --es value status     # state, the pose being held, the verdicts so far
gp --es cmd cal --es value advance    # begin the hold for the pose now being shown
gp --es cmd cal --es value skip       # skip this pose; its axis stores nothing and says why
gp --es cmd cal --es value repeat     # re-run this pose without restarting the run
gp --es cmd cal --es value confirm    # keep a suspect result, so finish may store it
gp --es cmd cal --es value finish     # store the run
gp --es cmd cal --es value abandon    # end the run and store nothing
gp --es cmd cal --es value show       # what is stored, per model and per axis
gp --es cmd cal --es value export     # the stored calibration, machine-readable
gp --es cmd cal --es value clear      # discard the connected model's calibration
gp --es cmd cal --es value fixture    # emit the run's samples as a labelled fixture
```

`start` needs an accessory, real or injected — the calibration is keyed by **model**, and
there is nothing to key it to otherwise:

```bash
gp --es cmd inject --es model 0x1420 --ei left 70 --ei right 70
gp --es cmd cal --es value start
```

```
cal: started — model=AIRPODS_PRO_2 address=DE:B0:60:00:00:01 poses=4 (neutral first, then one per axis)
cal: nothing is stored until 'cal finish'; 'cal abandon' leaves any stored calibration untouched
cal: cannot stream — UNAVAILABLE :: this phone cannot carry the commands, or the accessory never described itself
cal: the run is still driveable with 'cal feed'. Injected samples exercise the wizard; they measure nothing about this accessory
```

Two separate facts again, and the second is the interesting one: the **session** starts
regardless, and the **stream** is the part that can be refused. The refusal is drawn from
`HeadTrackingController.Refusal`, so it is the same sentence the entry point on the head
gestures screen shows — adb and the screen cannot disagree about why.

An action that does not apply to the current state says so rather than doing nothing:

```
cal: advance ignored — state=IDLE. Start a run first: 'cal start'.
```

That follows the precedent `set` established, where a silently-ignored command turned out to
be indistinguishable from success.

### Feeding poses with no earbuds and no head

```bash
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@6290,0,0"
```

The spec is `count@o1,o2,o3[,horizontal,vertical][~jitter]`, semicolon-separated. Each
segment fills one pose's hold, advancing to the next pose as each hold completes, and the
samples are spread evenly across the hold — so `60@…` is a full hold with sixty samples in
it, and `8@…` is a hold too short to measure anything.

`~n` adds a **deterministic** alternating jitter of ±n on the three orientation fields, not a
random one, so a not-held run reproduces rather than merely happening.

```bash
# a clean yaw measurement: neutral, then O1 displaced by 6290 units
gp --es cmd cal --es value start
gp --es cmd cal --es value advance
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@6290,0,0"
gp --es cmd cal --es value status

# noise wider than the tolerance -> NOT_HELD, and no number
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@6290,0,0~4000"

# two fields responding comparably -> INCONCLUSIVE, never the larger of the two
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@5900,6290,0"

# a pose that moves O3 where the app expects O2 -> MISMATCHED, and no pitch scale
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@0,0,0;60@0,0,10920"
```

**`feed` addresses the session directly.** That is the one place in this project where the
wizard runs without a live stream, and it is deliberate: `HeadTrackingController.stream()`
refuses before a single sample arrives when the transport is gated, so injecting through it
would make the calibration path unverifiable on exactly the phones where verification matters
most. Injected samples are still published onto `PodRepository.onAapEvent` — the entry point
real frames arrive on — so they walk the rest of the pipeline too; that republishing is
suppressed while a live stream is running, so one sample cannot be counted twice.

Nothing an injected run produces is a measurement of an accessory. It measures the machinery.
This command exists in the **debug build only**.

### Reading a run

```
cal: state=HOLDING pose=YAW remaining=1800ms samples=44
cal: yaw    = pending
cal: pitch  = MEASURED 0.00412 deg/unit field=O2 delta=10920 units from PITCH
cal: roll   = NOT_HELD "never settled: O3 moved 3140 units over the whole hold, past the 900 allowed"
```

`field=` appears on every verdict that identified one, **including `MISMATCHED`** — which
field actually moved is the evidence this feature exists to produce, and it is the most
interesting thing it prints.

A verdict is always present; a number is not. `SKIPPED`, `NOT_HELD`, `INCONCLUSIVE`,
`MISMATCHED` and `CROSS_COUPLED` carry no scale at all, and on current hardware
`CROSS_COUPLED` for all three axes is the **expected** result of a real run rather than an
error — see `docs/protocol-research.md`.

### Storing, and refusing to

```bash
gp --es cmd cal --es value finish
```

```
cal: finish refused — 1 suspect result(s) not confirmed. Nothing stored
cal: yaw    = SUSPECT 2.25000 deg/unit field=O1 delta=40 units confirmed=false "90° from 40 units …"
cal: 'cal confirm' keeps it anyway; 'cal start' re-runs from the beginning
```

The arithmetic is in the refusal on purpose. "This looks wrong" without the numbers is an
opinion; with them it is something the reader can check. `confirm` takes an optional
`--es axis YAW`; without one it confirms every suspect result waiting on a decision.

Only `finish` writes. Abandoning, losing the stream or walking away leaves a previously
stored calibration byte-identical, so a failed re-run can never destroy a good one.

### What is stored

```bash
gp --es cmd cal --es value show
```

```
cal: AIRPODS_PRO_2 (connected) measuredAt=2026-08-06T14:22:31Z
cal:   yaw    = MEASURED 0.01431 deg/unit field=O1 delta=6290 units from YAW applied=true
cal:   pitch  = MISMATCHED expected=O2 field=O3 delta=10920 units — no scale stored for this axis applied=false
cal:   roll   = UNCALIBRATED applied=false
```

`applied=true` only ever appears for the connected model: a calibration is keyed by model, so
another model in the list is stored and idle. An axis with no calibration is **present** and
reads `UNCALIBRATED`, here and in `dump` — "never measured" and "the dump forgot it" have to
stay distinguishable.

The same set is in the state dump under `headCalibration`:

```bash
python3 scripts/readdump.py | python3 -c "import sys,json; \
  print(json.dumps(json.load(sys.stdin)['headCalibration'], indent=1))"
```

`clear` puts one model back to the fallback:

```bash
gp --es cmd cal --es value clear
gp --es cmd cal --es value show    # every axis UNCALIBRATED, angles from the labelled approximation
```

### Exporting a result

```bash
gp --es cmd cal --es value export
```

One JSON object, in the form `docs/protocol-research.md` takes:

```json
{"model":"AIRPODS_PRO_3","measuredAt":"2026-08-06T14:22:31Z","app":"0.5.2",
 "axes":{"yaw":{"verdict":"MEASURED","field":"O1","degreesPerUnit":0.01431,"deltaUnits":6290,
                "referenceDegrees":90.0,"applied":true,"holdMillis":2000,"spanUnits":210,"samples":61},
         "pitch":{"verdict":"MISMATCHED","expectedField":"O2","respondingField":"O3","deltaUnits":10920,
                  "referenceDegrees":45.0,"applied":false},
         "roll":{"verdict":"NOT_HELD","reason":"never settled: O3 moved 3140 units …",
                 "referenceDegrees":45.0,"applied":false}},
 "poses":[{"pose":"NEUTRAL","index":0,"samples":60,"holdMillis":2000,"source":"injected",
           "medians":{"o1":0.0,"o2":0.0,"o3":0.0,"horizontalAcceleration":0.0,"verticalAcceleration":0.0},
           "spans":{"o1":0,"o2":0,"o3":0,"horizontalAcceleration":0,"verticalAcceleration":0}}]}
```

Three things about it are contractual:

- **A verdict is always present; a scale is not.** No consumer may assume `degreesPerUnit`
  exists — a record that quietly omitted the failed axes would read as a three-axis
  calibration.
- **The evidence travels with the number.** `deltaUnits`, `referenceDegrees`, `spanUnits`,
  `holdMillis` and `samples` are what let a later reader judge the measurement, or re-derive
  it under a different assumed reference angle.
- **`referenceDegrees` is nominal** — the angle the wearer was asked for, never the one their
  neck reached. Say so wherever the export is quoted.

`poses` carries **all five decoded fields**, not the three the axes use. The accelerations are
the only thing in the frame that could distinguish a head rotation from the wearer moving, and
the repository currently contradicts itself about what bytes 28 and 30 are — the decoder calls
them accelerations, the research notes describe them as part of a four-int16 near-unit-norm
vector. A run of this wizard is the cheapest evidence either way. `poses` is empty when no run
has happened in this process; the axes then come from storage alone, and the command says so.

### Emitting a fixture

```bash
adb logcat -c && gp --es cmd cal --es value fixture && sleep 3
adb logcat -d -s GreenPodsDebug | sed -n 's/^.*cal-fixture: //p' \
  > core/bluetooth/src/test/resources/aap/head-tracking-poses.txt
```

Writes the run's samples out as a labelled sample series in the style of
`hr-report-series.txt`, one row per sample, with a provenance header naming the model, the
host phone, the date, the HID service and the rate. A run fed with `cal feed` is labelled
**SYNTHETIC** in that header and must never be used to pin a decoder; a run off a live stream
is labelled **CAPTURED**.

Do not edit an emitted fixture to make a test pass. A decoder that disagrees with a capture is
wrong, or the capture has to be re-taken from a device and its source recorded.

## Monitoring service

```bash
gp --es cmd monitor --es value on
adb shell dumpsys activity services $PKG | grep -E "isForeground|foregroundServiceType"
gp --es cmd monitor --es value off
```

Enabling heart rate starts this service, because sensing lives with the channel. The
ongoing notification is how "the sensor is running" is discoverable — but **where
`POST_NOTIFICATIONS` is denied the service still runs and the notification is simply
absent.** Nothing is silently sensing that the app did not disclose; the disclosure is
just no longer on screen, which is why the settings copy states it too. To check that
case deliberately:

```bash
adb shell pm revoke $PKG android.permission.POST_NOTIFICATIONS
gp --es cmd hr --es value on
gp --es cmd hr --es value status      # still reports state=SETTLING|MEASURING
adb shell dumpsys activity services $PKG | grep isForeground   # still true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
```

## Diagnostics

```bash
gp --es cmd clear     # empty the log before a scenario, so what follows is only yours
```

## Screens

Navigation is ordinary Android:

```bash
adb shell am start -n $PKG/io.github.andrewkomkov.greenpods.MainActivity
adb exec-out screencap -p > screen.png
```

Screenshots corroborate. The dump verifies.
