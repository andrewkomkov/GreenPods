# Implementation Plan: Head Tracking Calibration

**Branch**: `feat/head-tracking-calibration` | **Date**: 2026-08-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-head-tracking-calibration/spec.md`

## Summary

> **Phase 0 changed this feature's shape. Read [research.md](./research.md) first.** The
> spec's original motivating evidence — 195° of pitch — was produced by a decoder bug that
> PR #6 fixed hours after the spec was written (R-1), and the same PR recorded that the three
> orientation values are **cross-coupled**, so a per-axis scale is a model the repository's own
> notes say cannot fit (R-3). `spec.md` was amended on 2026-08-06 to match; this plan describes
> the feature that finding leaves standing, which is most of it.

Build a wizard that walks the wearer through named poses, finds where the orientation stream
held steady, and produces **labelled measurements of what each pose actually moved** — the
one thing the project does not have and cannot get from a terminal, because labelling a pose
means asking the person holding it. A per-axis scale is emitted only where the poses genuinely
separate the raw fields; where they do not, the wizard says so and stores nothing.

The approach puts everything that decides anything into pure, off-device-tested components:
plateau detection and scale derivation in `core/bluetooth/head` beside the mapper and the
gesture detector they belong to, and the wizard's own state machine in `core/data/head` so
the whole run — start, advance, skip, repeat, finish, abandon — is driveable from adb with
injected samples and no earbuds. The screen renders that state machine and owns none of it.

Two things distinguish this from "pick a better constant". The wizard reports **which raw
field actually responded** to each pose, so a wrong axis assignment is reported rather than
scaled over (FR-012, FR-013) — the motivating capture cannot tell wrong-scale from
wrong-axis, and a feature that only produced numbers would bake that ambiguity in. And every
path that cannot measure something produces a *verdict*, never a value: skipped, not held,
inconclusive, mismatched and suspect are all storable outcomes, and none of them is a scale.

## Technical Context

**Language/Version**: Kotlin 2.4.10, JVM toolchain 21

**Primary Dependencies**: none new. Compose / Material 3 Expressive for the wizard screen,
the existing `HeadTrackingController` session for the sample stream, the existing DataStore
`SettingsRepository` for persistence.

**Storage**: one new preference in the existing settings DataStore — the whole set of
per-model calibrations encoded into a single string, following the `GestureBindingCodec`
pattern already used for gesture bindings.

**Testing**: JUnit + kotest assertions off-device for the plateau detector, the solver, the
codec and the wizard state machine; the adb command surface for on-device verification,
driven by injected orientation samples (FR-026) so every outcome is reproducible without
earbuds and without a head.

**Target Platform**: Android; `minSdk` 26, `compileSdk`/`targetSdk` 37. Head tracking itself
needs the AAP L2CAP channel and is already gated at runtime; calibration inherits that gate
and adds none of its own.

**Project Type**: Android application, multi-module.

**Performance Goals**: the wizard consumes the existing 20 Hz orientation stream. Plateau
detection is a bounded sliding window over that stream, so it must cost less per sample than
the gesture detector already does — no buffering of a whole session.

**Constraints**: the reference angles are nominal, not measured (spec Assumptions), which is
the feature's accuracy floor and must be stated on screen (FR-019). Calibration derives from
*differences* against the neutral pose, never from absolute values (FR-010), because the
accessory's zero is wherever the wearer's head was when the stream started.

**Scale/Scope**: four poses, three axes, one screen, one adb command with a handful of
sub-commands, one persisted preference.

## Constitution Check

*GATE: evaluated before Phase 0 and re-evaluated after Phase 1.*

| Principle | How this feature satisfies it | Verdict |
|---|---|---|
| **I. The transport gate is the law** | Calibration needs a live orientation stream, which needs `AAP_L2CAP`. The entry point reads `usableFeatures`/`gatedFeatures` (FR-002), never `PodModel.features`, and reuses `HeadTrackingController`'s existing refusals rather than inventing a second gate. A live session outranks the probe because the session *is* the stream being collected. | PASS |
| **II. Locked, not hidden** | The entry point is shown locked with its reason whenever the stream cannot start (FR-002), reusing `LockedCard`, the same affordance `HeadGestureScreen` already uses for the same refusal. | PASS |
| **III. Protocol code is pure and pinned to captures** | Plateau detection and scale derivation are pure, live in `core/bluetooth/head` beside `HeadPoseMapper`, take no Android dependency, and are unit-tested against sample sequences including the 2026-08-05 capture that motivated the feature (FR-030). | PASS |
| **IV. Unknown traffic is surfaced** | Untouched — this feature decodes no new frame. What it *does* surface is the axis-assignment evidence FR-012 produces, which is the same instinct applied to a value rather than a packet. | PASS |
| **V. No fiction** | The refusal machinery satisfies it: no scale for an axis that was skipped, not held, inconclusive or mismatched (FR-016); an implausible result shown as suspect with its numbers (FR-018); the nominal-angle limit stated on screen (FR-019); the uncalibrated fallback still saying it is an approximation (FR-022). **The gap that failed this row is closed**: FR-010/FR-011 required one independent scale per axis from values `docs/protocol-research.md:520-535` records as cross-coupled — a fitted number wearing a measurement's clothes. The amended spec measures every field's response to every pose, derives a scale only where one field dominates (FR-010a), expects the cross-coupled outcome rather than treating it as an error, and withdraws the superseded 195° evidence instead of correcting it in place. | PASS |
| **VI. Driveable and observable over adb** | The wizard is a pure state machine, so `gp --es cmd cal ...` drives every step without the screen (FR-025), synthetic samples reproduce every outcome with no accessory present (FR-026), the dump carries the stored calibration per axis and per model including why an axis is uncalibrated (FR-027), and the result exports as machine-readable text for `docs/protocol-research.md` (FR-028). `docs/adb.md` is updated in the same change (FR-029). | PASS |
| **VII. Unrooted, permissionless-by-default** | No new permission, no new platform reach. Calibration is arithmetic over a stream the app already receives. | PASS |
| **VIII. Every screen says how to leave it** | The wizard is a pushed screen, and the back affordance is already *derived* from the navigation structure (`GreenPodsApp.kt:101-115`), so it gets one by construction. The screen's **title**, however, is still a hand-written `when` chain (`GreenPodsApp.kt:116-135`) that would silently title the new screen "GreenPods"; the plan carries an explicit task for it. A mid-wizard abandon confirmation must still offer something to press. | PASS |

### The gate that failed, and how it was cleared

Principle V failed against the **spec**, not against the design: the plan already reported a
response matrix and refused to store a scale the evidence did not support, but a plan cannot
quietly deliver something its spec does not ask for. Governance is explicit — feature work
starts with the spec, and an amendment updates the spec in the same change as what
invalidated it.

**All four amendments were applied to `spec.md` on 2026-08-06**, and the row above now reads
PASS. They were:

1. **"Why This Exists"** — replace the 195° evidence table. The number was a decoder artefact
   (R-1) and citing it now would repeat the mistake the constitution's Amendment 1.1.0 was
   written about: an observation recorded as a conclusion, then treated as a bound.
2. **FR-010, FR-011** — recast from "derive one scale per axis, independently" to "measure and
   report the response of every raw field to every pose, and derive a scale for an axis only
   where one field's response dominates". The refusal is not an error path here; on current
   evidence it is the expected result.
3. **FR-030** — the 2026-08-05 capture is not in the repository and was taken through the
   fixed bug (R-4). Pin the tests to declared-synthetic fixtures plus the real
   `head-tracking-varint-boundary.txt`, and name a capture produced *by this wizard* as the
   first real fixture.
4. **Success criteria** — SC-002 and SC-003 assume a per-axis scale will be produced. They
   should be conditional on the axes separating, with the cross-coupled outcome given its own
   criterion: the wizard reports the coupling and stores nothing.

Complexity Tracking stays empty. `/speckit-tasks` may now run: without these, Phase 2 would
have generated tasks for a computation the project had already documented as invalid.

**One further carried risk, recorded rather than resolved here**: this feature makes
`HeadPoseMapper` stateful with respect to a stored calibration, and `HeadGestureDetector`'s
thresholds are expressed in degrees produced by that mapper. Calibration therefore silently
changes what every existing gesture threshold means in the real world — which is the
*intent* (FR-024), and is also the change most likely to be experienced as "gestures stopped
working". Research settles what that does to the shipped defaults.

## Project Structure

### Documentation (this feature)

```text
specs/004-head-tracking-calibration/
├── plan.md              # This file
├── spec.md              # Requirements and decisions
├── research.md          # Phase 0 — what the repo already knows, and what it does not
├── data-model.md        # Phase 1 — poses, held segments, verdicts, stored calibration
├── quickstart.md        # Phase 1 — how to verify every outcome from adb, with no hardware
├── contracts/
│   └── calibration.md   # Phase 1 — the `cal` command surface and the export format
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

