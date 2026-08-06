# Implementation Plan: Live Activities

**Branch**: `feat/live-activities` | **Date**: 2026-08-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-live-activities/spec.md`

## Summary

Promote the monitoring service's existing ongoing notification into an Android *Live
Update*, so battery, wear, listening mode and any active heart-rate session are readable on
the lock screen and as a status-bar chip without unlocking.

The approach is deliberately small: one notification, upgraded. The decision-making — what
the surface says, what it may offer, and why it is unavailable — becomes a pure component in
`core/data` with unit tests; `PodMonitorService` renders it and owns nothing. Availability
is a runtime gate with a reason, in the same shape the transport gate already uses, because
API 36 puts this out of reach of most installs (D3) and the user can also refuse promotion.

## Technical Context

**Language/Version**: Kotlin 2.4.10, JVM toolchain 21

**Primary Dependencies**: `androidx.core` 1.19.0 (`NotificationCompat`, already present —
`setRequestPromotedOngoing` arrived in 1.17); Compose/Material 3 Expressive for the settings
entries. No new dependency.

**Storage**: Two new preferences in the existing DataStore-backed `SettingsRepository` —
live surface on/off, heart-rate value shown/hidden.

**Testing**: JUnit + kotest assertions off-device for the pure summary and gate; the adb
command surface for on-device verification, driven by injected advertisements.

**Target Platform**: Android; `minSdk` 26, `compileSdk`/`targetSdk` 37. The Live Update API
is **API 36** and is gated at runtime, not compile time.

**Project Type**: Android application, multi-module.

**Performance Goals**: The surface must follow state within one advertisement interval
(SC-002). It re-posts only when its rendered content changes — the state flow ticks far
faster than the surface should.

**Constraints**: No custom `RemoteViews` (disqualifies promotion). Not colorized. Channel
importance above `IMPORTANCE_MIN`. A dismissed live update must not be re-posted.

**Scale/Scope**: One accessory, one notification, two settings, one adb command.

## Constitution Check

*GATE: evaluated before Phase 0 and re-evaluated after Phase 1.*

| Principle | How this feature satisfies it | Verdict |
|---|---|---|
| **I. The transport gate is the law** | The surface reads `usableFeatures`/`gatedFeatures` (FR-011), never `PodModel.features`. The P1 slice deliberately needs only the advertisement transport, so it works where AAP never opens. The listening-mode control does not report a change until the accessory echoes it (FR-013) — reusing the existing gateway path, which already enforces this. | PASS |
| **II. Locked, not hidden** | A gated control appears locked with its reason (FR-012). Three unavailability states are kept apart — Android too old, user refused promotion, notifications denied — each with its own reason (R-5). User Story 4 exists solely for this. | PASS |
| **III. Protocol code is pure and pinned** | No protocol work. The new logic is a pure summary/gate component in `core/data` with unit tests (R-6). No fixture is touched. | PASS |
| **IV. Unknown traffic is surfaced** | Untouched. | PASS |
| **V. No fiction** | FR-002 and FR-007 force "unknown", "not in range" and "0%" to be three visibly different things. R-4 records that two Android docs disagree about permitted styles rather than picking one, and makes the device the arbiter. | PASS |
| **VI. Driveable and observable over adb** | `gp --es cmd live` prints availability, reason, posted state and every rendered field (R-8, FR-020). `docs/adb.md` updated in the same change (FR-021). SC-007 requires reproduction with no accessory present, which the existing `inject` already enables. | PASS |
| **VII. Unrooted, permissionless-by-default** | `POST_PROMOTED_NOTIFICATIONS` is a **non-runtime** manifest permission — no prompt, no new runtime grant. Where notification permission is denied the surface is simply absent and monitoring still runs, which is existing behaviour. | PASS |

**Carried constraint, not a gate**: FR-023 from `003-heart-rate` — no heart-rate reading in
any diagnostic path — is unchanged. D1 permits the value on the *user-facing* surface only;
the new adb command prints no reading (R-7). Reviewers should expect that asymmetry and it
is justified in research.

**No violations. Complexity Tracking is therefore empty and omitted.**

## Project Structure

### Documentation (this feature)

```text
specs/005-live-activities/
├── plan.md              # This file
├── spec.md              # Requirements and decisions
├── research.md          # Phase 0 — platform findings, with the unresolved style question
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1 — how to verify it, from adb
├── contracts/
│   └── live-surface.md  # Phase 1 — the adb command and the surface's content contract
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

```text
core/model/
└── src/main/kotlin/.../core/model/
    └── LiveActivityAvailability.kt      # new — gate states and reasons, no Android

core/data/
├── src/main/kotlin/.../core/data/live/
│   ├── LiveActivitySummary.kt           # new — what the surface says, pure
│   └── LiveActivityPolicy.kt            # new — what it may offer, pure; reads the gate
├── src/main/kotlin/.../core/data/settings/
│   └── (existing SettingsRepository)    # + liveActivityEnabled, liveActivityShowHeartRate
└── src/test/kotlin/.../core/data/live/
    ├── LiveActivitySummaryTest.kt       # new
    └── LiveActivityPolicyTest.kt        # new

app/
├── src/main/AndroidManifest.xml         # + POST_PROMOTED_NOTIFICATIONS
├── src/main/kotlin/.../service/
│   ├── PodMonitorService.kt             # modified — NotificationCompat, promotion, actions
│   └── LiveActivityNotification.kt      # new — renders a summary; thin, no decisions
└── src/debug/kotlin/.../debug/
    └── GreenPodsDebugReceiver.kt        # modified — `live` command

feature/settings/
└── src/main/kotlin/.../settings/        # modified — two entries, locked-with-reason

docs/adb.md                              # modified — the `live` command
```

**Structure Decision**: The existing module layout is kept exactly. The gate type goes in
`core/model` because it is a pure domain type with no Android dependency, alongside the
transport gate types it deliberately mirrors. The decisions go in `core/data` where they can
be unit-tested. Only rendering lives in `app`, which is the module that may touch
`Notification`. Dependency direction is unchanged: `app` → `core/data` → `core/model`.

## Phase plan

**Phase A — the gate (User Story 4, P3 but first).** `LiveActivityAvailability`, its
reasons, and the settings entries that show them. Deliverable: on any phone, the user can
find out whether this works and why. Shippable alone, and it is what every later phase
branches on.

**Phase B — the surface (User Story 1, P1).** The pure summary, the `NotificationCompat`
rendering with promotion requested, `hasPromotableCharacteristics()` verification, the
`live` adb command, `docs/adb.md`. Deliverable: battery, case and wear on the lock screen.
This is the MVP and it needs no writes, so it works where AAP does not.

**Phase C — the actions (User Story 2, P2).** Listening-mode control on the surface,
gated, echo-confirmed. Deliverable: one-tap mode change from the lock screen.

**Phase D — sensing disclosure and the reading (User Story 3, P2).** Disclosure, stop
action, and the heart-rate value behind its setting, with the disclosure not subject to that
setting.

Phases are ordered so each is independently shippable and each later one degrades to the
previous if its transport is unavailable.

## Post-Design Constitution Re-Check

Re-evaluated after the Phase 1 artefacts below were written. No change: the design keeps all
decision-making in pure, tested components; the only Android-touching addition
(`LiveActivityNotification`) takes a rendered summary and sets fields, with no branching of
its own beyond the platform version check. The single assumption the feature rests on — that
a foreground-service notification is actually promoted in practice — is recorded in research
as an open item to settle on hardware, not asserted.
