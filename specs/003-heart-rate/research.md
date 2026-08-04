# Phase 0 research: Heart rate

**Feature**: [spec.md](./spec.md) | **Date**: 2026-08-04

Everything below was either read out of a running accessory or read out of the library
that will be linked against. Nothing here is inferred from documentation alone, and the
things that remain unverified are listed at the end rather than smoothed over.

**Capture context for R-1..R-4**: Pixel 8 (shiba), Android 17 / API 37, unrooted, stock;
AirPods Pro 3 (`0x2720`), firmware `81.2675000075000000.6877`, model `A3063`; AAP channel
opened by GreenPods `0.2.0-debug` via `gp --es cmd raw`, frames read with
`log.tag.AapTransport DEBUG`. 2026-08-04.

---

## R-1 — Service ids are discoverable, and this is what they look like

**Decision**: Discover the heart-rate service id by parsing the descriptor frames the
accessory sends unprompted under opcode `0x17`. Never hard-code `0x13`.

**Rationale**: FR-002 requires it, and until today the *shape* of those frames was not
written down — only the fact that `0x13` was HeartRate on this model. It is now decoded.

A `0x17` frame is the ordinary AAP header, a 12-byte prefix, and a protobuf body:

```
04 00 04 00 | 17 00 | 00 00 10 00 | <len u16 LE> | <protobuf>
```

The body's top level, as observed:

| Field | Wire type | Meaning |
|---|---|---|
| 1 | varint | Sequence. Increments per frame (11, 12, 13, 14 observed). |
| 2 | varint | Constant `1` on every descriptor frame. |
| 5 | bytes, repeated | **One service descriptor.** |
| 7 | bytes | Input report — `08 <service id> 1A <len> <report>` (already documented). |
| 12 | bytes, repeated | **Service is ready** — `08 <service id>`, one per service. |
| 8 | bytes | Start/stop request (host → accessory), already documented. |

Each field-5 descriptor is:

```
08 <service id varint>          inner field 1
12 <len> <property blob>        inner field 2
```

The property blob is a serialised Apple property dictionary — `D3 <count> …`, then
entries of `<u16 keylen> 00 00 09 <ASCII key> <typed value>`. Four services were
announced, and their ids came back exactly as the inner field 1 varint:

| Service id | `AccessoryService` key | Property keys carried |
|---|---|---|
| `0x10` | `devmotion` | VendorID, LocationID, SerialNumber, CFG#, ProtectedAccess, PrimaryVendorUsages, H2HTransformation, ReportDescriptor |
| `0x11` | `SPL0` | MaxReportSize, VendorID, MaxFIFOSize, ReportDescriptor |
| `0x12` | `HostLibHID` | MaxReportSize, MultipleInterfaceEnabled, VendorID, ReportDescriptor |
| `0x13` | `HeartRateService` → `HeartRate` | MaxReportSize (601), LocationID, VendorID, HIDServiceAccessEntitlement, HIDDeviceAccessEntitlement (both `com.apple.hid.heartrate-access`), ReportDescriptor |

Two frames later the accessory sent the readiness list — field 12 entries carrying
`0x10`, `0x11`, `0x12`, `0x13` — which is the accessory saying those services are up.

**Identification rule chosen**: a descriptor is the heart-rate one if its property blob
contains the ASCII key `HeartRateService`, or failing that the entitlement string
`com.apple.hid.heartrate-access`. Both are model-independent names; the id beside them is
not. The blob does not need a full dictionary parser for this — the keys are length-
prefixed ASCII and can be located by scan, and the id comes from the protobuf field, not
from the blob.

**Alternatives rejected**:

- *Hard-code `0x13`.* Fails FR-002, and the research notes already record LibrePods
  hard-coding `0x0E` for head tracking and it being silently ignored by this model. The
  same class of bug, one model later.
- *Try every announced id and see which one answers.* Starts an optical sensor on
  services that are not the heart-rate sensor to find out what they are. Costs battery to
  learn something the accessory already told us.

## R-2 — The report layout comes from the accessory's own HID descriptor

**Decision**: Parse the `ReportDescriptor` value out of the heart-rate service's property
blob, walk it as HID short items, and derive the field offsets from the declared usages.
Do not carry a hard-coded struct layout.

**Rationale**: The descriptor is 126 bytes and fully decodable. Captured verbatim today:

