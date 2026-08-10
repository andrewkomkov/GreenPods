# Feature Specification: Gravity-derived head orientation

**Feature Branch**: `006-gravity-orientation`

**Created**: 2026-08-09

**Status**: Draft — the finding is measured, the feature is not built

**Input**: The accessory sends a gravity direction vector in every motion report, at report
offsets 44/46/48, and this project has never decoded it. Measured on AirPods Pro 3 / Pixel 8,
2026-08-09. Pitch and roll follow from it as real angles with no calibration and no pose held.

## Why This Exists

Every angle GreenPods shows today is `raw × 0.0054933317`, a constant whose only justification
is that ±32767 might mean ±180°. `specs/004-head-tracking-calibration` exists to replace that
with a measurement taken from a person holding poses. It ran on 2026-08-09 and, on the way,
made itself half-unnecessary.

**The accessory has been sending gravity the whole time.** The devmotion input report is 58
bytes. Five int16 of it were decoded — `o1..o3` at 20/22/24 and two fields named accelerations
at 28/30 — and the remaining forty-odd were never looked at. Searching every int16 window
across 1268 captured reports for a stable norm found exactly one candidate:

| offsets | norm | spread | components that move |
|---|---|---|---|
| **44, 46, 48** | **1034** (≈1024 = 2¹⁰) | **0.37 %** | all three |

**What it is a direction of was settled by a movement it ignores.** Over a labelled sequence —
still, 90° yaw turn and hold, return, chin down and hold, return, ear to shoulder and hold,
return:

| segment | x (44) | y (46) | z (48) | norm | change from rest |
|---|---:|---:|---:|---:|---|
| at rest | −629 | 657 | 495 | 1036 | — |
| **90° yaw turn** | −638 | 657 | 484 | 1036 | **(−9, 0, −11)** |
| chin down | −290 | 845 | 506 | 1027 | (+339, +188, +11) |
| ear to shoulder | −447 | 364 | 860 | 1035 | (+183, −293, +364) |

Nine units of change across a 90° turn, against several hundred for each tilt — and in that
same window `o2` and `o3` moved by +12467 and −7744. One set of fields sees the turn; this one
is blind to it. That is the defining property of gravity, because rotation *about* the gravity
axis cannot change gravity's direction.

**Why this is worth a feature of its own.**

- **No calibration, no poses, no assumed reference angle.** The tilt is the angle between the
  current vector and the one recorded with the head upright. The unknown scale cancels in the
  ratio, so nothing has to be fitted and nothing has to be believed about what a unit is worth.
- **It is a measurement, not a fit.** Principle V's whole complaint about the current constant
  — that it was inferred rather than observed — does not apply to an angle between two measured
  directions.
- **It is bounded honestly.** Yaw is *not* recoverable from gravity, at any price, and saying
  so is part of shipping this rather than a caveat bolted on afterwards.

## What this does not solve

**Yaw.** Rotation about the gravity axis leaves gravity unchanged, so no processing of this
vector can recover it. Yaw stays with `specs/004-head-tracking-calibration`, or with whatever
`o1..o3` turn out to be. This is geometry, and no amount of decoding changes it.

**Which part of a tilt is pitch and which is roll.** The bud sits at an angle in the ear, so no
component of the vector is pitch or roll on its own — the upright reading (−629, 657, 495)
encodes that mounting rotation, and it differs per wearer and per insertion. Total tilt from
upright needs nothing; splitting it needs the mounting rotation, which is a two-parameter fit
from a single held pose rather than the three-pose scale hunt of feature 004.

**What `o1..o3` are.** Still open. They are not Euler angles and their norm is not constant
(3.4 % spread), so they are not a direction either. They respond to yaw, which is what makes
them worth keeping.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Angles that are measured rather than assumed (Priority: P1)

Someone using head tracking sees a pitch and a roll that correspond to their actual head,
because both are computed from the accessory's own gravity vector rather than from a constant.
Nothing is asked of them: no wizard, no poses, no countdown.

**Why this priority**: it removes the approximation from two of the three axes at zero cost to
the user, and it is the smallest slice that delivers on its own.

**Independent Test**: hold the earbud still, tilt it by a known angle against a protractor or a
phone's own inclinometer, and confirm the reported tilt matches within a few degrees. **This
needs no head and no wearer** — the bud can be rotated on a table, which makes it the first
head-tracking claim in this project that is verifiable without a neck.

