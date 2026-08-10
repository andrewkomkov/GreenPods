# Contract: the calibration command surface and export format

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-08-06

Two interfaces are exposed by this feature: the `cal` command on the debug broadcast
receiver, and the machine-readable export that carries a measured result into
`docs/protocol-research.md`. Both are contracts because something outside the app depends on
their shape — a script, and the project's own memory.

Everything here goes into `docs/adb.md` in the same change as the code (FR-029, Principle VI).

---

## `gp --es cmd cal`

One command, a `value` selecting the action, mirroring `hr` and `health`. The actions are the
`CalibrationSession` inputs one-to-one — there is no adb-only path into the state machine,
because a second path is a second thing to be wrong.

| Invocation | Does |
|---|---|
| `cal --es value start` | starts a run against the connected accessory; refuses with a reason if the stream cannot start |
| `cal --es value status` | prints the current state, the pose being held, and the verdicts so far |
| `cal --es value advance` | begins the hold for the pose now being shown |
| `cal --es value skip` | skips the current pose (FR-006) |
| `cal --es value repeat` | re-runs the current pose (FR-006) |
| `cal --es value finish` | stores the run; refuses while an unconfirmed suspect result is present |
| `cal --es value confirm` | confirms a suspect result so `finish` may store it (FR-018) |
| `cal --es value abandon` | ends the run and stores nothing (FR-007) |
| `cal --es value show` | prints what is currently stored, per model and per axis (FR-027) |
| `cal --es value export` | prints the stored calibration in the export format below (FR-028) |
| `cal --es value clear` | discards the stored calibration for the connected model (FR-023) |
| `cal --es value feed --es samples <spec>` | injects synthetic orientation samples (FR-026) |

### Refusals

`start` prints the same reason the entry point shows, drawn from
`HeadTrackingController.Refusal`, so adb and the screen cannot disagree:

```
cal: cannot start — UNAVAILABLE :: this phone cannot carry the commands, or the accessory
     never described itself
```

An action that does not apply to the current state says so, rather than doing nothing:

```
cal: advance ignored — state=IDLE. Start a run first.
```

An unknown value lists the known ones. Both of these follow the precedent set by `set`, where
a silently-ignored command was found to be indistinguishable from success.

### `status` output

One line of state, then one line per axis. Verdicts, never a bare number:

```
cal: state=HOLDING pose=YAW remaining=1800ms samples=44
cal: yaw    = pending
cal: pitch  = MEASURED 0.00412 deg/unit field=O2 delta=10920 units from PITCH
cal: roll   = NOT_HELD "never settled: roll span 3140 units over the whole hold"
```

`field=` is present on every verdict that identified one, including `MISMATCHED`, because that
field is the evidence FR-012 exists to produce and it is the most interesting thing this
feature prints.

### `feed` — synthetic samples

The whole of FR-026 and SC-005 rests on this: every outcome must be reproducible with no
earbuds, no wearer and no head. The sample spec is deliberately blunt — a repeat count and
three field values, semicolon-separated runs:

```bash
# 60 samples steady at neutral, then 60 steady with o2 displaced: a clean pitch measurement
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@0,10920,0"

# the same, with noise wider than the tolerance: NOT_HELD
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@0,10920,0~4000"

# two fields responding comparably: INCONCLUSIVE
gp --es cmd cal --es value feed --es samples "60@0,0,0;60@9800,10920,0"
```

`~n` adds a deterministic alternating jitter of ±n so a not-held run is reproducible rather
than merely random. Samples are injected as `AapEvent.HeadTracking` through
`PodRepository.onAapEvent`, the same entry point real frames arrive on — an injected pose walks
the identical pipeline, which is the property that makes the check worth anything.

**Injected samples do not need the transport.** `feed` addresses the session directly, so the
calibration path is verifiable on a phone that cannot open the AAP channel at all. This is the
one place the wizard is reachable without a live stream, and it exists only in the debug build.

---

## The export format (FR-028)

`cal --es value export` prints one JSON object per line, chunked by the existing `reply`
logic. It is the form in which a validated calibration is pasted into
`docs/protocol-research.md` as evidence, so it carries **what was measured and under what
conditions**, not just the constants:

```json
{
  "model": "AIRPODS_PRO_3",
  "measuredAt": "2026-08-06T14:22:31Z",
  "app": "0.5.2",
  "axes": {
    "yaw":   {"verdict": "MEASURED", "field": "O1", "degreesPerUnit": 0.01431,
              "deltaUnits": 6290, "referenceDegrees": 90.0, "holdMillis": 3040,
              "spanUnits": 210, "samples": 61},
    "pitch": {"verdict": "MISMATCHED", "expectedField": "O2", "respondingField": "O3",
              "deltaUnits": 10920, "referenceDegrees": 45.0},
    "roll":  {"verdict": "NOT_HELD", "reason": "roll span 3140 units over the whole hold"}
  }
}
```

The per-pose record that accompanies it carries **all five decoded fields**, not the three the
axes use — `o1`, `o2`, `o3`, `horizontalAcceleration`, `verticalAcceleration`. Two reasons, and
neither is completeness for its own sake: the acceleration fields are what would distinguish a
genuine head rotation from the wearer moving, and the repository currently contradicts itself
about what bytes 28 and 30 even are (research R-3 — the decoder calls them accelerations, the
research notes describe them as part of a four-int16 near-unit-norm vector). A run of this
wizard is the cheapest evidence anyone will get either way, and dropping two columns would
throw it away for nothing.

Three properties are contractual:

1. **A verdict is always present; a scale is not.** No consumer may assume `degreesPerUnit`
   exists. This is the export's whole reason for existing in this shape — a record that
   silently omitted the failed axes would read as a three-axis calibration.
2. **The evidence travels with the number.** `deltaUnits`, `referenceDegrees`, `spanUnits`,
   `holdMillis` and `samples` are what let a reader of `docs/protocol-research.md` judge the
   measurement later, or re-derive it under a different assumed reference angle. A constant
   without its provenance is exactly the thing this project promised not to record again.
3. **`referenceDegrees` is nominal and labelled as such** wherever the export is quoted. The
   number is what the wearer was asked for, never what their neck did.

---

## What the state dump adds (FR-027)

`StateDump` gains a `headCalibration` object: the stored set, per model, with each axis's
verdict and — for the connected model — whether it is currently being applied. An axis with no
calibration appears with `"verdict": "UNCALIBRATED"` rather than being absent, so "never
measured" and "the dump forgot to include it" are distinguishable.

---

## Not part of the contract

The `HeadCalibrationCodec` preference encoding is **internal**. It is read and written only by
this app, has no external consumer, and is free to change; the export above is the stable form.
Recording that here rather than leaving it implicit, because the codec's output is visible in a
DataStore file and will eventually be mistaken for an interface by somebody.