```
05 20 09 16 A1 01 85 01 0A 0E 03 14 27 FF FF FF 7F 75 20 95 01 B1 02
0A B8 04 26 FF 00 75 08 95 01 81 02
06 15 FF 0A 20 01 95 01 75 08 81 02
26 FF 7F 06 15 FF 0A 21 01 95 01 75 10 81 02
75 08 95 01 15 01 25 02 1A 04 01 2A 05 01 81 00
06 00 FF 09 23 A1 00 15 00 24
06 15 FF 09 04 95 08 75 08 81 02
06 0A FF 09 12 95 04 75 08 81 02
85 02 06 0A FF 09 13 96 58 02 75 08 81 02 C0 C0
```

Walked:

| Items | Meaning |
|---|---|
| `05 20` `09 16` `A1 01` | Usage page Sensors, biometric collection |
| `85 01` | **Report ID 1** |
| `0A 0E 03` … `75 20 95 01 B1 02` | **Feature** report: report interval, 32-bit — the field that starts and stops the stream |
| `0A B8 04` `26 FF 00` `75 08 95 01 81 02` | Input: **heart rate**, usage `0x0400B8`, 8 bits |
| `06 15 FF` `0A 20 01` `75 08 81 02` | Input: vendor usage **`0xFF15:0x0120`**, 8 bits — the byte that behaves as confidence |
| `06 15 FF` `0A 21 01` `75 10 81 02` | Input: vendor usage `0xFF15:0x0121`, 16 bits — the sequence counter |
| `15 01 25 02` `1A 04 01 2A 05 01` `81 00` | Input: status enum, logical 1..2, usages `0x0104..0x0105` |
| `06 15 FF 09 04` `95 08 75 08 81 02` | Input: 8 bytes — the nanosecond timestamp |
| `06 0A FF 09 12` `95 04 75 08 81 02` | Input: 4 bytes — the vendor tail |
| `85 02` `06 0A FF 09 13` `96 58 02 75 08 81 02` | **Report ID 2**: 600 bytes, vendor `0xFF0A:0x13`. Purpose unknown. |

That reproduces the 18-byte report layout in `docs/protocol-research.md` field for field,
from the accessory rather than from a diff — which is the confirmation that was missing.

Two corrections to the notes fall out of it: the item group previously described as a
"measurement-confidence style enum" (`1A 04 01 2A 05 01 81 00`) is the **status** field,
and the confidence byte is the vendor usage `0xFF15:0x0120` before it. And `MaxReportSize`
is 601, which is report ID 2 plus its byte — so the second report is a real thing the
service can send, not a descriptor artefact.

**Consequence for the gate**: confidence is identified by a *vendor* usage. If a future
model's descriptor carries no `0xFF15:0x0120` field, there is no confidence to gate on,
and FR-006 cannot be honoured. In that case the feature reports itself unsupported with
that reason rather than showing ungated numbers. An ungated number is precisely the
failure this specification exists to prevent.

**Alternatives rejected**: a fixed 18-byte struct. It works today on one model and is
indistinguishable from working when it silently stops being right.

## R-3 — Frames must be reassembled, and `0x17` must stop being decoded by size

**Decision**: Give `AapTransport` a length-aware reassembler and route `0x17` by protobuf
content.

**Rationale**: Two defects, both visible in today's capture.

*Reassembly.* `AapTransport` reads into a 1024-byte buffer and emits whatever one
`read()` returned. The descriptor frames observed were 488, 996, 20 and 28 bytes —
declared length matched actual length in all four, so nothing split *this time*. The
996-byte frame carried three service descriptors and sat 28 bytes under the buffer. A
model with one more service, or a longer serial, crosses it. The 16-bit length at offset
10 is what makes reassembly possible, and it is already on the wire.

*Mis-decode.* `AapDecoder.decode` sends every `0x17` packet of 55 bytes or more to
`decodeHeadTracking`, which reads fixed offsets 43..53. Every descriptor frame above is
therefore currently decoded as a head-tracking sample of garbage. Nothing consumes it
today because head gestures are off by default, which is the only reason this has not
surfaced. Heart rate makes `0x17` a shared channel in earnest, so it must be dispatched on
the protobuf field — 7 with a service id for input reports, 5 for descriptors, 12 for
readiness — and head tracking recognised by *its* service id rather than by packet length.

**Alternatives rejected**: decoding heart rate on a second, separate socket. There is one
channel; a second session would not be the session everything else uses.

## R-4 — The confidence threshold, and how it is set