**Acceptance Scenarios**:

1. **Given** a live motion stream, **When** the accessory is tilted by 45° from its rest
   attitude, **Then** the reported tilt is 45° within the stated tolerance.
2. **Given** a live motion stream, **When** the accessory is rotated about the vertical axis
   only, **Then** the reported tilt does not change, and yaw is reported as uncalibrated rather
   than as zero.
3. **Given** a model whose report carries no gravity vector, **When** orientation is read,
   **Then** the app falls back to the labelled approximation and says which it used.

### User Story 2 - Say which axes are measured and which are guessed (Priority: P1)

A person reading an angle can tell whether it came from a measurement or from the fallback
constant, without opening diagnostics.

**Why this priority**: shipping two measured axes beside one guessed one, undistinguished, would
be worse than today — it would launder the guess.

**Independent Test**: with gravity decoding live, confirm pitch and roll are shown as measured
and yaw as uncalibrated, and that `dump` says the same.

### User Story 3 - A gravity decode that refuses when it should (Priority: P2)

The vector is trusted only while it looks like gravity. A report whose norm has drifted far from
its resting magnitude — the accessory in free fall, being shaken, or a firmware that repurposed
the offsets — produces no angle rather than a wrong one.

**Independent Test**: feed a report whose norm is half the expected value and confirm no angle is
emitted and the reason is recorded.

## Requirements *(mandatory)*

- **FR-001**: The decoder MUST expose the three int16 at report offsets 44/46/48 as a named
  gravity vector, alongside the existing fields rather than replacing them.
- **FR-002**: The system MUST derive tilt as the angle between the current gravity vector and a
  recorded upright reference, and MUST NOT assume any scale for the vector's units.
- **FR-003**: The system MUST capture the upright reference explicitly and MUST NOT infer it
  from the first sample of a stream — the head is wherever it was when the stream started, which
  is the same mistake FR-010 of feature 004 exists to avoid.
- **FR-004**: The system MUST treat a report whose gravity norm departs from the resting norm by
  more than a stated fraction as unusable, and MUST emit no angle for it.
- **FR-005**: The system MUST report yaw as uncalibrated while it has no source for it, and MUST
  NOT report it as zero.
- **FR-006**: The system MUST distinguish, in what it shows and in `dump`, an angle derived from
  gravity from one produced by the labelled approximation.
- **FR-007**: The decode MUST be pure and unit-tested off-device against
  `core/bluetooth/src/test/resources/aap/devmotion-report-bodies.txt`, which is labelled by
  segment and carries the rest, yaw, pitch and roll holds.
- **FR-008**: A model whose report is too short to carry the vector, or which is not known to
  send one, MUST fall back rather than reading past the end.
- **FR-009**: Every capability here MUST be exercisable and observable from adb.

## Success Criteria *(mandatory)*

- **SC-001**: A tilt of a known angle, applied to the accessory on a table, is reported within
  5° — verifiable with no wearer.
- **SC-002**: Rotating the accessory about the vertical axis changes the reported tilt by less
  than 3°.
- **SC-003**: The captured fixture decodes to a norm within 2 % of 1034 for every sample in the
  rest, pitch and roll segments.
- **SC-004**: No screen and no dump shows a gravity-derived angle and an approximated one
  without saying which is which.

## Assumptions

- **The norm is ~1024 per g**, from a single accessory and a single session. Treated as an
  observation to check rather than a constant to rely on; FR-002 is written so the number does
  not matter.
- **Offsets 44/46/48 are stable for this model's firmware.** Not assumed for others: the report
  layout is not declared by the descriptor, so a model that has not been captured gets the
  fallback, per FR-008.
- **The mounting rotation differs per insertion**, so total tilt is the claim, and the pitch/roll
  split is deferred rather than guessed.

## Out of Scope

- **Yaw.** Geometrically impossible from this vector. Stays with feature 004.
- **Splitting tilt into pitch and roll.** Needs the mounting rotation; a separate, smaller
  feature once total tilt is shipped and trusted.
- **Replacing feature 004.** The wizard remains the instrument that would validate any decode
  here against a labelled pose, and it is the only path to yaw.
