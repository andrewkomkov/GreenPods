# GreenPods Constitution

GreenPods brings AirPods features to non-Apple Android devices without root. Every
rule below exists because this project sits on top of a reverse-engineered protocol
that Apple never documented and that Android's Bluetooth stack partly refuses to
carry. The constitution is what keeps that situation honest instead of magical.

## Core Principles

### I. The transport gate is the law

Three independent transports exist — `BLE_ADVERTISEMENT` (always available, read-only),
`GATT` (always available, heart rate on Powerbeats Pro 2 only), and `AAP_L2CAP` (available
on current Android without root, the only one that can write).

No feature may assume a transport. Availability is probed, cached with its reason, and
exposed as data. `PodState.usableFeatures` and `PodState.gatedFeatures` are the single
source of truth for what the UI may offer; `PodModel.features` alone never is.

Failure to open the AAP channel is a **normal outcome**. It is reported as a gate, never
as a crash, an error toast, or a retry storm. This holds even though the channel now
usually opens: it depends on a hidden platform constructor and a non-SDK exemption, both
of which an Android release can move.

A **live session outranks a probe**. A probe predicts; a session is evidence. A stale
failed probe must never keep features locked while traffic is flowing.

A write is not a change. Nothing is reported as applied until the accessory confirms it —
"accepted but never echoed" is this transport's characteristic failure, and the whole
discipline above is worthless if it is mistaken for success.

### II. Locked, not hidden

A feature the hardware has but this phone cannot reach is shown in a locked state with
the reason attached. A missing control reads as a bug; a locked control explains the
platform. Users must be able to tell "my phone can't" apart from "the app is broken".

### III. Protocol code is pure and pinned to captures (NON-NEGOTIABLE)

Codecs, decoders, mappers and detectors do no I/O and take no Android dependency they
do not need. They are unit-tested off-device against byte sequences captured from real
hardware.

A decoder that disagrees with a fixture is wrong. Fixtures are only changed by
re-capturing from a device, and the source of the new capture is recorded in
`docs/protocol-research.md`.

### IV. Unknown traffic is surfaced, never dropped

Every packet that cannot be interpreted becomes `AapEvent.UnhandledControl` or
`AapEvent.Unknown` and is visible in diagnostics. That log is how new protocol
behaviour gets discovered; silently discarding it forecloses the project's own future.

### V. No fiction

Nothing is invented to fill a protocol gap. If a frame layout is not decoded — such as
the AAP heart-rate measurement — the feature is absent and documented as absent, not
approximated. Values that *are* approximations (`HeadPoseMapper.SCALE`) say so in the
code that carries them.

This cuts both ways, and the harder direction is the one that cost this project a year:
an absence must not be documented as an impossibility. "We could not open the channel"
was written down as "the stack refuses it and it needs root", and that conclusion then
bounded every decision until it was tested. Record what was observed, and keep what was
inferred from it visibly separate.

### VI. Driveable and observable over adb (NON-NEGOTIABLE)

Every feature must be exercisable, and every piece of state inspectable, from `adb`
alone — without touching the screen, and without hardware that may not be present.

This is not a debugging convenience, it is what makes the project verifiable at all.
The accessories are expensive, the interesting states are physical (a bud leaving an
ear, a case closing, a battery crossing a threshold), and the transport that matters
most is unavailable on nearly every phone. Tapping at coordinates in a screenshot is
not verification; it is guessing.

Concretely:

- A debug-build broadcast receiver exposes a stable command surface: dump the whole
  state, change any setting, force a transport probe, start or stop monitoring, and
  **inject a synthetic advertisement** so wear transitions and battery thresholds can
  be driven without the hardware.
- The dump is machine-readable, complete, and includes *why* each transport is or is
  not live — the same reasons the UI shows.
- The surface exists only in debug builds. Release builds ship no such entry point.
- `docs/adb.md` documents every command with a runnable example, and is updated in the
  same change as the command.

A feature that cannot be driven from adb is not finished.

### VII. Unrooted, permissionless-by-default

The baseline experience requires no pairing, no root, no Magisk module, no Xposed hook,
and no location permission on API 31+. Anything beyond that baseline degrades to the
baseline instead of blocking the app.

Reaching the Apple protocol needs a scoped non-SDK exemption for `android.bluetooth`.
That stays inside this principle — it is an app-local runtime flag, not a permission and
not a system modification, and every Bluetooth permission is still enforced. It is kept
narrow to two class prefixes rather than opening the framework, and features that would
need genuine root, such as anything behind vendor-identity spoofing, remain out of scope.

## Technical Constraints

- Kotlin, Compose, Material 3 Expressive. `minSdk` 26, `compileSdk`/`targetSdk` from
  `build-logic/GreenPodsConfig.kt`. L2CAP is gated at runtime (API 29), not compile time.
- Dependency direction is one-way: `app` → `feature/*` → `core/data` → `core/bluetooth`
  → `core/model`. Features never depend on each other.
- `core/model` stays free of Android and of I/O.
- Motion uses `GreenPodsMotion` tokens, not raw `tween`/`spring` literals, until
  material3 1.5.0 makes `MotionScheme` public.
- No annotation processors. Dependencies are wired by hand in `GreenPodsApplication`.

## Quality Gates

- `./gradlew spotlessCheck lintDebug testDebugUnitTest` must pass before any commit.
- Every user-visible behaviour has an adb path that exercises it, and that path is the
  one used to verify it on a device. Screenshots corroborate; they do not verify.
- Every pure component — decoder, mapper, detector, policy, repository, view model —
  carries unit tests. Android-framework-touching classes are kept thin enough that the
  logic under them is testable without a device.
- Behaviour that depends on a transport is tested in both states: transport live and
  transport gated.
- Conventional Commits drive release-please; version numbers are never edited by hand.

## Governance

This constitution supersedes convenience. Feature work starts with `/speckit-specify`,
not with editing code — most of this project's hard problems are "what is actually
possible over this transport", which is spec territory.

Amendments require updating this file and any spec it invalidates in the same change.
What is learned about the protocol is written to `docs/protocol-research.md`; that file
is the project's memory and is updated in the same commit as the code that learned it.

**Version**: 1.1.0 | **Ratified**: 2026-08-03 | **Last Amended**: 2026-08-04

Amendment 1.1.0 — the AAP channel was shown to work on unrooted Android. Principle I no
longer describes it as usually unavailable, and gains the two rules that failure taught:
a live session outranks a probe, and a write is not a change until the accessory confirms
it. Principle V gains the converse of "no fiction": an absence must not be recorded as an
impossibility. Principle VII places the non-SDK exemption inside the unrooted baseline.
