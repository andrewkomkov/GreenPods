# Implementation Plan: Gravity-derived head orientation

**Feature**: `specs/006-gravity-orientation` | **Date**: 2026-08-09 | **Spec**: [spec.md](./spec.md)

## Summary

Decode the three int16 at devmotion report offsets 44/46/48 as a gravity direction, and report
head tilt as the angle between it and a recorded upright reference. Pitch and roll stop being
`raw × 0.0054933317`. Yaw is untouched and stays uncalibrated, loudly.

The work is small and almost entirely pure. The reason it is a feature rather than a patch is
that it changes what an angle in this app *means*, and that has to be visible.

## Technical Context

**Language**: Kotlin, existing modules, no new dependency.
**Testing**: JUnit + kotest off-device, pinned against the labelled capture.
**Constraints**: minSdk 26; the decode is pure and Android-free.

## Constitution Check

| Principle | How this plan satisfies it |
|---|---|
| I — the transport gate is law | Gravity rides the AAP motion stream, so it is gated exactly as head tracking already is. Nothing new to probe. |
| II — locked, not hidden | Where the vector is absent the axis says "approximation", never a bare number. |
| III — pure and pinned to captures | The decode is a pure function over a report; `devmotion-report-bodies.txt` is a real capture with labelled segments, taken 2026-08-09. |
| IV — unknown traffic surfaced | The other ~40 undecoded bytes stay undecoded and stay visible; this feature claims three of them and says so. |
| V — no fiction | This is the principle the feature exists to serve: an angle between two measured directions replaces a number that was inferred. Yaw, which cannot be measured this way, is reported as uncalibrated rather than approximated quietly. |
| VI — driveable over adb | The decode is observable in `dump`, and the tilt is readable without a wearer because the accessory can be tilted on a table. |

**No violations.** The one judgement call is FR-004's plausibility gate, which is a threshold
rather than a measurement; it is written as a fraction of the *observed resting norm* rather
than an absolute, so it carries no assumption about units.

## Phases

### Phase A — decode and pin

The vector, as a model type, and a pure decode tested against the capture. Nothing visible
changes. Ends with a test that reads the labelled fixture and asserts the norm holds across the
rest, pitch and roll segments, and that the yaw segment moves it by less than the tilt segments
do — which is the finding, expressed as an assertion.

### Phase B — tilt, and the reference

The upright reference has to be captured deliberately (FR-003) and stored per accessory, which
is the same shape as `HeadCalibrationStore` and should reuse it rather than grow a second store.
Tilt is then a pure function of two vectors. The plausibility gate lands here.

### Phase C — say which is which

`HeadPoseMapper` gains a source per axis — measured from gravity, calibrated from a pose, or the
labelled approximation — and every surface that shows an angle shows the source. This is the
part that keeps the feature honest and it is not optional.

### Phase D — adb and docs

`dump` carries the vector, the reference and the per-axis source. `docs/adb.md` gains the
commands. The tilt is checkable on a table, so the verification section is the first in this
project that needs no neck.

## Dependencies and ordering

Phase A blocks everything. B blocks C. D follows the command it documents, in the same change,
per FR-029 of feature 004 and the constitution's documentation rule.

**Feature 004 is not blocked by this and does not block it.** They meet only in `HeadPoseMapper`,
where 004 supplies yaw and this supplies tilt, and the merge point is the per-axis source in
Phase C.

## Risks

- **One accessory, one session.** Offsets 44/46/48 are pinned to AirPods Pro 3 firmware as
  captured. FR-008's fallback is what keeps that from becoming a wrong angle on another model.
- **The rest attitude drifts.** If the accessory's gravity estimate has its own slow drift, the
  reference goes stale and tilt creeps. Phase B should record the reference's age; a re-reference
  action is cheap and is better than a filter nobody can explain.
- **Tilt is not pitch.** Shipping "tilt" where a user expects "pitch" invites the same confusion
  the 195° caused. Phase C's naming matters more than it looks.