**Decision**: Ship an initial threshold of **128** (half of the byte's range), applied with
hysteresis, and make it overridable from adb so it can be calibrated from data rather than
re-argued.

**Rationale**: The only series in hand is the one in `docs/protocol-research.md`: the four
wrong readings (169, 147, 128, 96 BPM from someone sitting still) all carried confidence
`20`; the readings that had converged carried 156, 205, 233 and 237. Any threshold between
21 and 156 separates that capture, so the capture cannot choose one — it can only rule out
the extremes. 128 sits in the middle of the admissible range, is defensible without
pretending to be derived, and the spec's own assumption says the real value comes from
measurement across sessions and models.

Hysteresis rather than a bare comparison: readings straddling a threshold would otherwise
flicker the display between a number and "uncertain" once per second. Enter the trusted
state at ≥ 128, leave it at < 96.

**Alternatives rejected**:

- *Threshold 21.* Admits everything the observed capture did not disqualify, which
  disqualifies nothing in practice.
- *Wait for N consecutive good readings instead of using the byte.* Reinvents, worse, a
  quantity the accessory already publishes, and would have shown 169 BPM had the sensor
  been steady-but-wrong for four seconds.

## R-4a — The report's timestamp is not a wall clock

**Decision**: Treat the report's 8-byte timestamp as an **accessory-local monotonic
counter**. Anchor it once per session against the host clock and derive every reading's
wall-clock time from the anchor.

**Rationale**: The captured series reads

```
56339327885000, 56340327882000, 56341327878000 …   nanoseconds
```

`56339327885000 ns` is **56 339 s — about 15 hours 39 minutes**. A Unix epoch in
nanoseconds would be around `1.78e18`. So this is not a date; it is a counter running from
some accessory-local origin — plausibly since the buds powered on. Consecutive values are
exactly 1.000 s apart, which is what makes it useful and also what makes it dangerous:
the *intervals* are trustworthy and the *absolute value* is meaningless as a time.

Writing it into Health Connect as an instant would put every reading roughly fifteen hours
in the past, and SC-003 asks for times correct to the second. It would also be exactly the
failure Principle V describes — a field whose meaning was assumed from its shape.

The mapping:

```
on the first report of a session:  anchorMillis = hostNowMillis - (reportNanos / 1_000_000)
for every report:                  measuredAtEpochMillis = anchorMillis + reportNanos / 1_000_000
```

Re-anchor when a session restarts, because nothing guarantees the counter survives a
disconnect, and never carry an anchor across app launches. Within a session the report
deltas then carry the spacing — which is the part actually worth having, since it is more
precise than the moment a packet happened to be read off a socket.

**Alternatives rejected**:

- *Use the host clock at arrival for every reading.* Loses the accessory's own 1.000 s
  spacing to socket and scheduling jitter, for no gain.
- *Use the raw value as an epoch.* Wrong by fifteen hours, and silently so.

**Unverified**: what the counter's origin actually is. The anchor approach does not need to
know — it only needs the counter to be monotonic within a session, which the capture shows.

## R-5 — Health Connect: the API facts, checked against the artifact

**Decision**: `androidx.health.connect:connect-client:1.1.0`, following the shape proven
in `xoss-health`.

Verified by disassembling the AAR in the local Gradle cache, not from memory:

| Fact | Value |
|---|---|
| `minSdkVersion` of the library | **26** — exactly GreenPods' `minSdk`, so no new floor |
| Availability | `HealthConnectClient.getSdkStatus(context)` → `SDK_AVAILABLE`, `SDK_UNAVAILABLE`, `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` |
| Record | `HeartRateRecord(startTime, startZoneOffset, endTime, endZoneOffset, samples, metadata)`, samples are `(Instant, Long bpm)` |
| Device attribution | `Device(type, manufacturer, model)` with **`Device.TYPE_HEAD_MOUNTED`** present |
| Dedup identity | `Metadata.autoRecorded(device, clientRecordId, clientRecordVersion)` |
| Permission request | `PermissionController.createRequestPermissionResultContract()` → `ActivityResultContract<Set<String>, Set<String>>` — parameterised on plain strings, so it can be handed to a UI module without that module linking Health Connect |
| Permissions | `HealthPermission.getWritePermission(HeartRateRecord::class)`, and the read permission for FR-029 |

The three distinct availability states matter: FR-020 wants a reason, and "Health Connect
needs an update" is a different sentence from "this device does not have it".

**Alternatives rejected**: Google Fit APIs — retired. Writing a file the user exports —
does not satisfy "other apps can read it".

## R-6 — One reading, one identity

**Decision**: Batch trusted samples into one `HeartRateRecord` per **wall-clock-aligned
60-second window**, with `clientRecordId = "greenpods:hr:<windowStartEpochSeconds>"` and
`clientRecordVersion = <a monotonic flush counter>`.

**Rationale**: FR-019 and SC-009 want no duplicates after an interruption, and Health
Connect's deduplication key is the client record id. Alignment to the wall clock is what
makes the id deterministic: the same second always lands in the same window, so a session
that stops and resumes mid-window recomputes the same id and *updates* the record instead
of writing a second one beside it.

The version is a **monotonic counter incremented on every flush**, not the sample count.
Health Connect applies an update only when the version rises, and two different flushes of
one window can carry the same number of samples — a session that stopped after 30 seconds
and a later one covering the other 30 both hold 30 of them. A count would then leave
whichever landed first, silently. A flush counter always rises, so the most recent view of
a window wins.

A **partial window is flushed when sensing stops**, not held: a session ending at 00:40
must not discard 40 seconds of readings waiting for a minute boundary that will not
arrive. Because the id comes from the window and not from the flush, that partial write is
updated rather than duplicated if sensing resumes inside the same minute.

An id derived from "when this batch started" instead would produce a new id for the same
seconds after any interruption — exactly the duplicate the requirement forbids.

**Alternatives rejected**: one record per reading. 3,600 records an hour, each with its own
row and identity, for data that is a series by nature.

## R-7 — Where the code goes

**Decision**: No new Gradle module. HID/protocol code joins `core/bluetooth`, policy and
the Health Connect wrapper join `core/data`, state types join `core/model`.

**Rationale**: The dependency direction in the constitution is `feature/*` → `core/data` →
`core/bluetooth` → `core/model`, and every piece of this feature falls naturally on one of
those rungs. A `core/health` module would need `core/data` to depend on it, which is a new
edge in a graph the constitution describes as one-way, in exchange for isolating a single
dependency. Not worth it at this size.

## R-8 — Sensing runs where the channel runs

**Decision**: The heart-rate session is owned by the same component that owns the AAP
channel, and background sensing rides the existing `connectedDevice` foreground service.
Enabling heart rate implies the monitoring service is running; the toggle says so.

**Rationale**: FR-013 wants active sensing discoverable without opening the app, and
Android already requires a foreground service for continuous Bluetooth work. One
notification that says a heart rate is being measured satisfies the requirement and the
platform at once. FR-015's "stop when not worn" is driven by the wear state the
advertisement transport already provides — no new source.

## R-9 — Privacy versus "surface unknown traffic"

**Decision**: Unknown HID traffic is reported by *shape* — service id, report id, length,
and the first bytes of anything that is **not** a known heart-rate report. Heart-rate
report bodies never reach the diagnostics log, the state dump or `AapTransport`'s frame
logging path.

**Rationale**: Principle IV says nothing is dropped silently; FR-023 says values appear in
no diagnostic. Both hold if what is recorded is that a report arrived and what it was,
rather than what it said. The one place they genuinely collide is the raw `tx/rx` hex
logging, which is already off by default and already documented as carrying serial numbers
— heart rate joins that list in `docs/adb.md` rather than being exempted from it.

`StateDump` currently prints `heartRateBpm`. That field is removed and replaced by the
sensing state, which is what FR-028 asks for.

## R-10 — The Powerbeats path is wiring, not new work

**Decision**: Drive `HeartRateGattSource` from the same controller that drives the AAP
path, feeding the same state type; keep acquisition entirely separate below that point.

**Rationale**: `HeartRateGattSource` exists, is tested, and is collected by nothing —
`grep` finds no caller. US4 is therefore a wiring task plus the same presentation. FR-004
forbids blending, which the design honours by joining the two only at the state the UI
reads, with the source recorded on every reading (FR-005).

The standard profile carries **no confidence field**. Per R-2's rule this would disqualify
it — except that the SIG profile's contract is different: a chest strap or a Powerbeats
publishes a measurement it already considers valid, with no settling series to protect the
user from. The gate for that route is therefore the plausibility range alone (FR-009), and
the UI says which route produced the number. This is a deliberate, documented asymmetry,
not an oversight.

---

## Still unverified

Carried forward into implementation, and none of them blocks it:

1. **BPM has never been compared against a reference monitor.** SC-002 cannot be claimed
   until it is. This is the spec's own assumption and it stands.
2. **The confidence threshold is provisional** (R-4). It is a constant with a stated
   provenance and an adb override, not a measured value.
3. **Report ID 2** (600 bytes, vendor usage `0xFF0A:0x13`) is undecoded. It becomes
   unknown traffic, surfaced by shape.
4. **`HRM_STATE` (`0x30`) was already `1`** on the captured device and is still never
   written. Whether the HID stream requires it remains untested; the plan does not write
   it, so if a device ever arrives with it at `2` this is the first thing to check.
5. **Frames larger than the read buffer have not been observed splitting.** The
   reassembler is written for a case that is predicted, not seen — and is testable
   off-device by feeding it split input.
6. **Confidence is a name given to a vendor usage from behaviour**, not from
   documentation. It is right about this capture and could be about something adjacent.
