# Handoff — 003-heart-rate

Branch `feat/heart-rate`, two commits on top of `main`. `./gradlew spotlessCheck
lintDebug testDebugUnitTest` is green. Nothing is pushed.

Progress is tracked by the checkboxes in `specs/003-heart-rate/tasks.md` — that file is
the source of truth, not this one. 63 of 84 done.

## What is built

Phases 1–7 of the plan, off-device:

- `0x17` is dispatched on protobuf content, not packet length, and `AapTransport`
  reassembles frames against the declared 16-bit length. Both were pre-existing defects.
- `HidDescriptorParser` / `HidReportDescriptor` / `HeartRateReportDecoder`, all pure and
  pinned against the 2026-08-04 capture in `core/bluetooth/src/test/resources/aap/`.
- `HeartRateState` — only `Measuring` carries a number — plus `HeartRateConfidencePolicy`
  and `HeartRateController`.
- `HealthConnectLink` + `HeartRateBatcher`, behind a `HealthStoreClient` interface so the
  rules are testable without a provider.
- Settings UI, pods card, `hr` and `health` adb commands, all documented in `docs/adb.md`.

## What is left

Four are ordinary work and can be done in any session:

- **T054** — `SettingsViewModelTest` cases for health availability and permission,
  including "denied and not re-asked".
- **T061** — move/duplicate the no-sensor and locked cases into `PodStateTest`; they
  currently live in `HeartRateStateTest`.
- **T063** — a `DiagnosticsLogTest` case asserting a heart-rate report body never reaches
  the log.
- **T067, T068, T075, T076** — doc sweep, `docs/protocol-research.md` update, correcting
  the now-false comments on `AapCommands.heartRateSensor` / `ControlCommand.HRM_STATE` and
  deciding the fate of the orphaned `0x30` toggle, and the clinical-vocabulary audit over
  every user-facing string this feature added.
- **T061a** — record in `spec.md` Assumptions that the GATT route ships unverified on
  hardware.

Phase 9 (**T077–T081**, the Material 3 Expressive UI *and UX* pass) has not been started.
It is deliberately last: it judges how the finished feature behaves.

## What needs the hardware

These cannot be closed from a laptop, and none of them should be marked done on the
strength of the code looking right:

- **T066, T070** — the `quickstart.md` §1–§7 walk-through on the Pixel 8 with AirPods
  Pro 3, including the privacy sweep.
- **T071** — SC-002. Ten minutes against a reference monitor, the device named.
  **Until this is done, accuracy is unclaimed, not assumed.**
- **T072** — SC-006. Battery drain, disabled versus not installed, then at two cadences.
- **T073** — re-derive the confidence threshold from those sessions and replace the
  provisional 128 in `Settings.kt`, or record why it stands.
- **T081** — the expressive pass verified on device.

A phone was attached during the last session (`38041FDJH006G1`), but no run against real
AirPods Pro 3 was performed, so **nothing in this feature has been exercised on
hardware**.

## One thing worth knowing before editing the tests

`HeartRateControllerTest` drives the controller with `testScheduler.runCurrent()`, not
`advanceUntilIdle()`. The controller runs in `backgroundScope`, and advancing virtual time
leaves background work untouched — a test written the other way sees no state at all and
reads as a broken controller.

## Restarting

```
cd ~/PycharmProjects/GreenPods
git status          # expect: on feat/heart-rate, clean
```

Then `/speckit-implement` again — it re-reads `tasks.md` and picks up at the first
unchecked task.
