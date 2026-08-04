# Tasks: GreenPods core application

**Input**: [spec.md](./spec.md), [plan.md](./plan.md)

Ordering is by dependency. `[P]` marks tasks that touch disjoint files and can run in
parallel. Each task names the requirement it satisfies so the gate stays traceable.

## Phase 1 — Model foundations

- [x] **T001** `core/model`: add `MediaAction`, `PlaybackSource`, `TransportStatus`,
  and `GreenPodsSettings` (pure data, no Android). — FR-016, FR-028
- [x] **T002** `core/model`: extend `PodState` with `transportStatuses` so a gated
  feature can carry its reason; keep `usableFeatures`/`gatedFeatures` derivation.
  — FR-008, FR-009
- [x] **T003** [P] `core/model`: `PodFeature.displayName` + `explanation` so the UI
  never renders raw enum names. — FR-009

## Phase 2 — Pure protocol and policy components

- [x] **T004** `core/bluetooth`: `NoiseControlCommands` — listening mode, adaptive
  strength, conversational awareness, long-press cycle bitmask, HRM toggle. Rejects an
  empty cycle. — FR-012, FR-014
- [x] **T005** [P] `core/bluetooth`: `GestureDispatcher` — resolves a
  `HeadGestureEvent` against bindings, honouring `enabled` and `minimumConfidence`.
  — FR-020, FR-021
- [x] **T006** [P] `core/data`: `AutoPausePolicy` — (previous wear, next wear, settings,
  paused-by-us) → optional `MediaAction`. Never resumes what it did not pause; at most
  one action per transition. — FR-016, FR-017, FR-018, FR-019
- [x] **T007** [P] `core/data`: `LowBatteryNotifier` — one warning per downward
  threshold crossing, per address and component. — FR-024
- [x] **T008** [P] `core/data`: `DiagnosticsLog` — bounded ring of probe results and
  undecoded traffic. — FR-011

## Phase 3 — Stateful plumbing

- [x] **T009** `core/data`: `SettingsRepository` over DataStore, exposing
  `Flow<GreenPodsSettings>` and field-wise writes, bindings included. — FR-022, FR-028
- [x] **T010** `core/data`: `TransportGate` — per-address probe cache, derived transport
  set, reason strings, probe-once semantics. — FR-007, FR-008, FR-010
- [x] **T011** `core/bluetooth`: `AapSession` — connect, decode, fold events into state
  patches, expose command methods; every failure is a gate change, not an exception.
  — FR-012, FR-013, FR-015
- [x] **T012** `core/data`: `PodRepository` — injectable clock and scanner, settings-driven
  scan mode, AAP event merge, staleness ageing, RSSI ranking. — FR-004, FR-005, FR-006
- [x] **T013** `core/data`: `EarDetectionController` — subscribes to pod state, runs
  `AutoPausePolicy`, actuates through `AudioManager`. — FR-016, FR-019

## Phase 4 — UI

- [x] **T014** `core/designsystem`: locked `CapabilityChip` variant with reason;
  `BatteryRing`; motion via `GreenPodsMotion`. — FR-009
- [x] **T015** `feature/pods`: `PodsViewModel` + screen states — scanning, no
  permission, Bluetooth off, results. — FR-001, FR-030
- [x] **T016** [P] `feature/controls`: `ControlsViewModel` + gated banner, mode chips,
  adaptive slider, CA toggle, long-press cycle picker. — FR-012, FR-015
- [x] **T017** [P] `feature/settings`: `SettingsViewModel` + auto-pause, threshold,
  monitoring, gesture bindings, diagnostics, update check. — FR-023, FR-028, FR-029
- [x] **T018** `app`: bottom navigation over the three destinations, DI container
  extension, permission rationale flow. — FR-030
- [x] **T019** `app`: `PodMonitorService` driven by the monitoring setting, posting
  low-battery warnings, tolerating denied notification permission. — FR-023, FR-025

## Phase 5 — Tests

- [x] **T020** [P] Extend `AapProtocolTest` / `AppleBeaconDecoderTest` with malformed,
  truncated and boundary inputs. — SC-004
- [x] **T021** [P] `NoiseControlCommandsTest` — byte-pinned packets. — SC-004
- [x] **T022** [P] `AutoPausePolicyTest` — full wear-transition matrix. — SC-005
- [x] **T023** [P] `LowBatteryNotifierTest`, `DiagnosticsLogTest`,
  `GestureDispatcherTest`, `HeadPoseMapperTest`. — SC-006
- [x] **T024** `PodRepositoryTest` — accumulation, ageing, ranking, AAP merge, with an
  injected clock and fake scanner. — SC-006
- [x] **T025** `TransportGateTest` — probe-once, reason mapping, derived transports.
  — SC-006
- [x] **T026** [P] View-model tests for all three screens, gated and live. — SC-006, SC-008
- [x] **T027** `SettingsRepositoryTest` — defaults and round-trip. — SC-006
- [x] **T028** Compose UI tests: empty state, locked controls, navigation. — SC-001, SC-003

## Phase 6 — Verification

- [x] **T029** `./gradlew spotlessApply spotlessCheck lintDebug testDebugUnitTest`
  green. — SC-007
- [x] **T030** Install on Samsung SM-G780F, grant permissions, walk all three screens,
  capture screenshots, confirm no crash in logcat, run connected UI tests. — SC-001
- [x] **T031** Update `docs/protocol-research.md` and `README.md` with what the device
  run established about this stack. — Constitution VII (project memory)

## Phase 7 — Verified against real hardware (Pixel 8, Android 17, AirPods Pro 3)

- [x] **T032** `BondedPodResolver`: correlate a rotating advertisement address with the
  paired classic address. Without it the AAP probe could never succeed on any phone.
  — FR-007
- [x] **T033** `ProbeOutcome`: keep "could not try" distinct from "tried and refused",
  and report ambiguity rather than guessing between paired accessories. — FR-007, FR-010
- [x] **T034** `AapAvailability.ChannelNotEstablished`: name what a stock stack actually
  does, and report which socket API got that far. — FR-010
- [x] **T035** Auto-pause ownership survives a lagging `AudioManager.isMusicActive`,
  so auto-resume stops silently failing. — FR-016, FR-017
- [x] **T036** Case battery explains its own absence instead of showing a bare dash.
  — FR-003
- [x] **T037** adb command surface (`dump`, `probe`, `set`, `inject`, `monitor`,
  `clear`), debug-only, documented in `docs/adb.md`. — Constitution VI
- [x] **T038** `SyntheticBeacon` + `StateDump`, unit-tested, so injection and inspection
  are themselves trustworthy. — Constitution VI
- [x] **T039** Pin espresso 3.7.0: the version Compose pulls transitively reflects on
  `InputManager.getInstance`, removed in Android 17, and fails every UI test on API 37.
- [x] **T040** Field notes for both devices in `docs/protocol-research.md`, including
  the heart-rate workout gate. — Constitution V
