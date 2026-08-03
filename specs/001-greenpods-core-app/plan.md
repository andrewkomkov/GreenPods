# Implementation Plan: GreenPods core application

**Branch**: `001-greenpods-core-app` | **Date**: 2026-08-03 | **Spec**: [spec.md](./spec.md)

## Summary

Turn the existing scaffold — decoders, a scanner, a repository and three unwired
screens — into a complete application. The work is mostly *wiring with a gate*: a
single place that knows which transports are live and why, a settings store, a session
that owns the AAP channel when it exists, and three screens driven by view models that
read both.

Nothing about the reverse-engineered protocol changes. What changes is that its results
reach the user, and that everything the user can do is derived from the transport gate.

## Technical Context

**Language**: Kotlin 2.4, coroutines/Flow
**UI**: Compose, Material 3 Expressive (`GreenPodsMotion` tokens; `MotionScheme` is
still internal in material3 1.4.0)
**Storage**: `androidx.datastore:datastore-preferences` — already in the catalog
**Network**: OkHttp, only for the GitHub release check
**Target**: `minSdk` 26 / `compileSdk` 37; L2CAP gated at runtime on API 29
**Testing**: JUnit4 + kotest assertions + turbine + coroutines-test off-device;
Compose UI tests on device
**Verification device**: Samsung SM-G780F (Galaxy S20 FE), Android 13 / API 33, no
AirPods paired

## Constitution Check

| Principle | How this plan satisfies it |
|---|---|
| I. Transport gate is law | `TransportGate` is the only source of live transports; view models take it as input and every capability is derived through `PodState.usableFeatures`. |
| II. Locked, not hidden | `CapabilityChip` gains a locked variant carrying a reason; `ControlsScreen` disables rather than removes. |
| III. Pure, pinned codecs | New pure components (`AutoPausePolicy`, `LowBatteryNotifier`, `NoiseControlCommands`, `GestureDispatcher`) live beside the codecs, take no Android types, and are unit-tested. |
| IV. Unknown traffic surfaced | `DiagnosticsLog` records probes and every `Unknown`/`UnhandledControl` event; the settings screen shows them. |
| V. No fiction | AAP heart rate stays unimplemented and is labelled as such in the UI. |
| VI. Unrooted baseline | Everything except the controls screen and gestures works with the advertisement transport alone. |

No violations. No new module is introduced; no feature module gains a dependency on
another.

## Architecture decisions

**AD-1 — One gate object, not a flag per screen.** `TransportGate` holds the probe
result per address plus the derived `Set<Transport>`, and exposes it as a `StateFlow`.
View models never call `AapTransport.probe` themselves. This is what makes FR-008 a
structural property rather than a discipline.

**AD-2 — Probe once, on demand, never in a loop.** A probe runs when the user asks for
controls for an accessory, or once per app session per address. FR-010 forbids retry
storms, and a failed probe on a stock stack is the *expected* result, so retrying is
pure battery cost.

**AD-3 — Auto-pause is a pure policy plus a thin actuator.** `AutoPausePolicy` maps
(previous wear, next wear, settings, whether we paused) to an optional `MediaAction`.
The actuator that turns that into `AudioManager.dispatchMediaKeyEvent` is ~20 lines and
untested; all the behaviour in FR-016..019 sits in the policy, where it is testable.

**AD-4 — Settings as one immutable data class.** `GreenPodsSettings` is read as a
`Flow<GreenPodsSettings>` from DataStore and written field-wise. Gesture bindings are
serialised as a compact string list rather than JSON to avoid adding a serialization
dependency for six rows of data.

**AD-5 — Command builders separated from the session.** `NoiseControlCommands` builds
byte arrays; `AapSession` writes them. That keeps FR-012's wire format under test even
though no test device can open the channel.

**AD-6 — View models own `SharingStarted.WhileSubscribed`, not the Activity.** The
current `MainActivity` builds its own `stateIn` scope; moving this into view models is
what lets the three screens exist independently and be tested.

## Component work

### `core/model`
- `MediaAction` (PAUSE / RESUME) and `PlaybackOwner` for auto-pause results.
- `TransportStatus`: per-transport availability + human-readable reason.
- Extend `PodState` with `aapAvailability` reason text; keep derivation rules intact.

### `core/bluetooth`
- `NoiseControlCommands`: pure builders for listening mode, adaptive strength,
  conversational awareness, long-press cycle bitmask, HRM toggle.
- `AapSession`: owns `AapTransport`, decodes into `AapEvent`, folds events into a
  `PodState` patch, exposes `Flow<AapEvent>` and suspend command methods.
- `HeadPoseMapper` stays; `GestureDispatcher` maps `HeadGestureEvent` + bindings to a
  `GestureAction` honouring `enabled` and `minimumConfidence`.

### `core/data`
- `SettingsRepository` over DataStore with a typed `GreenPodsSettings`.
- `TransportGate`: probe cache, derived transports, reason strings.
- `AutoPausePolicy` + `EarDetectionController` (actuator).
- `LowBatteryNotifier` policy: threshold crossings, per address+component.
- `DiagnosticsLog`: bounded ring of `DiagnosticEvent`.
- `PodRepository` gains: injectable clock, settings-driven scan mode, merge of AAP
  events into the stored `PodState`.

### `core/designsystem`
- `CapabilityChip` locked variant with reason.
- `BatteryRing` / `PodCard` visuals using `GreenPodsMotion` springs.

### `feature/*`
- `PodsViewModel`, `ControlsViewModel`, `SettingsViewModel`, each taking repositories as
  constructor parameters so they are constructible in a unit test.
- `PodsScreen`: scan state, permission-missing state, Bluetooth-off state, pod cards.
- `ControlsScreen`: gated banner with reason, mode chips, adaptive slider, CA toggle,
  long-press cycle picker.
- `SettingsScreen`: auto-pause switches, low-battery threshold, background monitoring,
  gesture bindings with confidence, diagnostics list, update check.

### `app`
- `GreenPodsNav`: bottom navigation over the three destinations.
- `GreenPodsApplication`: container gains settings, gate, diagnostics, session factory.
- `MainActivity`: permission rationale flow, edge-to-edge, theme.
- `PodMonitorService`: driven by the settings flag, posts low-battery warnings, tolerates
  a missing notification permission.

## Test strategy

| Layer | What is tested | How |
|---|---|---|
| Codecs | Existing fixtures, extended with malformed/boundary inputs | JUnit + kotest |
| Commands | Byte-for-byte packet equality | JUnit |
| Policies | Full wear-transition matrix, threshold crossings, gesture dispatch | JUnit, table-driven |
| Repository | Accumulation, staleness ageing, RSSI ranking, AAP merge | turbine + injected clock and fake scanner |
| Settings | Round-trip and defaults | DataStore in a temp dir |
| View models | State derivation under gated/live transports | turbine + fakes |
| UI | Empty state, locked controls, navigation | Compose UI test on the Samsung device |

Everything except the last row runs off-device in CI.

## Risks

- **No AirPods available.** Advertisement-driven paths cannot be exercised end-to-end on
  the device. Mitigated by driving the same pipeline from captured payloads in tests and
  by verifying on-device that scanning starts, permissions resolve and the empty state
  is correct.
- **AAP unavailable on the Samsung.** Expected; the gated path is what gets verified
  there, which is also the path most users will see.
- **DataStore in unit tests** needs a temp directory and a test scope; keep it in one
  test to avoid flakiness elsewhere.
