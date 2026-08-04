# Contract: the adb surface for heart rate

Principle VI and FR-028/FR-029: the whole feature is drivable and observable from adb,
without touching the screen, and **without printing a heart rate**. Every command below
lands in `docs/adb.md` in the same change that implements it.

Setup as in `docs/adb.md`:

```bash
gp() { adb shell am broadcast --receiver-foreground -n $CMP \
       -a io.github.andrewkomkov.greenpods.DEBUG "$@" >/dev/null; }
```

## Commands

| Command | Effect |
|---|---|
| `gp --es cmd hr --es value on` | Enables the setting and starts the sensor, if a transport can carry it |
| `gp --es cmd hr --es value off` | Disables it, and sends interval 0 to the accessory |
| `gp --es cmd hr --es value status` | Prints the sensing state — see below |
| `gp --es cmd set --es key hrConfidenceThreshold --es value 100` | Calibration override (R-4) |
| `gp --es cmd set --es key hrIntervalMs --es value 2000` | Cadence, for battery measurement |
| `gp --es cmd set --es key hrHealthConnect --es value on` | The health-store integration, separately (FR-017) |
| `gp --es cmd health --es value status` | Health Connect availability and whether permission is held |
| `gp --es cmd health --es value count --el minutes 10` | **Counts** own records in a window (FR-029) |
| `gp --es cmd health --es value clear` | Deletes what GreenPods wrote, own records only |

`hr status` prints one line and no values:

```
hr: state=MEASURING enabled=true source=AAP service=0x13 interval=1000ms \
    reports=63 trusted=59 discarded=0 lastStop=none
```

`health count` prints counts, never samples:

```
health: available=true permission=granted own records in last 10m: 10 records, 59 samples
```

`59 trusted` above and `59 samples` here is SC-009, checked in two commands.

## Prohibitions

- No command prints a heart rate. Not `hr status`, not `dump`, not a diagnostic detail.
- `dump` loses its `heartRateBpm` field and gains a `heartRate` object holding the state
  and the counters from `HeartRateSensing` — state, never value.
- `health count` filters on GreenPods' own `DataOrigin`. Counting everything in the window
  would count a chest strap someone else's app is writing, and report it as ours.
- The surface exists in debug builds only, as the whole receiver already does.

## Scenarios these commands have to make possible

| Requirement | Driven by |
|---|---|
| FR-011, sensing off until enabled | `dump` on a fresh install shows `state=OFF`; no start frame in `AapTransport` logging |
| FR-014, off stops the sensor | `hr off`, then the frame log shows interval 0 and reports stop arriving |
| FR-015, stops when not worn | `inject --es wear out`, then `hr status` shows `lastStop=notWorn` |
| SC-008, locked with the right reason | On a model without the sensor: `state=UNSUPPORTED`; with the channel refused: `state=LOCKED` and the transport's reason |
| SC-009, no gaps and no duplicates | `hr status` trusted count against `health count` samples, before and after an interruption |
| FR-023 / SC-005, values in no diagnostic | `dump`, `hr status`, `health count` and the diagnostics list, grepped for a plausible BPM after a session |