```text
core/model/
└── src/main/kotlin/.../core/model/
    └── HeadCalibration.kt               # new — poses, axis verdicts, stored calibration; no Android

core/bluetooth/
├── src/main/kotlin/.../core/bluetooth/head/
│   ├── PlateauDetector.kt               # new — finds the held segment in a sample run, pure
│   ├── CalibrationSolver.kt             # new — segment + reference angle -> scale or verdict, pure
│   └── HeadGestureDetector.kt           # modified — HeadPoseMapper takes a calibration
└── src/test/kotlin/.../core/bluetooth/head/
    ├── PlateauDetectorTest.kt           # new
    ├── CalibrationSolverTest.kt         # new
    └── HeadCalibrationFixtures.kt       # new — declared-synthetic runs, plus the real
                                         #       head-tracking-varint-boundary.txt frames

core/bluetooth/src/test/resources/aap/
└── head-tracking-poses.txt              # new — the first real labelled series, produced BY
                                         #       this feature (research R-4), provenance header
                                         #       in the style of hr-report-series.txt

core/data/
├── src/main/kotlin/.../core/data/head/
│   ├── CalibrationSession.kt            # new — the wizard state machine, pure over a sample flow
│   ├── HeadCalibrationStore.kt          # new — small interface, prefixed key per PodModel
│   └── HeadTrackingController.kt        # modified — publishes the raw sample too
├── src/main/kotlin/.../core/data/settings/
│   └── HeadCalibrationCodec.kt          # new — flat row format, malformed rows dropped, no defaults fill
└── src/test/kotlin/.../core/data/
    ├── head/CalibrationSessionTest.kt   # new
    └── settings/HeadCalibrationCodecTest.kt  # new

feature/settings/
└── src/main/kotlin/.../settings/
    ├── HeadCalibrationScreen.kt         # new — renders the session; owns no decisions
    ├── HeadCalibrationViewModel.kt      # new
    └── HeadGestureScreen.kt             # modified — the entry point, locked with its reason

app/
├── src/main/kotlin/.../ui/
│   ├── GreenPodsApp.kt                  # modified — route, and the title that is still a list
│   └── GreenPodsViewModels.kt           # modified — wiring, by hand as always
├── src/main/kotlin/.../GreenPodsApplication.kt   # modified — the calibration repository
└── src/debug/kotlin/.../debug/
    ├── GreenPodsDebugReceiver.kt        # modified — the `cal` command
    └── StateDump.kt                     # modified — calibration in the dump (FR-027)

docs/adb.md                              # modified — `cal`, with runnable examples
docs/protocol-research.md                # modified — what calibration measured, and its limits
```

