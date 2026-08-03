# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

GreenPods is an Android app that brings AirPods features to non-Apple devices —
battery, ear detection, noise control, head tracking, heart rate. Kotlin, Compose,
Material 3 Expressive. **No root.**

Development is spec-driven via [spec-kit](https://github.com/github/spec-kit).
Feature work starts with `/speckit-specify`, not with editing code.

## Commands

All Gradle commands need JDK 21. If `java -version` reports anything else:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

```bash
./gradlew assembleDebug              # build the APK
./gradlew testDebugUnitTest          # all unit tests
./gradlew spotlessApply              # format (run before committing)
./gradlew spotlessCheck lintDebug    # what CI enforces
./gradlew installDebug               # install on a connected device
```

Single test class or method:

```bash
./gradlew :core:bluetooth:testDebugUnitTest --tests '*AapProtocolTest'
./gradlew :core:bluetooth:testDebugUnitTest --tests '*AapProtocolTest.battery report*'
```

Test reports land in `<module>/build/reports/tests/testDebugUnitTest/index.html`.

## Spec-kit workflow

```
/speckit-constitution   # project principles (once)
/speckit-specify        # write the spec for a feature
/speckit-clarify        # de-risk ambiguity before planning
/speckit-plan           # technical plan
/speckit-tasks          # actionable task list
/speckit-implement      # execute
```

Specs live in `.specify/`. Prefer amending a spec over ad-hoc code changes when the
behaviour itself is in question — most of this project's hard problems are
"what is actually possible over this transport", which is spec territory.

## Architecture

### The transport gate — read this before touching any feature

This is the single most important thing about the codebase. GreenPods talks to
AirPods over **three independent transports**, and what the app can do depends
entirely on which are live:

| Transport | Works unrooted | Gives |
|---|---|---|
| `BLE_ADVERTISEMENT` | Always | Battery, charging, in-ear/in-case, lid counter. Read-only. |
| `GATT` | Always | Heart rate — Powerbeats Pro 2 only. |
| `AAP_L2CAP` | **Usually not** | Everything else: noise control, gestures, CA, head tracking, rename. All writes. |

`AAP_L2CAP` needs an L2CAP channel on PSM `0x1001`. Android's public
`createInsecureL2capChannel` rejects any PSM ≥ `0x0100`, and the hidden
`createInsecureL2capSocket` gets refused by real AirPods on most stacks. It
generally requires a Magisk module or Xposed hook — which this project does not
ship, because it targets unrooted devices.

**Consequences for any change you make:**

- Never assume the AAP transport is available. Probe it (`AapTransport.probe()`)
  and branch on the result.
- Failure to open the channel is a **normal outcome**, not an error to report as a
  crash or a bug.
- `PodState.usableFeatures` / `gatedFeatures` are the source of truth for what the
  UI may offer. Derive UI state from those, never from `PodModel.features` alone.
- Gated features are *shown as locked*, not hidden. A missing button reads as a bug;
  a locked one explains itself.

`docs/protocol-research.md` records where every protocol fact came from and what is
still unknown. Update it when you learn something new — it is the project's memory.

### Module layout

```
app/                  Application, MainActivity, foreground service, DI container
core/model/           Pure domain types. No Android dependencies. No I/O.
core/bluetooth/       All three transports + protocol codecs
core/data/            Repositories, settings, GitHub update checker
core/designsystem/    M3 Expressive theme, motion tokens, shared components
feature/pods/         Status screen
feature/controls/     Noise control, adaptive audio
feature/settings/     Gesture bindings, diagnostics, updates
build-logic/          Convention plugins (included build)
```

Dependencies flow one way: `feature/*` → `core/data` → `core/bluetooth` →
`core/model`. Features never depend on each other.

### Protocol code is pure and tested off-device

`AapProtocol`, `AapDecoder`, `AppleBeaconDecoder`, `HeadGestureDetector` and
`HeadPoseMapper` do no I/O. That is deliberate: the wire format is
reverse-engineered, so the only way to have confidence in it is to pin decoders
against captured byte sequences in unit tests.

`AapProtocolTest` fixtures are **real captures** from AirPods Pro 2 firmware 7A305.
Do not "fix" a test by changing an expected byte sequence — if a decoder disagrees
with a fixture, the decoder is wrong, or the fixture needs re-capturing from a
device and the source noting.

Unknown traffic is surfaced as `AapEvent.UnhandledControl` / `AapEvent.Unknown`
rather than dropped. Keep it that way; it is how new protocol behaviour gets found.

### Heart rate has two unrelated paths

Do not merge these — they share nothing but the name:

- **Powerbeats Pro 2** broadcasts the standard Bluetooth SIG Heart Rate Profile
  (`0x180D`). Plain GATT, works unrooted. Implemented in `HeartRateGattSource`.
- **AirPods Pro 3** does not. Its HR is AAP-only. `ControlCommand.HRM_STATE` (0x30)
  toggles the sensor, but **the measurement frame layout is not publicly decoded**.
  There is no decoder and inventing one would be fiction.

### Material 3 Expressive

material3 1.4.0 keeps `MotionScheme` internal — the public expressive motion API
only lands in 1.5.0-alpha. Spring tokens are therefore defined locally in
`GreenPodsMotion`. Use those, not raw `tween`/`spring` literals, so motion stays
consistent. When 1.5.0 goes stable, that file can be swapped for
`MaterialTheme.motionScheme`; the values were chosen to match.

Compose functions are PascalCase; `.editorconfig` disables ktlint's
`function-naming` rule for `@Composable`. Don't rename them to satisfy a linter.

## Build specifics worth knowing

- **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android`
  fails the build. Convention plugins apply only the Android plugin.
- **AGP 9 defaults to `android.newDsl=true`**, under which the `android` extension
  is looked up by name and cast to the `com.android.build.api.dsl` interface —
  `extensions.configure<LibraryExtension>` does not resolve it.
- `compileSdk` is 37 and lives in `build-logic/src/main/kotlin/GreenPodsConfig.kt`,
  not in module build files.
- `minSdk` 26. The L2CAP API needs 29 and is gated at runtime, not compile time.
- Type-safe project accessors are enabled: use `projects.core.model`.

## Releases

Conventional Commits drive everything. release-please maintains a release PR from
commits on `main`; merging it tags the release, and CI builds and attaches a signed
APK. The in-app updater reads the first `.apk` asset on the latest GitHub release,
so that attachment is what makes auto-update work.

`versionName` in `app/build.gradle.kts` carries an `x-release-please-version`
marker and is rewritten automatically. `versionCode` is derived from it — don't set
either by hand.

Signing secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`) are optional; without them the release build falls back to the debug
key so forks can still cut releases.

## Scope boundaries

Out of reach for any third-party client, and not worth attempting: Find My network,
automatic device switching, "Hey Siri" invocation, audio sharing. These need Apple
account services, not protocol access.

Reachable but unimplemented, in rough order of value: the AAP heart-rate frame
format, head-tracking calibration constants (`HeadPoseMapper.SCALE` is an
approximation), and the encrypted tail of the BLE advertisement.
