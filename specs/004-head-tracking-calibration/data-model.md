# Data Model: Head Tracking Calibration

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-08-06

Everything here is a pure value type. Nothing in this document touches Android, performs
I/O, or knows what a screen is — which is what lets the whole wizard be driven from adb and
tested off-device (Principles III and VI).

## The shape of the thing

A calibration is not a number. It is a set of **verdicts**, at most three of which happen to
carry a number. That asymmetry is the model's whole point: "we measured 0.0043°/unit for
yaw" and "we could not measure yaw, because you moved" have to be equally storable, equally
displayable, and equally exportable, or Principle V degrades into a comment.

```text
HeadCalibration (one per accessory model)
└── AxisCalibration × 3          yaw, pitch, roll
    └── verdict                  Measured(scale) | Skipped | NotHeld | Inconclusive | Mismatched | Suspect(scale)
        + provenance             which pose it came from, which raw field responded, when
```

---

## `core/model` — domain types

### `HeadAxis`

The three axes of head movement, named for the movement rather than for the wire field.

| Value | The movement | Nominal reference angle |
|---|---|---|
| `YAW` | turning the head, chin toward a shoulder | 90° |
| `PITCH` | nodding, chin toward the chest | 45° |
| `ROLL` | tilting, ear toward a shoulder | 45° |

The reference angles are **nominal, not measured** — they are what the instruction asks for,
not what the neck did. FR-019 requires this to be on screen; it is recorded here so the
number's status travels with it in code as well as in prose.

### `OrientationField`

Which raw field of `HeadTrackingSample` responded — `O1`, `O2`, `O3`.

This type exists so that "the axis the app calls yaw" and "the field the app reads for yaw"
can be spoken of separately. Collapsing them is exactly the mistake the motivating capture
could not rule out, and a model that cannot express the distinction cannot report it.

The **current assignment** — `O1→YAW`, `O2→PITCH`, `O3→ROLL` — lives in one place as
`OrientationField.currentlyMappedTo(axis)`, so FR-013's mismatch check compares against a
single declared truth rather than against a convention repeated at each call site.

### `CalibrationPose`

One step of the wizard.

| Field | Type | Notes |
|---|---|---|
| `axis` | `HeadAxis` | which axis this pose exercises; `null` for the neutral pose |
| `instruction` | string key | plain language, names the movement and its reference angle (FR-003) |
| `referenceDegrees` | `Float` | nominal; `0f` for neutral |
| `holdMillis` | `Long` | how long the countdown asks for (FR-005) |

The wizard's sequence is **neutral first, then one pose per axis** (FR-004). Neutral first is
not presentation order: every scale is derived as a difference from the neutral hold (FR-010),
so a run without a usable neutral can produce no scale for any axis, and finding that out at
the end would waste the wearer's time.

### `AxisVerdict`

A sealed hierarchy, because the outcomes carry different evidence.

| Verdict | Carries | Means |
|---|---|---|
| `Measured` | `degreesPerUnit`, `field`, `deltaUnits` | a scale, from a held pose whose responding field was the expected one |
| `Suspect` | `degreesPerUnit`, `field`, `deltaUnits`, `why` | a scale outside the plausible range; storable only after explicit confirmation (FR-018) |
| `Mismatched` | `expectedField`, `respondingField`, `deltaUnits` | the pose moved a field other than the one mapped to this axis (FR-013) |
| `Inconclusive` | `contenders` | two or more fields responded comparably (FR-014) |
| `CrossCoupled` | `responses` (the full field→delta map) | this pose moved several fields together, and so did the others — a per-axis scale is not the right model for these values at all. See research R-3; on current hardware this is the *expected* verdict, not an edge case |
| `NotHeld` | `reason` | no segment stayed within tolerance for long enough (FR-015) |
| `Skipped` | — | the wearer chose to skip this pose (FR-006) |
| `Uncalibrated` | — | never attempted; the state a fresh install is in |

Only `Measured` and a *confirmed* `Suspect` carry a scale that may be applied. Everything else
falls back to the documented approximation, labelled as uncalibrated (FR-022). This is
enforced in one place — `AxisCalibration.appliedScale` — rather than at each reader, because a
rule about when a number may be used is exactly the rule that gets forgotten at the fourth
call site.

### `AxisCalibration`

`axis`, `verdict`, `fromPose`, `measuredAtEpochMillis`.

`appliedScale: Float?` — the scale if and only if the verdict permits it, `null` otherwise.

### `HeadCalibration`

`model: PodModel`, `axes: Map<HeadAxis, AxisCalibration>`, `measuredAtEpochMillis`.

Keyed by **model, not by address** (FR-021). Two AirPods Pro 3 report the same units; an
AirPods Pro 2 and a Powerbeats Pro 2 do not necessarily, and applying one's constants to the
other is precisely the fiction Principle V forbids. `PodModel` is an enum, so the key is
stable across firmware updates and re-pairings in a way an address is not.

`HeadCalibrations` wraps the whole set — `Map<PodModel, HeadCalibration>` — because that is
the unit that gets persisted and dumped.

---

## `core/bluetooth/head` — computation types

### `HeldSegment`

The output of plateau detection over one pose's samples.

