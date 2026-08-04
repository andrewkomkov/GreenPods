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
with why, plus all settings and the diagnostics log.

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
`hrIntervalMs`, `hrConfidenceThreshold`, `hrHealthConnect`.

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

## Monitoring service

```bash
gp --es cmd monitor --es value on
adb shell dumpsys activity services $PKG | grep -E "isForeground|foregroundServiceType"
gp --es cmd monitor --es value off
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
