---

description: "Task list for Gravity-derived head orientation"
---

# Tasks: Gravity-derived head orientation

**Input**: [plan.md](./plan.md), [spec.md](./spec.md)

**Tests**: Included and not optional. The decode is pure, so the constitution requires it
tested off-device, and there is a real labelled capture to pin it against —
`core/bluetooth/src/test/resources/aap/devmotion-report-bodies.txt`, taken 2026-08-09 with the
segments marked `REST`, `YAW`, `PITCH` and `ROLL`.

**Read the spec's "Why This Exists" first.** This feature exists because a search of the
undecoded part of the motion report found gravity. The one thing that must not happen is a
tilt that looks measured and is not: every task below either pins a number to that capture or
says out loud which axes are guessed.

## Format: `[ID] [P?] Description`

---

## Phase A — decode and pin

- [ ] T001 [P] Add `GravityVector` to `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/HeadCalibration.kt` — three ints and a `magnitude`. No units, no degrees: the point of FR-002 is that the scale never has to be named
- [ ] T002 Add `gravity: GravityVector?` to `HeadTrackingSample` in `core/model`, nullable because a report too short to carry it must not read past its end (FR-008)
- [ ] T003 Decode offsets 44/46/48 in `AapDecoder.decodeHeadTracking`, guarded by report length. Raise `HEAD_TRACKING_MIN_REPORT_BYTES` only for the gravity read, not for the existing fields — an older or shorter report must still yield the five fields it already yields
- [ ] T004 [P] Load the labelled capture in `core/bluetooth/src/test/kotlin/.../aap/DevmotionGravityTest.kt` and assert the finding as a test: the norm holds within 2 % of 1034 across every `REST`, `PITCH` and `ROLL` row (SC-003), **and the `YAW` segment moves the vector less than either tilt segment does**. That second assertion is the whole discovery; if it ever fails, the offsets have moved
- [ ] T005 [P] Assert the negative too — a 40-byte report yields a sample with `gravity == null` and the five existing fields intact

## Phase B — tilt, and the reference

- [ ] T006 Create `GravityTilt` in `core/bluetooth/.../head/GravityTilt.kt` — pure, taking a reference vector and a current one, returning degrees. The unknown scale cancels in the dot product over the magnitudes, so nothing about units enters (FR-002)
- [ ] T007 Add the plausibility gate (FR-004): a current magnitude departing from the reference magnitude by more than a stated fraction yields no angle and a reason. State the fraction with its provenance, the way `PlateauDetector`'s tolerance does
- [ ] T008 [P] Unit-test both in `GravityTiltTest.kt` — a known rotation gives the known angle; a halved magnitude yields no angle; the reference and current being equal gives exactly zero rather than a rounding fuzz
- [ ] T009 Store the upright reference per model, reusing `HeadCalibrationStore` rather than adding a second store. It is captured deliberately and never inferred from the first sample of a stream (FR-003) — the head is wherever it was when the stream started, which is the mistake FR-010 of feature 004 exists to avoid
- [ ] T010 [P] Unit-test the store round-trip, and that an absent reference reads as absent rather than as an identity vector

## Phase C — say which is which

- [ ] T011 Give `HeadPoseMapper` a per-axis source — gravity, calibration, or the labelled approximation — and make it the single reader of "where did this number come from". `isCalibrated(axis)` already exists and is the seam
- [ ] T012 Report yaw as uncalibrated whenever nothing measures it, never as zero (FR-005). Zero is a value; absent is a fact, and they are not interchangeable
- [ ] T013 Show the source wherever an angle is shown (FR-006). A measured pitch beside a guessed yaw, undistinguished, would launder the guess — which is worse than today
- [ ] T014 [P] Unit-test the mapper in all three states: gravity present, calibration present, neither

## Phase D — adb and docs

- [ ] T015 Add the vector, the stored reference and the per-axis source to `dump` in `app/src/debug/.../StateDump.kt`
- [ ] T016 Add a `tilt` command — read the current tilt, capture a reference, clear it — and document it in `docs/adb.md` in the same change (FR-009)
- [ ] T017 Write the verification section. **It needs no wearer**: the accessory is tilted on a table against a protractor or a phone's inclinometer, which makes this the first head-tracking claim in the project that a person can check alone

## Phase E — the measurement

- [ ] T018 Verify SC-001 on hardware: tilt the accessory by a known angle on a table, confirm the reported tilt is within 5°. **Needs the accessory, not a head** — one of the two things the next hardware session is for
- [ ] T019 Verify SC-002: rotate about the vertical axis, confirm the reported tilt moves by less than 3°. Same session, same table
- [ ] T020 Record what T018 and T019 measured in `docs/protocol-research.md`, including the resting magnitude on that unit, in the same change

## Dependencies

- Phase A blocks everything. B blocks C. D documents what B and C build.
- T018–T019 need hardware and are the only tasks here that do.
- Feature 004 neither blocks this nor is blocked by it. They meet in `HeadPoseMapper` at T011,
  where 004 supplies yaw and this supplies tilt.