| Field | Type | Notes |
|---|---|---|
| `medians` | per-field `Float` | median rather than mean: a single jitter sample must not move the answer |
| `spans` | per-field `Int` | max − min inside the segment, in raw units; the evidence for "steady" |
| `durationMillis` | `Long` | how long it actually held, which is not what the countdown asked for |
| `sampleCount` | `Int` | so a thin segment at a slow stream rate is visible rather than averaged over |

`PlateauDetector` consumes samples one at a time and reports the longest segment in which
**every** field stayed within tolerance (FR-009) — every field, not the pose's own axis,
because a pose held perfectly in yaw while the head drifts in pitch is not a held pose and
the difference matters to the axis-assignment check.

The detector never assumes the wearer obeyed the countdown (FR-008): the countdown bounds when
samples are collected, and the detector finds the plateau inside them.

### `AxisResponse`

Which field moved, and by how much, between the neutral hold and a pose's hold.

`deltas: Map<OrientationField, Int>`, `dominant: OrientationField?`, `separation: Float`.

`dominant` is `null` when `separation` — the ratio of the largest delta to the second-largest
— falls below the inconclusive threshold. Returning `null` rather than the larger of two
comparable values is FR-014 expressed as a type: the caller cannot accidentally use a winner
that was not one.

### `CalibrationSolver`

Pure. Takes the neutral segment, **every** pose's segment and each pose's nominal reference
angle; returns a verdict per axis. It sees the whole run at once rather than one pose at a
time, because `CrossCoupled` is a statement about the run — one pose moving three fields is
ambiguous, three poses each moving the same three fields is a model failure — and a solver
given one pose could not tell them apart.

It is the only place a scale is ever computed:

```text
degreesPerUnit = referenceDegrees / |poseMedian(field) − neutralMedian(field)|
```

with the field chosen by `AxisResponse.dominant`, and the result checked against the plausible
range before it is returned as `Measured` rather than `Suspect`.

---

## `core/data/head` — the wizard as a state machine

### `CalibrationSession`

Consumes a `Flow<HeadTrackingSample>` — **raw samples, not poses**. Calibration derives the
mapping from units to degrees, so it cannot consume values that have already been through it.
This is why `HeadTrackingController.Sample` grows a `raw` field: the same stream feeds both,
and forking the transport to get raw units would mean two sensor sessions in the earbuds.

State, exhaustively:

| State | Means |
|---|---|
| `Idle` | nothing running; a previously stored calibration may exist |
| `Awaiting(pose)` | showing the instruction, not yet counting |
| `Holding(pose, remainingMillis)` | counting down, collecting samples |
| `Analysing(pose)` | countdown finished, detector running over what was collected |
| `Reviewing(results)` | every pose attempted; verdicts shown, nothing stored yet |
| `Stopped(reason)` | the stream ended mid-run (FR-017) |

Transitions are driven by four inputs and nothing else: a sample, a clock tick, a wearer
action (`advance`, `skip`, `repeat`, `finish`, `abandon`, `confirmSuspect`), and the stream
ending. That closed set is what makes the adb surface a complete driver rather than a partial
one — `cal` maps one-to-one onto the wearer actions, which is how FR-025 is satisfied without
a second code path.

**Nothing is persisted before `finish`.** Abandoning, navigating away, or losing the stream
leaves the stored calibration exactly as it was (FR-007, SC-007). A failed re-run cannot
destroy a good stored result, so the session writes once, at the end, or not at all.

---

## Persistence

One preference key, `head_calibration`, holding the whole `HeadCalibrations` set encoded as a
single string by `HeadCalibrationCodec` — the pattern `GestureBindingCodec` already
establishes for `gesture_bindings` (`core/data/.../settings/GestureBindingCodec.kt`).

Chosen over a key per model per axis because the set is read and written as a whole, and over
`DataStoreHidServiceMemory`'s per-address store because that store is keyed by address and
this is keyed by model.

Decoding is **lossy-tolerant by design**: an unparseable entry yields no calibration for that
model rather than an exception, and the app falls back to the labelled approximation. A
corrupt preference is not worth an app that cannot start, and the fallback is a documented,
honest state rather than a silent one — `dump` reports it (FR-027).

## Validation rules

| Rule | Where enforced | Requirement |
|---|---|---|
| A segment counts as held only if every field stays within tolerance for at least the minimum duration | `PlateauDetector` | FR-009 |
| A scale is derived from a difference against neutral, never from absolute values | `CalibrationSolver` | FR-010 |
| One axis's result is never applied to another | `AxisCalibration` is per axis; there is no code path that copies one | FR-011 |
| No scale exists for a skipped, not-held, inconclusive or mismatched axis | `AxisVerdict` — those variants have no scale field | FR-016 |
| A suspect scale is not stored without explicit confirmation | `CalibrationSession.finish` refuses; `confirmSuspect` is a separate action | FR-018 |
| A calibration is applied only to the model it was measured on | `HeadCalibrations` is keyed by `PodModel`; the lookup takes the connected model | FR-021 |
| An uncalibrated axis falls back to the documented approximation, labelled | `HeadPoseMapper` default | FR-022 |

The first column is deliberately short on "the view model checks that": every rule above is
enforced by a type that makes the wrong state unrepresentable, or by the single function that
owns it. A rule enforced at a call site is a rule that will be missing from the next call site.
