# Phase 1 data model: Heart rate

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

Types are grouped by the module they belong in, which is also the order they may depend on
each other: `core/model` knows nothing below it, `core/bluetooth` knows `core/model`,
`core/data` knows both.

---

## `core/model` — pure, no Android, no I/O

### `HeartRateReading` (replaces the current `HeartRateSample`)

One measurement at one instant.

| Field | Type | Notes |
|---|---|---|
| `beatsPerMinute` | `Int` | 1..255 as it arrives on the wire |
| `confidence` | `Int?` | The accessory's own byte, 0..255. **Null on the GATT route**, which publishes none — see R-10 |
| `source` | `Source` | `AAP` or `GATT`. Never inferred, always recorded (FR-005) |
| `measuredAtEpochMillis` | `Long` | **Derived, never raw.** The report's nanosecond field is an accessory-local monotonic counter, not an epoch — see R-4a. `anchorMillis + reportNanos / 1_000_000`, where the anchor is set once per session from the host clock. GATT readings use the host clock directly |
| `sequence` | `Int?` | The report's counter, for gap detection. AAP only |

Validation, applied at construction:

- `beatsPerMinute` outside **25..250** makes the reading *implausible*. It is not
  constructed; the caller surfaces it as unhandled traffic (FR-009).
- `confidence` is not interpreted here. `HeartRateReading` is data; the threshold lives in
  the policy, so the policy is what tests exercise.
- The **session anchor is not a field on the reading** — it lives in the controller, is
  re-established whenever a session starts, and is never carried across app launches. A
  reading is constructed with a wall-clock time already resolved, so nothing downstream can
  mistake the counter for a date.

The existing `HeartRateSample` is renamed rather than kept alongside: two nearly identical
reading types is how the two routes would eventually get blended by accident.

### `HeartRateState`

What the user is shown, and what the state dump prints. A sealed hierarchy, because the
distinctions in FR-008, FR-020, FR-027 and SC-008 are exactly the cases here.

| State | Carries | Meaning |
|---|---|---|
| `Unsupported` | `reason` | This model has no heart-rate sensor of either kind (FR-027) |
| `Locked` | `reason`, `transport` | The model has one; this phone cannot reach it (SC-008) |
| `Off` | — | Supported, reachable, not enabled. The default (FR-011) |
| `Starting` | `since` | Start requested, no report has arrived yet. **Not** "measuring" |
| `Settling` | `since` | Reports arriving, confidence below the gate. No number is shown (FR-006, FR-008) |
| `Measuring` | `reading` | A trusted reading. The only state that carries a number |
| `Uncertain` | `lastTrustedAt` | Confidence fell below the gate mid-session; the number is withdrawn, sensing continues (FR-006) |
| `Unavailable` | `reason` | Buds removed, channel dropped, or settling never converged (edge cases) |

Two rules are structural, not advisory:

- **Only `Measuring` has a reading.** No other state can be rendered as a number, because
  no other state holds one.
- **A transition into `Measuring` requires a report**, never an accepted write. A write is
  not a change (Principle I), and "the start command was accepted" is exactly the failure
  mode this transport is known for.

**Wear gating is `anyInEar`, not `bothInEar`.** Sensing runs while *either* bud is worn and
stops only when neither is, which is what the spec's one-bud edge case asks for: use a
reading if one is available. If the worn bud is not the one with the sensor, no reports
arrive and the ordinary no-convergence path reports that it cannot measure — which is a
different sentence from "you are not wearing them", and the right one.

State transitions:

```
Off ──enable──▶ Starting ──first report──▶ Settling ──confidence ≥ enter──▶ Measuring
                    │                          │                              │
                    │                          │ no convergence in 30 s       │ confidence < leave
                    ▼                          ▼                              ▼
                Unavailable ◀──not worn / channel gone──────────────────── Uncertain
                    │                                                          │
                    └──────────────── worn again, feature still on ────────────┘
                                              ▼
                                          Starting
```

`Off` is reachable from every state by the user disabling the feature, which also sends the
stop command (FR-014).

### `HeartRateSensing`

The bookkeeping half of the same story, kept separate because it is what the dump prints
and it must contain no values (FR-028).

| Field | Type | Notes |
|---|---|---|
| `enabled` | `Boolean` | The user's setting |
| `requestedIntervalMicros` | `Int?` | What was asked of the accessory; `0` means stop |
| `serviceId` | `Int?` | Discovered, never hard-coded (FR-002, R-1) |
| `reportsReceived` | `Int` | Count only |
| `trustedCount` | `Int` | Count only — SC-009 compares this with the health store |
| `discardedImplausible` | `Int` | Count only (FR-009) |
| `lastStopReason` | `String?` | Why it is not running |