**Structure Decision**: the existing module layout is kept exactly, and the placement follows
the existing precedent rather than inventing a home for calibration. The domain types go in
`core/model` beside `HeadPose` and `HeadGestureBinding`. The two pure algorithms go in
`core/bluetooth/head` beside `HeadPoseMapper` and `HeadGestureDetector` — they consume raw
accessory units, which is what that package is for, and they are testable there in exactly
the way Principle III demands. The wizard's state machine goes in `core/data/head` beside the
controller whose stream it consumes, so it can be driven from `app`'s debug receiver without
the UI module. Only rendering lives in `feature/settings`. Dependency direction is unchanged.

## Phase plan

**Phase A — the mapper can be calibrated at all.** `HeadCalibration` types, the codec, the
per-model persisted keys, `HeadPoseMapper` taking a calibration with the current constant as
the explicit uncalibrated fallback, and the dump exposing what is stored. Deliverable: nothing
visible changes, and a calibration set from adb changes every angle the app reports. This is
the smallest slice that proves the wiring, and it makes the rest verifiable.

It also carries one small thing that is not calibration and belongs here anyway (research R-2):
**a descriptor that declares a physical range must outrank a stored calibration.**
`HidReportField.hasPhysicalScale` and `toPhysical` already exist and no code consults them for
orientation, so the ordering is unimplemented. The devmotion descriptor declares nothing today,
which is exactly why this is cheap to add now and awkward to retrofit the day some firmware
starts declaring something.

