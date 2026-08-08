---

description: "Task list for Head Tracking Calibration"
---

# Tasks: Head Tracking Calibration

**Input**: Design documents from `/specs/004-head-tracking-calibration/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/calibration.md](./contracts/calibration.md),
[quickstart.md](./quickstart.md)

**Tests**: Included, and not optional. FR-030 requires the plateau detection and the scale
derivation to be pure and unit-tested off-device, and the constitution requires every pure
component to carry tests and every transport-dependent behaviour to be tested in both states.

**Read [research.md](./research.md) before starting.** Phase 0 withdrew the evidence this
spec was originally written around and changed what the feature produces. In particular: on
current hardware the expected result of a run is **three refusals and a labelled capture**,
not three scales (FR-010a, SC-003a). A task that "fails" by reporting cross-coupling has
succeeded.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: Which user story the task serves (US1–US3)

## Path Conventions

Existing module layout, unchanged. Package root is
`io/github/andrewkomkov/greenpods`. Dependency direction stays one-way:
`app` → `feature/*` → `core/data` → `core/bluetooth` → `core/model`.

---

## Phase 1: Setup

**Purpose**: There is nothing to install. This feature adds no dependency, no plugin and no
build change — which is worth stating rather than leaving as an empty phase, because the
absence is a design decision from the plan and not an oversight.

- [X] T001 Confirm no new dependency is needed: the wizard consumes the existing `HeadTrackingController` stream and persists through the existing DataStore. If any task below reaches for a serialization plugin, stop — `GestureBindingCodec` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/settings/GestureBindingCodec.kt` is the precedent and its reasoning applies unchanged

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Make the mapper calibratable at all. Nothing visible changes, and afterwards a
calibration set from adb changes every angle the app reports. Plan Phase A.

**⚠️ Every user story below depends on this phase.**

- [X] T002 [P] Create `HeadAxis`, `OrientationField` and `CalibrationPose` in `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/HeadCalibration.kt` — per [data-model.md](./data-model.md). `OrientationField.currentlyMappedTo(axis)` is the **single** declaration of the current `O1→YAW, O2→PITCH, O3→ROLL` assignment; no call site may repeat it, because FR-013's mismatch check compares against it
- [X] T003 [P] Add `AxisVerdict` to the same file as a sealed hierarchy — `Measured`, `Suspect`, `Mismatched`, `Inconclusive`, `CrossCoupled`, `NotHeld`, `Skipped`, `Uncalibrated`. Only `Measured` and `Suspect` carry a scale; the others must have **no scale field at all**, so FR-016 is enforced by the type rather than by a check somebody can forget
- [X] T004 Add `AxisCalibration` and `HeadCalibration` to the same file, with `AxisCalibration.appliedScale: Float?` as the **one** place that decides when a derived number may be used (FR-016, FR-022)
- [X] T005 [P] Unit-test the verdict types in `core/model/src/test/kotlin/io/github/andrewkomkov/greenpods/core/model/HeadCalibrationTest.kt` — every non-`Measured`, non-confirmed-`Suspect` verdict yields `appliedScale == null`. This is the whole of "no fiction" expressed as an assertion, so it is worth a test of its own
- [X] T006 Create `HeadCalibrationCodec` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/settings/HeadCalibrationCodec.kt` — flat human-readable rows, following `GestureBindingCodec`. A malformed row is dropped and the rest kept; **unmentioned axes are NOT filled from defaults**, unlike the gesture codec, because an absent calibration must read as absent and never as a default scale (research R-8)
- [X] T007 [P] Unit-test the codec in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/settings/HeadCalibrationCodecTest.kt` — round-trip; a malformed row drops only itself; an unknown verdict name drops its row rather than the record; **an absent axis decodes to `Uncalibrated`, never to a scale**
- [X] T008 Create `HeadCalibrationStore` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/head/HeadCalibrationStore.kt` — a small interface over a prefixed key per model, `head_calibration_${model.name}`, on the shared `GreenPodsStore` DataStore. Structure follows `DataStoreHidServiceMemory`; the key is the **model**, not the address (FR-021, and see research R-8 for why that divergence from the precedent is deliberate)
- [X] T009 Change `HeadPoseMapper` in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/HeadGestureDetector.kt` from an `object` with a `private const SCALE` into a value type constructed from a `HeadCalibration`, with an explicitly-named uncalibrated variant carrying today's constant and today's "this is an approximation" wording. **No mutable global**; the one production call site is `HeadTrackingController.kt:160`
- [X] T010 [P] Unit-test the mapper in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/HeadPoseMapperTest.kt` — the uncalibrated variant reproduces the current constant exactly, a per-axis calibration is applied per axis, and an axis with a non-`Measured` verdict falls back rather than being scaled by a neighbour
- [X] T011 Implement **descriptor precedence** (FR-011a): where `HidReportField.hasPhysicalScale` is true for an orientation field, `toPhysical` outranks any stored calibration. The machinery exists in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HidReportDescriptor.kt` and is consulted by nothing; devmotion declares no range today, which is exactly why this is cheap now and awkward later
- [X] T012 [P] Unit-test the precedence in the same test file — a declared range wins over a stored calibration, and the absence of one falls through to it. Both states, per the constitution
- [X] T013 Wire `HeadCalibrationStore` into `app/src/main/kotlin/io/github/andrewkomkov/greenpods/GreenPodsApplication.kt` by hand, as everything else in the container is
- [X] T014 Add `headCalibration` to the state dump in `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/StateDump.kt` (FR-027) — per model, per axis, with each axis's verdict and, for the connected model, whether it is being applied. **An unmeasured axis appears with `"verdict": "UNCALIBRATED"` rather than being absent**, so "never measured" and "the dump forgot it" stay distinguishable
- [X] T015 Add `cal show` and `cal clear` to `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/GreenPodsDebugReceiver.kt` (FR-023, FR-027), and document both in `docs/adb.md` in this same change (FR-029)

**Checkpoint**: A calibration written from adb changes the angles the app reports, and `cal
show` says what is stored. Nothing measures anything yet.

---

## Phase 3: User Story 1 — Derive a scale that matches a real head (Priority: P1) 🎯 MVP

**Goal**: A person holds a few named poses and the app derives, from the stream alone, a
scale per axis — or says why it could not.

**Independent test**: Run the wizard end to end against a live head-tracking session, then
confirm a deliberate 90° turn reads as roughly 90° where it did not before. With no hardware,
`cal feed` reproduces the same outcomes.

### Measuring — plan Phase B

- [X] T016 [US1] Create `PlateauDetector` in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/PlateauDetector.kt` — pure, consuming raw `HeadTrackingSample`s, reporting the longest run in which **every** field stayed within tolerance for at least the minimum duration (FR-008, FR-009). Not a refactor of `HeadGestureDetector`: that takes `HeadPose` in degrees and so sits downstream of the constant being replaced, which would make the measurement circular (research R-5)
- [X] T017 [US1] Ship the tolerance and hold duration as named constants with their provenance attached — **900 units** and **2 000 ms**, from this feature's own `checklists/requirements.md:46`, which records that plateaus in the motivating session were found at a tolerance of 900. Note in the code that it came from a capture taken through the since-fixed decoder bug, so it is a starting point and not a measurement (research R-6)
- [X] T018 [US1] Make both overridable from adb via `set`, following `hrConfidenceThreshold` — a provisional number is a setting precisely so that calibrating it stays a measurement rather than a rebuild
- [X] T019 [P] [US1] Unit-test `PlateauDetector` in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/PlateauDetectorTest.kt` — a clean hold is found; jitter wider than tolerance yields nothing; a hold shorter than the minimum yields nothing; **a pose steady in its own axis while another field drifts is NOT held**, which is the case that matters for the axis check
- [X] T020 [P] [US1] Create `HeadCalibrationFixtures` in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/HeadCalibrationFixtures.kt`, with every synthetic sequence **declared synthetic in the file** (FR-030). The real `head-tracking-varint-boundary.txt` frames stay the only capture used for anything touching decode
- [X] T021 [US1] Create `CalibrationSolver` in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/CalibrationSolver.kt` — pure, taking the neutral segment and **every** pose's segment at once, returning a verdict per axis. It sees the whole run because `CrossCoupled` is a statement about the run: one pose moving three fields is ambiguous, three poses moving the same three fields is a model failure, and a solver given one pose could not tell them apart (data-model)
- [X] T022 [US1] Derive the scale as `referenceDegrees / |poseMedian(field) − neutralMedian(field)|` — from the **difference against neutral**, never from absolute values (FR-010), because the accessory's zero is wherever the head was when the stream started
- [X] T023 [US1] Add the plausible-range check that separates `Measured` from `Suspect` (FR-018), with the underlying numbers carried on the verdict so a refusal can show its arithmetic
- [X] T024 [P] [US1] Unit-test `CalibrationSolver` in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/CalibrationSolverTest.kt` — a clean separated run yields `Measured` with the expected scale; a non-zero neutral proves the difference is used and not the absolute; an implausible ratio yields `Suspect`; one axis's result never reaches another (FR-011)

### The session — plan Phase B

- [X] T025 [US1] Add `raw: HeadTrackingSample` to `HeadTrackingController.Sample` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/head/HeadTrackingController.kt` (research R-7). One stream, two consumers: a second collector would mean two sensor sessions in the earbuds for one screen, which is what this controller's whole lifetime model exists to prevent
- [X] T026 [US1] Create `CalibrationSession` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/head/CalibrationSession.kt` — the state machine from [data-model.md](./data-model.md), consuming an injectable `Flow<HeadTrackingSample>` so the wizard can be driven without a transport. States: `Idle`, `Awaiting`, `Holding`, `Analysing`, `Reviewing`, `Stopped`
- [X] T027 [US1] Sequence the poses **neutral first**, then one per axis (FR-004). Not presentation order: every scale is a difference from the neutral hold, so a run without a usable neutral can produce nothing, and discovering that at the end wastes the wearer's time
- [X] T028 [US1] Make `finish` the **only** thing that writes. Abandoning, navigating away or losing the stream leaves the stored calibration untouched (FR-007, SC-007), so a failed re-run can never destroy a good stored result
- [X] T029 [P] [US1] Unit-test `CalibrationSession` in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/head/CalibrationSessionTest.kt` — a full run stores; an abandoned run stores nothing; a run abandoned **after** a previous good calibration leaves that one byte-identical (SC-007); the stream ending mid-run yields `Stopped` and stores nothing (FR-017)

### Driving it — plan Phase B, and the only honest way to verify the rest

- [X] T030 [US1] Add the `cal` command to `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/GreenPodsDebugReceiver.kt` per [contracts/calibration.md](./contracts/calibration.md) — `start`, `status`, `advance`, `skip`, `repeat`, `finish`, `confirm`, `abandon` (FR-025). One-to-one with the session's wearer actions; **no adb-only path into the state machine**, because a second path is a second thing to be wrong
- [X] T031 [US1] Add `cal feed --es samples "<spec>"` — the `60@0,0,0;60@6290,0,0~4000` form, injected as `AapEvent.HeadTracking` so samples walk the real pipeline (FR-026). `~n` is a **deterministic** alternating jitter, so a not-held run is reproducible rather than merely random
- [X] T032 [US1] Make `feed` address the session directly, so the calibration path is exercisable on a phone that cannot open the AAP channel at all (research R-9) — `HeadTrackingController.stream()` refuses before any sample arrives when the transport is gated. Debug build only, and documented as the one place the wizard runs without a live stream
- [X] T033 [US1] Make refusals speak: `cal start` prints the same reason the entry point shows, drawn from `HeadTrackingController.Refusal`, and an action that does not apply to the current state says so rather than doing nothing — the precedent being `set`, where a silently-ignored command was found to be indistinguishable from success
- [X] T034 [US1] Document every `cal` sub-command in `docs/adb.md` with a runnable example, in this same change (FR-029)

### The wizard — plan Phase C

- [X] T035 [US1] Create `HeadCalibrationScreen` in `feature/settings/src/main/kotlin/io/github/andrewkomkov/greenpods/feature/settings/HeadCalibrationScreen.kt` — renders the session and owns no decisions. Countdown per pose (FR-005), using `GreenPodsMotion` tokens rather than raw `tween`/`spring`
- [X] T036 [US1] Create `HeadCalibrationViewModel` in the same package, collecting the controller's stream and feeding the session
- [X] T037 [US1] State on screen that the reference angles are approximate and that accuracy depends on how closely the poses were performed (FR-019). This is the feature's accuracy floor and the spec requires it said, not implied
- [X] T038 [US1] Add the entry point to `feature/settings/src/main/kotlin/io/github/andrewkomkov/greenpods/feature/settings/HeadGestureScreen.kt`, shown **locked with its reason** when the stream cannot start (FR-002), reusing `LockedCard` — the same affordance that screen already uses for the same refusal
- [X] T039 [US1] Add the route in `app/src/main/kotlin/io/github/andrewkomkov/greenpods/ui/GreenPodsApp.kt` and wire the view model in `GreenPodsViewModels.kt`. The back affordance is **derived** from the navigation structure and needs nothing (Principle VIII), but the **title** is still a hand-written `when` chain — add the entry, or the screen ships titled "GreenPods"
- [X] T040 [P] [US1] Unit-test the view model in `feature/settings/src/test/kotlin/io/github/andrewkomkov/greenpods/feature/settings/HeadCalibrationViewModelTest.kt`, in **both** transport states — gated yields the locked entry with a reason, live yields a runnable wizard. Use `runCurrent()`, not `advanceUntilIdle()`, if the subject launches into a background scope

**Checkpoint**: User Story 1 is complete. The wizard runs from a screen and from adb, and
stores a calibration where the poses separate the fields.

---

## Phase 4: User Story 2 — Catch an axis that is not what it claims (Priority: P2)

**Goal**: Report which raw field actually responded to each pose, rather than scaling
whichever one the app currently believes in.

**Why this is larger than its priority suggests**: after research R-3 this is the part with
value beyond the app. It produces the first labelled head-tracking capture this project has
had, and on current hardware its verdict — not the scale — is the expected output of a run.

**Independent test**: Feed a pose that moves `O3` where the app expects `O2` for pitch, and
confirm the wizard names the mismatch and refuses to store a pitch scale.

- [X] T041 [US2] Add `AxisResponse` to `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/head/CalibrationSolver.kt` — every field's delta between neutral and the pose, the dominant field, and the separation ratio. `dominant` is **null** below the separation threshold, so a caller cannot accidentally use a winner that was not one (FR-014)
- [X] T042 [US2] Record the full response set for every pose, not only the largest (FR-010), and carry it on the verdicts that need it
- [X] T043 [US2] Emit `Mismatched` when the responding field is not the one `OrientationField.currentlyMappedTo` names, and **do not store a scale for that axis** (FR-013)
- [X] T044 [US2] Emit `CrossCoupled` when the same fields respond across poses so that no axis separates (FR-010a). On the evidence in `docs/protocol-research.md:520-528` this is the expected verdict on AirPods Pro 3 — build it as the ordinary path it is, not as an error case bolted on
- [X] T045 [P] [US2] Unit-test all three in `CalibrationSolverTest.kt` — a mismatch is named and stores nothing; two comparable responders yield `Inconclusive` rather than the larger; a run where every pose moves the same fields yields `CrossCoupled` for all three axes and stores nothing
- [X] T046 [US2] Extend `cal status` to print `field=` on every verdict that identified one, **including `MISMATCHED`** — that field is the evidence FR-012 exists to produce and the most interesting thing this feature prints
- [X] T047 [US2] Add `cal export` per [contracts/calibration.md](./contracts/calibration.md) (FR-028) — machine-readable, carrying `deltaUnits`, `referenceDegrees`, `spanUnits`, `holdMillis` and `samples` alongside any scale. A verdict is always present; a scale is not, and no consumer may assume one
- [X] T048 [US2] Carry **all five decoded fields** in the export — `o1`, `o2`, `o3`, `horizontalAcceleration`, `verticalAcceleration`. The accelerations are what would distinguish a head rotation from the wearer moving, and the repository currently contradicts itself about what bytes 28 and 30 are (research R-3); a run of this wizard is the cheapest evidence either way
- [X] T049 [US2] Add fixture emission (FR-030a) — a run can write a labelled sample series to `core/bluetooth/src/test/resources/aap/head-tracking-poses.txt` in the style of `hr-report-series.txt`, with a provenance header naming device, firmware, host, date, service and rate, and the standing instruction not to edit it to make a test pass
- [X] T050 [US2] Document `cal export` and the fixture emission in `docs/adb.md`, in this same change (FR-029)

**Checkpoint**: A run answers the question the motivating capture could not — which field
moves for which pose — and leaves an artefact anyone can re-run.

---

## Phase 5: User Story 3 — Refuse rather than guess (Priority: P3)

**Goal**: Every failure names itself, keeps what succeeded, and emits no number it could not
measure.

**Independent test**: Move during one pose; confirm that pose is named unusable, the others
survive, and no number appears for the failed axis.

- [X] T051 [US3] Implement per-pose `skip` and `repeat` without restarting the run (FR-006), including from adb
- [X] T052 [US3] Report a pose with no held segment as `NotHeld`, name it, and offer to repeat it (FR-015)
- [X] T053 [US3] Handle the stream stopping mid-run — say so, and store no partial run as a completed one (FR-017)
- [X] T054 [US3] Require explicit confirmation before storing a `Suspect` result, with the arithmetic visible in the refusal (FR-018). `finish` refuses; `confirm` is a separate action, in the UI and in adb alike — "this looks wrong" without the numbers is an opinion
- [X] T055 [US3] Build the final screen so that a person who reads **only** it can tell which axes were measured, which were not, and why, without opening diagnostics (SC-006)
- [X] T056 [US3] Make each pose skippable without blocking the run, leaving that axis clearly labelled uncalibrated — neck mobility varies and some people cannot reach the reference angles
- [X] T057 [P] [US3] Unit-test the refusals in `CalibrationSessionTest.kt` — a skipped axis stores nothing; a not-held pose keeps its siblings' results; finishing with one failure names the uncalibrated axis; an unconfirmed `Suspect` blocks `finish`
- [X] T058 [US3] Abandon the run when the accessory is swapped mid-wizard, rather than attributing it to whichever accessory happens to be primary at the end

**Checkpoint**: Every path that cannot measure something produces a verdict, and none produces
a value.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T059 [P] Walk sections 1–6 of [quickstart.md](./quickstart.md) — every outcome reproduced from adb with **no earbuds and no head** (SC-005). Walked 2026-08-08 on an Android 17 emulator (API 37, arm64, Pixel 8 profile); what was executed is recorded in quickstart's "What was actually run". The walk found five things the page had wrong — the bond `inject` needs, the double quoting `--es samples` needs, the `skip` a failed pose needs before any verdict prints, a `cal status` that hid the refusal it was holding, and a suspect example below the noise floor — and all five are fixed in this same change rather than written down as quirks
- [ ] T060 Walk section 7 on hardware: AirPods Pro 3, AAP channel open, a real run of the wizard. Record what the response matrix says. **If it reports cross-coupling, that is the result** — write it down and stop, rather than adjusting thresholds until numbers appear. **Open, and blocked on the accessory**: every sample this feature has seen was injected, so it has measured its own machinery and nothing else
- [ ] T061 Verify gesture thresholds still fire after a calibration is stored (FR-024). They are expressed in degrees, so calibration changes what they mean physically; that is intended, and it is also the change most likely to be experienced as "head gestures stopped working". A nod that no longer registers is a finding to record, not a bug in the wizard. **Open, and depends on T060**
- [ ] T062 Update `docs/protocol-research.md` with what the run measured and what it could not — including the response matrix, whether the cross-coupling claim reproduced, and the offsets-28/30 contradiction from research R-3 as an open question. Same change as the code that learned it, per the constitution. **Partly done, and left unticked because the missing part is the point**: the offsets-28/30 contradiction is recorded as an open question, the stale claim that this feature is unbuilt is corrected, and the emulator walk is written up for exactly what it is. The response matrix — the measurement this whole feature exists to take — arrives with T060
- [X] T063 Run `./gradlew spotlessCheck lintDebug testDebugUnitTest` and fix anything it finds

---

## Dependencies

- **Phase 2 blocks everything.** No story can be built before the mapper accepts a calibration.
- **US1 (Phase 3) blocks US2 and US3** in practice: both refine verdicts that US1's solver and
  session produce. They do not block each other.
- Within Phase 3, the order is `PlateauDetector` → `CalibrationSolver` → `CalibrationSession` →
  the `cal` command → the screen. The screen is genuinely last: by Principle VI the adb path is
  what makes the screen verifiable, not the other way round.
- T049 (fixture emission) depends on T047 (export), which depends on T041–T042.

## Parallel opportunities

- T002, T003, T005, T007 — separate files in `core/model` and `core/data`.
- T010, T012, T019, T020, T024 — every unit-test file is independent of the others.
- Within US2, T045 runs alongside T046–T048.
- T059 and T061 are independent on-device checks once the feature is built.

## Implementation strategy

**MVP is Phase 2 + Phase 3.** That delivers a wizard which runs, measures, and refuses
honestly — and which can be driven end to end from a terminal.

**But read Phase 4 before deciding what "done" means.** On the evidence in
`docs/protocol-research.md`, a real run today is expected to produce three refusals and a
labelled capture rather than three scales. That makes US2 the part with lasting value: it is
the measurement nobody has taken, and it is what would let a future model — a matrix, a
different field set, a firmware that declares its own units — be chosen on evidence rather
than on a hypothesis fitted to one unlabelled session.

Phase 2 is still built first regardless, because the storage, the fallback and the descriptor
precedence are what make **any** future model installable without another spec.
