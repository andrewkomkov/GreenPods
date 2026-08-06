# Data Model: Live Activities

**Feature**: `specs/005-live-activities` | **Date**: 2026-08-06

Three types, none of which owns anything. Every one is derived from state that already
exists, and every one is pure — no Android, no I/O — so the behaviour worth testing is
testable without a device.

## `LiveActivityAvailability` — `core/model`

Whether this phone can present the surface, and why not when it cannot. Deliberately the
same shape as `AapAvailability`: the user must be able to tell "my phone can't" from "I
turned this off" from "the app is broken", and one type collapsing those would be the exact
failure Principle II exists to prevent.

| State | Meaning | What the user is told |
|---|---|---|
| `Available` | The surface can be posted | — |
| `PlatformTooOld(apiLevel)` | Below API 36; the platform has no promoted surface | This phone's Android cannot show it; monitoring is unaffected |
| `PromotionRefused` | The user turned promoted notifications off for this app | You turned this off — with a route to the system setting |
| `NotificationsDenied` | Notification permission not granted | Monitoring still runs; the surface cannot appear |
| `NotPromotable(reason)` | The device declined `hasPromotableCharacteristics()` | This device will not promote it, with what it objected to |

`isAvailable` is `this is Available`. Nothing else may infer availability.

**Note on `NotPromotable`**: it exists because R-4 leaves the permitted style genuinely
unsettled between two Android documentation pages. Rather than assume, the app asks the
platform and reports the answer. If it never fires in the field, the type is cheap; if it
does, it is the difference between a diagnosable gate and a feature that silently does
nothing.

## `LiveActivitySummary` — `core/data/live`

What the surface says at one moment. Produced by a pure function from `PodState`,
`Settings` and `LiveActivityAvailability`.

| Field | Type | Rules |
|---|---|---|
| `accessoryName` | `String` | Always present. FR-004 — the surface must never be ambiguous about whose battery it shows |
| `presence` | `Presence` | `InRange` or `OutOfRange`. FR-007 |
| `leftPercent` | `Int?` | Null is **unknown**, and must render differently from `0` |
| `rightPercent` | `Int?` | As above |
| `casePercent` | `Int?` | As above. FR-002 |
| `charging` | `Set<BatteryComponent>` | FR-010 — charging must be distinguishable from a static level |
| `wear` | `EarDetectionState` | From the advertisement, which carries the side. FR-003 |
| `noiseControl` | `ControlState` | `Offered(mode)` or `Locked(reason)`. FR-011, FR-012 |
| `sensing` | `SensingState` | `Idle`, `Disclosed`, or `Disclosed(bpm)`. FR-015 – FR-018a |

### `Presence`

`OutOfRange` is not "battery unknown" and not "0%". When the accessory stops advertising,
the summary says so and the levels are **not** carried forward. Presenting the last known
level as current is the specific failure FR-007 names.

### `ControlState`

`Locked` carries the gate's own reason string — the same one the status screen shows — so
the lock screen and the app never explain the same lock two different ways.

### `SensingState`

Three states, and the distinction between the last two is the whole of D1:

- `Idle` — no session. The summary mentions heart rate **not at all** (FR-017).
- `Disclosed` — a session is running; the value is hidden by the user's setting.
- `DisclosedWithRate(bpm)` — a session is running and the user allows the value.

`Idle → Disclosed` is not subject to any setting. A user may decline to display their heart
rate; they may not have the sensor run without being told (FR-018a). The setting selects
between the two disclosed forms and can never reach `Idle`.

## `LiveActivityPolicy` — `core/data/live`

The pure decision: given availability, settings and pod state, should a surface be posted at
all, and what should it contain? Returns a summary or a reason not to.

Encodes four rules that are easy to get wrong in a renderer and hard to see once they are:

1. **Monitoring off ⇒ no surface.** It is a view onto the monitoring session, not something
   that outlives it (FR-008).
2. **Dismissed ⇒ stay down.** The platform is explicit that a dismissed live update must not
   be reposted. The policy holds the dismissal until there is a genuine reason to post
   again — a new accessory, or sensing starting.
3. **Availability is checked before content is built**, so nothing can render a control the
   gate has locked by forgetting to ask.
4. **Re-post only when the rendered content changes.** The pod flow ticks far faster than the
   surface should; the edge case about rapid wear changes is prevented here rather than
   debounced in the service.

## What is *not* modelled

No new persisted entity. No new identity concept — "the accessory" is the one the existing
bond resolution already returns, and that problem was solved and shipped in v0.3.0; this
feature reuses it and must not re-derive it.

## Settings additions

| Key | Default | Meaning |
|---|---|---|
| `liveActivityEnabled` | `true` | FR-019. Opt-out: it replaces a notification the user already sees, so it adds no new interruption |
| `liveActivityShowHeartRate` | `true` | D1. Hides the value, never the disclosure |

Both are ordinary entries in the existing `SettingsRepository` and are settable from adb
through the existing `set` command, which is what makes FR-020 achievable without new
machinery.