**Phase B — measuring (User Story 1, P1).** `PlateauDetector`, `CalibrationSolver`, the
`CalibrationSession` state machine, the `cal` command surface with synthetic sample injection,
`docs/adb.md`. Deliverable: a calibration can be measured and stored end to end, from adb,
with no screen and no earbuds. It is the MVP, and by Principle VI it is also the only form in
which the later phases can be honestly verified.

**Phase C — the wizard (User Story 1's screen).** `HeadCalibrationScreen`, its view model, the
countdown, the entry point locked with its reason, the route and its title, the nominal-angle
statement. Deliverable: a person can run it.

**Phase D — the response matrix (User Story 2, P2, and after R-3 the point of the whole
thing).** Reporting which raw fields responded to each pose and by how much, the mismatch,
inconclusive and cross-coupled verdicts, and the export that carries the evidence into
`docs/protocol-research.md`. Deliverable: the first *labelled* head-tracking capture this
project has ever had, taken through the fixed decoder. Everything currently blocking a correct
orientation model is downstream of that measurement not existing.

**Phase E — refusing well (User Story 3, P3).** Per-pose repeat and skip, the stream-stopped
path, the suspect-result confirmation, the final screen that says which axes were measured and
which were not and why (SC-006).

Phase D is separable from B and C on purpose: the response evidence is worth having even from
a run the person then abandons, and it is the part of this feature with research value beyond
the app. Given R-3, a reasonable reading of the phase order is that **D is the MVP and A–C are
its delivery mechanism** — the scale-storing path may well never fire on current hardware.
That reading is not adopted here only because the storage and fallback machinery of Phase A is
what makes any future model — a matrix, a different field set, a firmware that declares its
units — installable without another spec.

## Post-Design Constitution Re-Check

Re-evaluated after the Phase 1 artefacts were written.

**Principle V now passes.** `data-model.md` gives `AxisVerdict` a `CrossCoupled` variant
carrying the response matrix, and `CalibrationSolver` returns it wherever no field dominates —
which, on the evidence in `docs/protocol-research.md`, is what will happen for all three axes
on an AirPods Pro 3 today. Before the amendment that combination would have shipped as a broken
feature: success criteria assuming three measured scales, paired with a design whose expected
output is three refusals. SC-003a now names that outcome as a success, which is what makes the
design and the spec describe the same thing.

Everything else re-checks clean. The pure components stayed pure and grew no Android
dependency; the only new platform surface is a screen and a debug command; the adb contract in
`contracts/calibration.md` drives every state the session can reach, including the ones no
hardware currently produces; and no fixture was invented — the one real capture in the
repository is used for what it actually pins, and the synthetic ones say they are synthetic.
