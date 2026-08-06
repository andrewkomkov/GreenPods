# Contract: the live surface and its adb command

**Feature**: `specs/005-live-activities` | **Date**: 2026-08-06

Two interfaces. The first is what the user sees; the second is how it is verified without
looking at it, which Principle VI requires.

## 1. The notification contract

The surface **is** the monitoring service's ongoing notification, promoted. There is one,
not two.

### Fields it must set

| Field | Value | Why |
|---|---|---|
| `contentTitle` | The accessory's name | Required for promotion; FR-004 |
| `contentText` / `BigTextStyle` | The rendered summary | FR-002, FR-003 |
| `smallIcon` | Existing headset icon | Unchanged |
| `contentIntent` | Status screen | FR-009 |
| `setOngoing(true)` | Always | Required for promotion; already true |
| `setRequestPromotedOngoing(true)` | When the policy says post | The promotion request itself |
| `deleteIntent` | Records dismissal | The platform forbids reposting a dismissed live update |

### Fields it must never set

| Field | Why |
|---|---|
| `customContentView` / any `RemoteViews` | **Disqualifies** the notification from promotion |
| `setColorized(true)` | Same — disqualifies it |
| `setGroupSummary(true)` | Same |

A channel at `IMPORTANCE_MIN` also disqualifies it. The existing channel is
`IMPORTANCE_LOW`, which is fine, and must not be lowered.

### Actions

| Action | Condition | Behaviour |
|---|---|---|
| Cycle listening mode | `noiseControl` is `Offered` | Writes through the existing gateway; the surface shows the mode the **accessory** reports, never the requested one |
| Explain the lock | `noiseControl` is `Locked` | Opens the app at the explanation. Must not silently do nothing |
| Stop sensing | `sensing` is not `Idle` | Stops the sensor **in the accessory**, not only in the app |

### Content rules

- `null` battery renders as unknown — a dash, never `0%`.
- `OutOfRange` renders as "not in range" and **suppresses the levels**. It never shows the
  last known value as if it were current.
- Charging is visibly distinct from a static level.
- Heart rate appears only while a session is active, and the value only when the setting
  allows; the disclosure appears either way.

## 2. The adb command contract

```bash
gp --es cmd live
```

Prints one line per fact. Machine-greppable, stable key names.

```
live: availability=AVAILABLE reason=none posted=true dismissed=false
live: accessory="AirPods Pro" presence=IN_RANGE
live: battery left=72% right=68% case=unknown charging=[CASE]
live: wear left=IN_EAR right=IN_EAR
live: noiseControl=OFFERED mode=TRANSPARENCY
live: sensing=DISCLOSED valueShown=true
```

Unavailable, with the reason the user is shown:

```
live: availability=PLATFORM_TOO_OLD reason="This phone's Android cannot show a live update (API 34, needs 36)" posted=false
```

### What it must never print

**No heart-rate value.** `sensing=DISCLOSED valueShown=true` says the surface *would* show
one; it does not say what it is.

This is not an inconsistency with D1 and is worth stating because it looks like one. FR-023
from `003-heart-rate` forbids a reading in any diagnostic path, and this is the most
diagnostic path in the app — people paste its output into bug reports. D1 permits the value
on the lock screen, where a user is looking at their own body's data. The two surfaces are
for different audiences and get different rules.

### Related existing commands

Nothing new is needed to drive it. These already exist and are what make SC-007 reachable
with the earbuds in a drawer:

```bash
gp --es cmd inject --es model 0x1420 --ei left 40 --ei right 30 --ei case 90 --es wear in_ear
gp --es cmd set --es key liveActivityEnabled --es value off
gp --es cmd set --es key liveActivityShowHeartRate --es value off
gp --es cmd monitor --es value on
```

## 3. Verification obligations

Every one of these must be demonstrable from a terminal:

| Requirement | How it is shown |
|---|---|
| FR-002, FR-007 | Inject a sighting, read `live`; stop injecting, watch `presence=OUT_OF_RANGE` and the levels drop |
| FR-011, FR-012 | With AAP unavailable, `noiseControl=LOCKED` with a reason |
| FR-014, FR-014a | On API < 36, `availability=PLATFORM_TOO_OLD` with a reason, and the ordinary notification unchanged |
| FR-017 | With no session, `live` output contains no `sensing` value beyond `IDLE` |
| FR-018a | With `liveActivityShowHeartRate=off`, `sensing=DISCLOSED valueShown=false` — disclosed either way |
| FR-019 | With `liveActivityEnabled=off`, `posted=false` while monitoring still runs |