### `PodFeature` changes

- `HEART_RATE_AAP.isImplemented` becomes `true`. Its `explanation` stops saying the frame
  is undecoded, because it is not.
- `HEART_RATE_GATT` is unchanged.
- `PodState` gains `heartRate: HeartRateState` (defaulting to `Off`/`Unsupported` as the
  model dictates) and loses `heartRate: HeartRateSample?`.

### `GreenPodsSettings` changes

| Field | Default | Why that default |
|---|---|---|
| `heartRateEnabled` | `false` | FR-011. A sensor that costs battery never starts unasked |
| `heartRateHealthConnectEnabled` | `false` | FR-017. Separately controllable from display |
| `heartRateIntervalMillis` | `1000` | The capture's cadence; a field rather than a constant so the assumption about battery cost is testable |
| `heartRateConfidenceThreshold` | `128` | Provisional, per R-4. A field so calibration does not need a rebuild |

---

## `core/bluetooth` — codecs, pure and pinned to captures

### `HidService`

One entry parsed out of a `0x17` descriptor frame.

| Field | Type | Notes |
|---|---|---|
| `id` | `Int` | Protobuf inner field 1. The value that goes into start/stop frames |
| `name` | `String?` | From the `AccessoryService` key — `devmotion`, `SPL0`, `HostLibHID`, `HeartRateService` |
| `reportDescriptor` | `ByteArray` | The raw HID descriptor bytes |
| `isHeartRate` | `Boolean` | Name is `HeartRateService`, or the entitlement string is present |

### `HidReportLayout` / `HidReportField`

The result of walking a report descriptor: per report id, the ordered input fields with
`usagePage`, `usage`, `bitSize`, `count`, and the derived byte offset. Plus the feature
report id that carries the 32-bit report interval.

Lookups the heart-rate decoder needs, by usage rather than by offset:

| Usage | Meaning |
|---|---|
| `0x0020:0x04B8` | Heart rate, BPM |
| `0xFF15:0x0120` | Confidence |
| `0xFF15:0x0121` | Sequence |
| `0xFF15:0x0004` | Timestamp, nanoseconds |

A layout with no confidence field is **not** usable for heart rate — see R-2.

### `AapEvent` additions

| Event | Carries |
|---|---|
| `HidServices` | `List<HidService>` — a descriptor frame |
| `HidServicesReady` | `List<Int>` — the field-12 readiness list |
| `HeartRateReport` | `serviceId`, `HeartRateReading` |
| `UnhandledHidReport` | `serviceId`, `reportId`, `length` — shape only, no body (R-9) |

`AapEvent.HeadTracking` stays, but is produced when the report belongs to the head-tracking
service, not when a `0x17` packet happens to be 55 bytes long.

---

## `core/data` — policy and I/O

### `HeartRateConfidencePolicy` (pure)

Holds the enter/leave thresholds and the plausibility range; maps a raw report to one of
*trusted*, *settling*, *uncertain*, *implausible*. All of FR-006 through FR-009 is
decidable here, off-device, which is the point of it being a separate object.

### `HeartRateController`

Owns the session: turns settings + wear state + transport availability into start/stop
commands, folds reports through the policy, and publishes `HeartRateState` and a stream of
trusted readings. Everything it decides is a function of inputs it is given, so the
interesting cases — never converging, confidence collapsing, the accessory vanishing
mid-measurement — are testable with fakes.

### `HealthConnectLink`

| Concept | Shape |
|---|---|
| Availability | `Available` / `NeedsProviderUpdate` / `NotOnThisDevice`, each with the sentence FR-020 requires |
| Permission | Granted / not, read on demand; never re-prompted automatically (FR-018) |
| Batch | Trusted readings for one aligned 60-second window, flushed on the boundary **and on stop** so a partial window is never discarded |
| Identity | `clientRecordId = "greenpods:hr:<windowStartEpochSeconds>"`, `clientRecordVersion = <monotonic flush counter>` (R-6, FR-019) |
| Permission contract | Exposed as `ActivityResultContract<Set<String>, Set<String>>`, so `feature/settings` can launch it without linking Health Connect (R-5) |
| Attribution | `Device(TYPE_HEAD_MOUNTED, manufacturer = "Apple", model = <PodModel.displayName>)` (FR-021) |

Only trusted readings ever reach a batch. That is enforced by type: the writer accepts
readings that came out of `HeartRateState.Measuring`, and nothing else can produce one.
