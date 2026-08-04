# Tasks: Heart rate

**Input**: [spec.md](./spec.md), [plan.md](./plan.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and not optional here. The constitution's quality gates require a unit
test for every pure component, and behaviour that depends on a transport tested in both
states. For a reverse-engineered wire format pinned to captures, the test *is* the
specification of the decoder.

**Format**: `- [ ] [ID] [P?] [Story?] Description — requirements`

`[P]` marks tasks touching disjoint files with no incomplete dependency. Every task names
the requirement it satisfies, so the gate stays traceable.

---

## Phase 1: Setup

**Purpose**: Build configuration only. Nothing here changes behaviour.

- [X] T001 Add `healthConnect = "1.1.0"` and `health-connect = { module = "androidx.health.connect:connect-client", version.ref = "healthConnect" }` to `gradle/libs.versions.toml` — R-5
- [X] T002 Add `implementation(libs.health.connect)` to `core/data/build.gradle.kts` — keep it `implementation`, not `api`: no Health Connect type may cross a module boundary — R-5, R-7, AD-11
- [X] T003 [P] Add `implementation(libs.androidx.activity.compose)` to `feature/settings/build.gradle.kts` so the permission contract — typed `ActivityResultContract<Set<String>, Set<String>>` — can be launched without linking Health Connect — FR-017, AD-11

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The state types every story reads, the two `0x17` defects heart rate walks
into, and the privacy floor. No story can start until this is done.

**⚠️ The `0x17` fixes (T011–T014) land before any heart-rate traffic is decoded.** Adding a
second consumer to a channel that is currently dispatched by packet length would bury the
existing defect rather than fix it.

### Model types

- [X] T004 [P] Create `HeartRateReading` (bpm, confidence, source, measuredAtEpochMillis, sequence) in `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/HeartRate.kt`, replacing `HeartRateSample`; reject BPM outside 25..250 at construction; `measuredAtEpochMillis` is always a resolved wall-clock time, never the raw report counter — FR-005, FR-009, AD-10
- [X] T005 [P] Create the `HeartRateState` sealed hierarchy and `HeartRateSensing` counters in `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/HeartRateState.kt`, with `Measuring` as the only variant carrying a reading — FR-006, FR-008, FR-020, FR-027, FR-028
- [X] T006 Replace `heartRate: HeartRateSample?` with `heartRate: HeartRateState` in `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/PodState.kt` (depends on T004, T005) — FR-026
- [X] T007 [P] Set `PodFeature.HEART_RATE_AAP.isImplemented = true` and rewrite its `explanation` in `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/PodModel.kt` — FR-001, FR-027
- [X] T008 [P] Add `heartRateEnabled`, `heartRateHealthConnectEnabled`, `heartRateIntervalMillis`, `heartRateConfidenceThreshold` with their defaults and `sanitised()` clamps to `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/Settings.kt` — FR-011, FR-017
- [X] T009 Add `HeartRateStateTest` in `core/model/src/test/kotlin/io/github/andrewkomkov/greenpods/core/model/HeartRateStateTest.kt` proving no state but `Measuring` can yield a number and that an implausible reading is not constructible (depends on T004–T006) — FR-006, FR-007, FR-009

### Captured fixtures

- [X] T010 [P] Add the 2026-08-04 capture as test fixtures in `core/bluetooth/src/test/resources/aap/hid-descriptors.txt`, `hr-report-descriptor.txt` and `hr-report-series.txt`, taken verbatim from [contracts/aap-hid.md](./contracts/aap-hid.md) with the source recorded in a header comment — Principle III

### The `0x17` defects

- [X] T011 Add length-aware frame reassembly (16-bit length at offset 10) and raise `READ_BUFFER_BYTES` in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapTransport.kt`, so decoders always see whole frames — R-3, AD-6
- [X] T012 Add `AapFramingTest` in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapFramingTest.kt` covering a frame split across two reads, two frames in one read, and a declared length beyond the buffer (depends on T011) — R-3
- [X] T013 Dispatch `Opcode.HEAD_TRACKING` (`0x17`) on protobuf field — 5 descriptors, 7 input reports, 12 readiness — instead of packet length, and recognise head tracking by its service id, in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapDecoder.kt` — R-3, AD-5
- [X] T014 Extend `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapProtocolTest.kt` with a case proving a descriptor frame is no longer decoded as a head-tracking sample, and confirm `HeadGestureTest` still passes (depends on T010, T013) — Principle III, Risk "0x17 dispatch touches head tracking"

### Privacy floor

- [X] T015 Remove the `heartRateBpm` field from `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/StateDump.kt` and emit a `heartRate` object carrying state name and `HeartRateSensing` counters instead — FR-023, FR-028
- [X] T016 Add a case to `app/src/test/kotlin/io/github/andrewkomkov/greenpods/debug/StateDumpTest.kt` asserting the rendered dump contains no BPM value for a pod in `Measuring` (depends on T015) — FR-023, SC-005
- [X] T017 Carry `HeartRateState` through `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/PodOverlay.kt` and `PodRepository.kt`, replacing the `HeartRateSample` overlay field, and update `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/PodOverlayTest.kt` which references the old type; `grep -rn HeartRateSample` must come back empty when done (depends on T006) — FR-026

**Checkpoint**: the state types exist, `0x17` is dispatched honestly, and no path can print a
heart rate. Stories can start.

---

## Phase 3: User Story 1 — See a heart rate you can trust (Priority: P1) 🎯 MVP

**Goal**: A trustworthy heart rate from AirPods Pro 3 over the Apple protocol, with the
settling readings never shown.

**Independent Test**: [quickstart.md](./quickstart.md) §3 — wear the buds, `gp --es cmd hr
--es value on`, confirm `SETTLING` carries no number, `MEASURING` arrives inside 30 s, and
the value tracks exertion.

### Tests for User Story 1

- [X] T018 [P] [US1] Write `HidDescriptorParserTest` in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HidDescriptorParserTest.kt`: the captured frame yields four services; **the same frame with ids renumbered still finds the heart-rate service**; the entitlement string works when the name is absent — FR-002, AD-1
- [X] T019 [P] [US1] Write `HidReportDescriptorTest` in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HidReportDescriptorTest.kt`: the captured 126-byte descriptor yields the offsets in [contracts/aap-hid.md](./contracts/aap-hid.md); a descriptor with no `0xFF15:0x0120` field yields no usable layout — R-2
- [X] T020 [P] [US1] Write `HeartRateReportDecoderTest` in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HeartRateReportDecoderTest.kt`: the captured series decodes to the documented bpm/confidence pairs; short, over-long and malformed reports are rejected; report id 2 becomes unhandled; **an anchored session maps the captured counter series to wall-clock times 1.000 s apart, and the raw counter never appears as an instant** — FR-009, FR-016, AD-10, Principle IV
- [X] T021 [P] [US1] Write `HeartRateConfidencePolicyTest` in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/heartrate/HeartRateConfidencePolicyTest.kt`: the four settling readings are untrusted, the converged ones trusted, hysteresis holds around the gate, 0 and 251 BPM are implausible — FR-006, FR-007, FR-009, SC-004

### Implementation for User Story 1

- [X] T022 [P] [US1] Implement `HidDescriptorParser` in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HidDescriptorParser.kt`: protobuf walk, property-blob key scan, `HidService` list, heart-rate service found by name or entitlement — FR-002
- [X] T023 [P] [US1] Implement `HidReportDescriptor` in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HidReportDescriptor.kt`: HID short-item walker producing report ids, ordered input fields with derived offsets, and the feature report id carrying the interval — R-2, AD-2
- [X] T024 [US1] Implement `HeartRateReportDecoder` in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HeartRateReportDecoder.kt`, resolving fields **by usage** rather than by offset; take a session anchor so the report counter becomes a wall-clock time (AD-10); return an unhandled result — never a reading — for an implausible BPM so FR-009's "surface as unhandled" has a producer (depends on T022, T023) — AD-2, FR-009, FR-016
- [X] T025 [US1] Add `HidServices`, `HidServicesReady`, `HeartRateReport` and `UnhandledHidReport` to `AapEvent` and route them in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapDecoder.kt` (depends on T013, T024) — Principle IV, R-9
- [X] T026 [P] [US1] Implement start/stop frame builders in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/HidTransport.kt` for a discovered service id and interval, and pin their bytes in `core/bluetooth/src/test/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapCommandsTest.kt` — FR-003, contracts/aap-hid.md
- [X] T027 [US1] Add `startHeartRate(serviceId, intervalMicros)` and `stopHeartRate(serviceId)` to `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapSession.kt`, waiting on `awaitReady()` before writing (depends on T026) — FR-003, FR-014
- [X] T028 [P] [US1] Implement `HeartRateConfidencePolicy` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/heartrate/HeartRateConfidencePolicy.kt` with enter/leave thresholds and the plausibility range — FR-006..FR-009, AD-4
- [X] T029 [US1] Implement `HeartRateController` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/heartrate/HeartRateController.kt`: discover the service, start at the configured cadence, establish the timestamp anchor on the session's first report, fold reports through the policy, publish `HeartRateState`, and enter `Measuring` **only from an arriving report**; re-anchor on every new session and never persist an anchor — FR-001, FR-003, FR-008, AD-3, AD-10
- [X] T030 [US1] Add the no-convergence timeout (30 s in `Settling` → `Unavailable` with a reason) to `HeartRateController` (depends on T029) — Edge case "the sensor never converges", SC-001
- [X] T031 [US1] Write `HeartRateControllerTest` in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/heartrate/HeartRateControllerTest.kt` with turbine and fakes, covering the settling series, a never-converging sensor, and **both transport states** — Quality gates, SC-004, SC-008
- [X] T032 [US1] Feed controller output into `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/PodRepository.kt` via the overlay, replacing `onHeartRate(HeartRateSample)` (depends on T017, T029) — FR-026
- [X] T033 [US1] Wire `HeartRateController` into the container in `app/src/main/kotlin/io/github/andrewkomkov/greenpods/GreenPodsApplication.kt` — AD-8
- [X] T034 [US1] Add the `hr on|off|status` command to `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/GreenPodsDebugReceiver.kt`, printing state and counters and no values, per [contracts/adb.md](./contracts/adb.md), **and document it in `docs/adb.md` in the same commit** — FR-028, SC-007, Principle VI
- [X] T035 [US1] Render the heart-rate card in `feature/pods/src/main/kotlin/io/github/andrewkomkov/greenpods/feature/pods/PodsScreen.kt` with distinct forms for measuring, settling and uncertain, replacing the current `pod.heartRate?.let` block — FR-008, FR-026
- [X] T036 [US1] Map `HeartRateState` to UI state in `feature/pods/src/main/kotlin/io/github/andrewkomkov/greenpods/feature/pods/PodsViewModel.kt` and cover it in `PodsViewModelTest.kt`: settling renders no number, uncertain withdraws the last one — FR-006, FR-008
- [X] T036a [US1] Write the heart-rate card's copy in `feature/pods/.../PodsScreen.kt` so it reads as a sensor reading and never as a medical one — no "normal", no "resting rate", no ranges, no interpretation — and add a test asserting the string resources carry no clinical vocabulary — **FR-010**

**Checkpoint**: a trustworthy heart rate on screen and over adb. This is the MVP.

---

## Phase 4: User Story 2 — Decide whether it runs, and know what it costs (Priority: P1)

**Goal**: The sensor runs only when the user asked for it, says so while it runs, and stops
in the accessory when told to or when the buds come out.

**Independent Test**: [quickstart.md](./quickstart.md) §2 and §4 — a fresh install sends no
start frame; disabling produces an interval-0 frame; taking a bud out stops the sensor and
putting it back resumes it.

- [X] T037 [P] [US2] Persist the four heart-rate settings in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/settings/SettingsRepository.kt` and extend `SettingsRepositoryTest.kt` with their defaults and round-trip — FR-011, FR-017
- [X] T038 [US2] Gate the controller on `heartRateEnabled` and on wear state in `core/data/.../heartrate/HeartRateController.kt`: sensing runs while **`anyInEar`** and stops with a recorded reason only when neither bud is worn — one bud in is a case to measure in, not a case to refuse (depends on T029, T037) — FR-011, FR-015, edge case "only one bud is worn"
- [X] T039 [US2] Resume sensing on reconnection and on the buds being worn again while the feature is still enabled, in the same controller (depends on T038) — FR-015, edge case "disconnects mid-measurement"
- [X] T040 [US2] Extend `HeartRateControllerTest.kt` with the bud-out, channel-dropped and re-worn transitions, asserting a stop command was issued and not merely a display change — FR-014, FR-015
- [X] T041 [US2] Add the enable toggle to `feature/settings/src/main/kotlin/io/github/andrewkomkov/greenpods/feature/settings/SettingsScreen.kt`, stating the accessory battery cost **before** it can be switched on — FR-012
- [X] T042 [US2] Expose the heart-rate settings in `feature/settings/src/main/kotlin/io/github/andrewkomkov/greenpods/feature/settings/SettingsViewModel.kt` and cover them in `SettingsViewModelTest.kt` — FR-011, FR-012
- [X] T043 [US2] Run the controller inside `app/src/main/kotlin/io/github/andrewkomkov/greenpods/service/PodMonitorService.kt` and reflect active sensing in the ongoing notification text; where `POST_NOTIFICATIONS` is denied the service still runs, and that limitation is recorded in `docs/adb.md` and in the settings copy rather than left as a silent hole in FR-013 — FR-013, AD-8
- [X] T044 [US2] Start the monitoring service when heart rate is enabled, from `app/src/main/kotlin/io/github/andrewkomkov/greenpods/GreenPodsApplication.kt` or the settings action. **Enabling heart rate implies background monitoring, and the toggle must say so** — the existing `backgroundMonitoringEnabled` default is off because an unasked-for service is hostile, so this is a deliberate override the user consents to, not a silent one — FR-012, FR-013
- [X] T045 [US2] Add the `hrIntervalMs`, `hrConfidenceThreshold` and `hrHealthConnect` keys to the `set` command in `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/GreenPodsDebugReceiver.kt`, and document them in `docs/adb.md` in the same commit — R-4, contracts/adb.md, Principle VI

**Checkpoint**: the sensor is under the user's control and visible while running.

---

## Phase 5: User Story 3 — Feed it to the phone's health store (Priority: P1)

**Goal**: Trusted readings appear in Health Connect, attributed to GreenPods and to the
accessory, with no duplicates.

**Independent Test**: [quickstart.md](./quickstart.md) §5 — a ten-minute session, then the
sample count read back from Health Connect equals the trusted count, including after an
interruption inside a minute.

### Tests for User Story 3

- [X] T046 [P] [US3] Write `HeartRateBatcherTest` in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/health/HeartRateBatcherTest.kt`: window alignment to the wall clock; identical `clientRecordId` across an interruption inside one window; the version rises on every flush **including two flushes carrying the same sample count**; a partial window is emitted on stop rather than discarded — FR-019, SC-009
- [X] T047 [P] [US3] Write `HealthConnectLinkTest` in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/health/HealthConnectLinkTest.kt` against a fake client: each SDK status maps to its sentence; no permission means no write is attempted — FR-018, FR-020

### Implementation for User Story 3

- [X] T048 [P] [US3] Declare `WRITE_HEART_RATE`, `READ_HEART_RATE` and the `com.google.android.apps.healthdata` `<queries>` entry in `app/src/main/AndroidManifest.xml` — FR-016, FR-029
- [X] T049 [P] [US3] Add `HealthRationaleActivity` in `app/src/main/kotlin/io/github/andrewkomkov/greenpods/health/HealthRationaleActivity.kt` plus the `ACTION_SHOW_PERMISSIONS_RATIONALE` intent filter and the `ViewPermissionUsageActivity` alias in `app/src/main/AndroidManifest.xml` — FR-022
- [X] T050 [US3] Implement `HeartRateBatcher` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/health/HeartRateBatcher.kt`: aligned 60-second windows, `greenpods:hr:<windowStartEpochSeconds>`, version from a monotonic flush counter, flush on boundary and on stop (depends on T046) — FR-019, AD-7
- [X] T051 [US3] Implement `HealthConnectLink` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/health/HealthConnectLink.kt`: availability, granted-permission read, batched insert with `Device(TYPE_HEAD_MOUNTED, "Apple", model)`, own-origin count, own-record delete, and the permission request exposed as `ActivityResultContract<Set<String>, Set<String>>` so no Health Connect type leaves the module (depends on T047, T050) — FR-016, FR-020, FR-021, FR-025, FR-029, AD-11
- [X] T052 [US3] Feed **only** readings taken from `HeartRateState.Measuring` into the link from `core/data/.../heartrate/HeartRateController.kt`, and stop writing the moment permission is lost (depends on T029, T051) — FR-007, FR-017, FR-018, SC-004
- [X] T053 [US3] Add the Health Connect section to `feature/settings/src/main/kotlin/io/github/andrewkomkov/greenpods/feature/settings/SettingsScreen.kt`: its own toggle, the permission launcher driven by the contract from T051, and the three availability states each with their reason. The module must compile without a Health Connect import — FR-017, FR-020, AD-11
- [X] T054 [US3] Surface health-store availability and permission state in `feature/settings/.../SettingsViewModel.kt` with cases in `SettingsViewModelTest.kt`, including "denied and not re-asked" — FR-018, FR-020
- [X] T055 [US3] Add the `health status|count|clear` command to `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/GreenPodsDebugReceiver.kt`, counting own records only and printing counts, never samples, and document it in `docs/adb.md` in the same commit — FR-029, SC-009, Principle VI
- [X] T056 [US3] Wire `HealthConnectLink` into `app/src/main/kotlin/io/github/andrewkomkov/greenpods/GreenPodsApplication.kt` — R-7

**Checkpoint**: readings reach the health store, and the count can be checked from adb.

---

## Phase 6: User Story 4 — Powerbeats Pro 2 owners get the same screen (Priority: P2)

**Goal**: The standard-profile route reaches the same screen through the same state type,
without borrowing anything from the AAP path.

**⚠️ No Powerbeats Pro 2 hardware is available to this project.** This story is verifiable
off-device only, against a fake GATT source. It ships as *implemented and unverified on
hardware*, and the spec says so — SC-003 is not claimed for this route. That is a reason to
be careful about what is asserted, not a reason to skip the work: `HeartRateGattSource`
already exists, is tested, and currently has no caller at all.

**Independent Test**: A fake `HeartRateGattSource` drives the controller to `Measuring`
with `source = GATT`, the AAP path is demonstrably not involved, and the card renders
identically to the AAP case.

- [X] T057 [P] [US4] Return `HeartRateReading(source = GATT, confidence = null)` from `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/gatt/HeartRateGattSource.kt` and update its parser test for the new type — FR-005, R-10
- [X] T058 [US4] Add the GATT branch to `core/data/.../heartrate/HeartRateController.kt`: collect `HeartRateGattSource` when the model has `HEART_RATE_GATT`, gate on plausibility alone, and never fall back between routes (depends on T029, T057) — FR-004, R-10
- [X] T059 [US4] Extend `HeartRateControllerTest.kt` with a case asserting the two routes never substitute for one another and that the source is recorded on every reading — FR-004, FR-005
- [X] T060 [US4] Present the GATT route identically in `feature/pods/.../PodsScreen.kt`, labelling which route produced the number rather than hiding the difference — FR-026, R-10
- [X] T061 [US4] Cover the no-sensor model in `core/model/src/test/kotlin/io/github/andrewkomkov/greenpods/core/model/PodStateTest.kt`: `Unsupported` with a model reason, distinct from `Locked` with a transport reason — FR-027, SC-008
- [X] T061a [US4] Record in `specs/003-heart-rate/spec.md` Assumptions and in the PR description that the GATT route is unverified on hardware, naming what was tested instead — Principle V, "an absence must not be recorded as an impossibility", and neither may an untested path be recorded as verified

**Checkpoint**: both routes, one screen, no blending.

---

## Phase 7: User Story 5 — Health data that stays yours (Priority: P2)

**Goal**: No heart rate in any diagnostic or export path, and the user can delete what the
app holds.

**Independent Test**: [quickstart.md](./quickstart.md) §6 — after a measuring session, every
diagnostic and dump path grepped for a plausible BPM comes back empty.

- [X] T062 [P] [US5] Record unknown HID traffic **and discarded implausible readings** by shape only — service id, report id, length, and the `discardedImplausible` counter — in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/diagnostics/DiagnosticsLog.kt` and the `AapEvent` handling in `PodRepository.kt` — FR-009, FR-023, Principle IV, R-9
- [X] T063 [US5] Add a case to `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/diagnostics/DiagnosticsLogTest.kt` asserting a heart-rate report body never reaches the log (depends on T062) — FR-023, SC-005
- [X] T064 [US5] Exclude heart-rate report bodies from the hex frame log in `core/bluetooth/src/main/kotlin/io/github/andrewkomkov/greenpods/core/bluetooth/aap/AapTransport.kt`, and document what that log still carries in `docs/adb.md` — FR-023, R-9
- [X] T065 [US5] Add "delete what GreenPods holds" to `feature/settings/.../SettingsScreen.kt`, with the plain sentence that data already in the health store is managed there — FR-024, FR-025
- [ ] T066 [US5] Run the [quickstart.md](./quickstart.md) §6 sweep on device after a measuring session, including a check that no reading crosses a network boundary (the only outbound path is `UpdateChecker`), and record the result in the PR description — FR-023, FR-024, SC-005

**Checkpoint**: the feature is honest about what it keeps and where.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T067 [P] Sweep `docs/adb.md` for consistency once every command has landed — each was documented in its own commit (T034, T043, T045, T055), so this is a coherence pass, not the first write — Principle VI
- [X] T068 [P] Fold what implementation taught into `docs/protocol-research.md` — anything the descriptor walker or the report decoder discovered that the 2026-08-04 entry does not already say, including the finding that the report timestamp is an accessory-local counter rather than an epoch — Governance, R-4a
- [X] T075 [P] Correct the doc comments that this feature makes false — `AapCommands.heartRateSensor`, `ControlCommand.HRM_STATE` and `HeartRateGattSource` all still state that the AAP measurement frame has never been decoded — and decide the fate of the now-orphaned `0x30` toggle, which the plan never writes: keep it with an accurate comment, or remove it and its pinned test — Principle V
- [X] T076 Audit every user-facing string added by this feature — pods card, settings copy, notification text, `HealthRationaleActivity` — for clinical framing, and confirm none of it interprets a heart rate or implies medical use — **FR-010**
- [X] T069 Run `./gradlew spotlessApply` then `./gradlew spotlessCheck lintDebug testDebugUnitTest` and fix what they report — Quality gates
- [X] T070 Walk [quickstart.md](./quickstart.md) §1-§7 on the Pixel 8 with AirPods Pro 3 — **§1-§3 pass, 2026-08-04.** Off-device suite green; nothing starts unasked (zero tx frames in 60 s); and with the channel held open across a reconnection the accessory announces its services, `0x13` is discovered from that announcement rather than assumed, reports arrive at 1 Hz, and a heart rate reaches the screen — confirmed on the device by the project owner. §4-§7 were not walked: the hardware became unavailable, and the two defects found in §3 (see T085-T087) had consumed the session. Stop-frame correctness, report-body exclusion from the log and the discovery path are each verified in the earlier runs recorded in `docs/protocol-research.md` — SC-001, SC-007, SC-008

### Found on hardware, 2026-08-04

- [X] T082a Stop a blocking health-store call from wedging the whole feature: `HeartRateController.stop()` awaited the trusted-reading sink before publishing, and on device that call did not return — stop frame sent, state never published, no later input processed, heart rate unrecoverable without an app restart. Sink work is now queued to its own consumer and never awaited by the state machine — FR-014, AD-3, and `HealthConnectLink`'s own "output, not dependency" rule
- [X] T082 **Find out what makes the accessory announce its HID services.** Answered 2026-08-04: nothing is sent — the announcement is tied to the **ACL link**, not the L2CAP channel. Open the channel as the buds reconnect and five `0x17` frames arrive unprompted (descriptors for `0x10`–`0x13`, two readiness frames, one field 9); open it mid-link and none ever will. See `docs/protocol-research.md`
- [X] T085 **Hold the AAP channel across reconnections instead of opening it on demand.** `AapControlGateway.connect()` is called lazily by the first write, which for heart rate is always *after* the link came up — so the descriptors have already been missed and the controller waits in `STARTING` for ever. The channel has to be open when the accessory reconnects — R-1, FR-002, T082
- [X] T086 **Key sessions and overlays by something that survives address rotation.** After a reconnect the pod advertised `5D:ED:95:CC:44:42` while AAP events arrived under `73:C3:9E:7B:98:F5`: the transport was recorded against a dead key, so heart rate read `LOCKED` with `reports=0` while sixteen input reports were arriving at the transport. `BondedPodResolver` already maps advertisement → bonded address; that resolved address is the identity everything downstream should use — FR-026, SC-008
- [X] T087 **Pin the live descriptor capture, and correct what it disproves.** The hand-transcribed fixture and the accessory disagree: the announcement arrives in **two** frames (`0x10` alone, then `0x11`/`0x12`/`0x13`), and the heart-rate service's name key is `HeartRateService` rather than the `AccessoryService` the other three use — so its display name reads as absent. Detection is unaffected: `isHeartRate` matches on the name key or the `com.apple.hid.heartrate-access` entitlement, and both are present. `HidDescriptorLiveCaptureTest` pins all of it against `hid-descriptors-live.txt`, including renumbering. The earlier "parser mis-reads the frame" reading was wrong — only the label is missing — FR-002, AD-1, Principle III
- [X] T088 Decode `0x17` field 9. It carries the service id of a started service (`4A 02 08 13`) and currently falls through to `AapEvent.Unknown` — harmless but it is the accessory confirming a start, which is exactly the acknowledgement FR-003 would want — Principle IV
- [X] T083 Decide what the UI says while the service is undiscovered. Today it is `STARTING` indefinitely, which reads as a hang; the no-convergence timeout does not cover it because no report ever arrives. Either extend that timeout to the discovery wait or give discovery its own state with its own sentence — Edge case "the sensor never converges", Principle II
- [X] T084 Detect and explain a second AAP client. Another app holding PSM `0x1001` produces a socket that connects, accepts writes, and then EOFs with no `IOException` and no diagnostic — indistinguishable inside the app from an accessory with nothing to say. A silent channel that never delivers a frame should say so rather than look like a working one — Principle II, and the field note in `docs/protocol-research.md`
- [ ] T071 Compare ten minutes of readings at rest against a reference heart-rate monitor, name the reference device, and record the comparison in `docs/protocol-research.md` — SC-002. **Until this is done, accuracy is unclaimed, not assumed.**
- [ ] T072 Measure accessory battery drain over an hour with the feature disabled against the app not installed, and at two cadences with it enabled; record both in `docs/protocol-research.md` — SC-006, FR-012
- [ ] T073 Re-derive the confidence threshold from the sessions run in T070–T072 and replace the provisional 128 in `core/model/.../Settings.kt` with a measured value, or record why it stands — R-4, spec assumption
- [ ] T074 Update `specs/003-heart-rate/spec.md` status and tick `specs/003-heart-rate/checklists/requirements.md` — Governance

---

## Phase 9: Material 3 Expressive — a full UI **and UX** pass

**Purpose**: Bring the whole app, not only the heart-rate screens, to Material 3
Expressive — in behaviour first and appearance second. This runs last on purpose: it is the
pass that judges what the feature *feels* like, and that cannot be judged from a screen
that does not work yet.

**The bar**: a stranger given the phone with the buds in should understand, without being
told, that the app is measuring, that the number is not yet trustworthy, and that they may
switch it off. If any of those needs a sentence of explanation, the UX has not passed.

**Constraint**: material3 1.4.0 keeps `MotionScheme` internal, so springs stay in
`GreenPodsMotion` — never raw `tween`/`spring` literals. Where 1.5.0 would do it better,
record the swap in a comment rather than reaching for a pre-release.

- [ ] T077 Audit every screen against the Material 3 Expressive guidance and fix what falls short, in `feature/pods/`, `feature/controls/`, `feature/settings/` and `core/designsystem/`: shape and corner language, the expressive type scale and emphasis, tonal and container colour roles, spacing rhythm, and component choice — replacing anything that is Material 3 baseline where an expressive equivalent exists in 1.4.0
- [ ] T078 Make motion carry meaning, not decoration, in `core/designsystem/src/main/kotlin/io/github/andrewkomkov/greenpods/core/designsystem/theme/GreenPodsMotion.kt` and its consumers: the heart-rate card **breathes with the actual reading** rather than on a fixed loop; settling → measuring is a transition the eye follows rather than a value swap; uncertain visibly withdraws the number instead of blanking it; battery rings, capability chips and locked states share one spring vocabulary. Every animation answers "what changed and why"
- [ ] T079 Rework the flows, not the pixels — the part that matters most: first-run enable (what it costs, stated before the switch moves), the settle wait (a wait with a visible reason beats a spinner), permission requests that ask once and explain first, the locked and unsupported states reading as facts about the hardware rather than as failures, and off being one obvious gesture from anywhere it is running. Walk each flow start to finish on the device and fix what makes you hesitate
- [ ] T080 [P] Accessibility as part of expressive, not after it: TalkBack reads the heart-rate card as a state and not as a bare number, `SETTLING` is announced as measuring-in-progress, dynamic type to the largest setting leaves no clipped or overlapped text, contrast holds in light and dark, and no state is distinguished by colour alone — `feature/*` and `core/designsystem/`
- [ ] T081 Verify the pass on the Pixel 8 with the buds in — record a short screen capture of enable → settle → measure → uncertain → off, and check the same flows with dark theme, largest font size and TalkBack on. Screenshots corroborate; the walk-through is what decides

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: needs Setup only for T002/T003; the model and `0x17` work can
  start immediately. **Blocks every story.**
- **US1 (Phase 3)**: after Foundational. Nothing else depends on it being finished, but it
  is the MVP.
- **US2 (Phase 4)**: after Foundational; T038–T040 extend the controller built in T029, so
  in practice it follows US1.
- **US3 (Phase 5)**: after Foundational. T052 needs the controller from US1; T046–T051,
  T048, T049 do not and can be built in parallel with US1.
- **US4 (Phase 6)**: after Foundational; T058 extends the US1 controller.
- **US5 (Phase 7)**: T062–T064 depend on Foundational only. T066 is a verification pass and
  needs US1 working.
- **Polish (Phase 8)**: after the stories being shipped are complete.
- **Expressive pass (Phase 9)**: last. It judges how the finished feature behaves, and a
  screen that does not yet work cannot be judged. T077, T078 and T080 touch the whole app,
  so they also want the rest of the tree stable.

### Story dependencies

The three P1 stories are one feature seen from three sides, and they share the controller.
Only US3's Health Connect half and US5's diagnostics half are genuinely independent of US1.
US4 is independent in acquisition and joins at the state type.

### Parallel opportunities

- Phase 2: T004, T005, T007, T008, T010 in parallel; then T006, T009; T011→T012 and
  T013→T014 as two independent chains; T015→T016 as a third.
- Phase 3: the four test tasks T018–T021 in parallel, then T022, T023, T026, T028 in
  parallel.
- Phase 5: T046, T047, T048, T049 in parallel with all of US1.
- Phase 8: T067 and T068 in parallel; T071 and T072 are separate device sessions.

### Parallel example: User Story 1

```bash
# The pure-component tests, written before their implementations:
Task: "HidDescriptorParserTest — renumbered ids still find the sensor"
Task: "HidReportDescriptorTest — captured descriptor yields the contract offsets"
Task: "HeartRateReportDecoderTest — the captured series, and the malformed cases"
Task: "HeartRateConfidencePolicyTest — settling untrusted, hysteresis, implausible"

# Then the implementations that satisfy them:
Task: "HidDescriptorParser in core/bluetooth/aap/HidDescriptorParser.kt"
Task: "HidReportDescriptor in core/bluetooth/aap/HidReportDescriptor.kt"
Task: "HidTransport start/stop builders in core/bluetooth/aap/HidTransport.kt"
Task: "HeartRateConfidencePolicy in core/data/heartrate/HeartRateConfidencePolicy.kt"
```

---

## Implementation Strategy

### MVP: Phases 1–3

Setup, Foundational, User Story 1. That yields a trustworthy heart rate on screen and over
adb, from a sensor whose id was discovered rather than assumed, with the settling readings
withheld. It is demonstrable on the Pixel 8 the moment T036 lands.

Stop there and validate against [quickstart.md](./quickstart.md) §3 before going further.
The confidence gate is the whole risk of this feature, and it is cheaper to be wrong about
it before readings are being written into anyone's health history.

### Incremental delivery

1. Phases 1–2 → the `0x17` defects are fixed and nothing can print a heart rate.
2. Phase 3 → MVP. A number the user can trust.
3. Phase 4 → the user owns the switch, and the app admits the battery cost.
4. Phase 5 → other apps can read it. **Do not ship this before Phase 3 is validated** — an
   ungated reading written here spreads to every app the user trusts.
5. Phase 6 → Powerbeats Pro 2 owners get the same screen.
6. Phase 7 → the privacy claims are verified rather than asserted.
7. Phase 8 → the two measurements (T071, T072) that decide whether SC-002 and SC-006 can be
   claimed at all.
8. Phase 9 → the Material 3 Expressive pass over the whole app, UX first. A correct feature
   that feels wrong is still a feature people turn off.

### Notes

- Commit per task or per logical group, Conventional Commits, no version edits by hand.
- A decoder that disagrees with a fixture is wrong. Re-capture from the device and record
  the source; never edit an expected byte sequence to make a test pass.
- Every task that changes adb behaviour updates `docs/adb.md` in the same commit.
- `Measuring` is the only state that carries a number. If a task ever needs a second one,
  that is the signal to re-read FR-006 rather than to add the field.
