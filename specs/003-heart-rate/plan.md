# Implementation Plan: Heart rate

**Branch**: `003-heart-rate` | **Date**: 2026-08-04 | **Spec**: [spec.md](./spec.md)

**Artifacts**: [research.md](./research.md) · [data-model.md](./data-model.md) ·
[contracts/](./contracts/) · [quickstart.md](./quickstart.md)

## Summary

Ship heart rate from AirPods Pro 3 over the Apple protocol, present it only when the
accessory says it is worth presenting, and write it into Health Connect so the user's other
apps can read it.

The protocol half is smaller than it looks: the accessory publishes a description of its
own heart-rate sensor, and Phase 0 decoded that description off a real device today — see
[research.md](./research.md) R-1 and R-2. What remains is a HID descriptor walker, a report
decoder, and a service-discovery step that reads the sensor's id instead of assuming it.

The half that carries the risk is everything downstream of the confidence byte. The sensor
opens by reporting 169 BPM from someone sitting still, and the only thing standing between
that and the user's health history is a gate that must hold in the UI, in the writer, and
in the diagnostics at the same time. The design puts a number in exactly one state object,
so the states that must not show one structurally cannot.

Two existing defects are in scope because heart rate walks into both: `0x17` frames are
currently decoded by packet length and so every descriptor frame is read as a garbage
head-tracking sample, and the transport emits whatever one socket read returned with no
regard for the declared frame length.

## Technical Context

**Language**: Kotlin 2.4, coroutines/Flow
**New dependency**: `androidx.health.connect:connect-client:1.1.0` — `minSdk` 26, the same
floor GreenPods already has (verified against the artifact, R-5)
**UI**: Compose, Material 3 Expressive, `GreenPodsMotion` tokens
**Storage**: existing DataStore settings; readings are not persisted by GreenPods beyond
the current session
**Target**: `minSdk` 26 / `compileSdk` 37; AAP gated at runtime, Health Connect gated at
runtime
**Testing**: JUnit4 + kotest + turbine + coroutines-test off-device, against fixtures
captured from hardware; adb scenarios on device
**Verification device**: Pixel 8 (shiba), Android 17 / API 37, unrooted, AirPods Pro 3 —
the device the Phase 0 capture came from
**Scale**: one reading per second per accessory; one `HeartRateRecord` per minute

## Constitution Check

*Gate evaluated before Phase 0 and re-evaluated after Phase 1 design. Result: pass, with
one tension recorded under Complexity Tracking.*

| Principle | How this plan satisfies it |
|---|---|
| I. Transport gate is law | Heart rate is derived from `PodState.usableFeatures`, never from `PodModel.features`. The AAP route needs `AAP_L2CAP`, the GATT route needs `GATT`, and the two are separate features already. **A write is not a change**: `Measuring` is reachable only from an arriving report, never from an accepted start command — the state machine has no other edge into it. |
| II. Locked, not hidden | `HeartRateState` distinguishes `Unsupported` (this model has no sensor) from `Locked` (this phone cannot reach it), each carrying its reason. SC-008 is a state, not a string built in the UI. |
| III. Pure, pinned codecs | `HidDescriptorParser`, `HidReportDescriptor`, `HeartRateReportDecoder` and `HeartRateConfidencePolicy` do no I/O and take no Android types. Fixtures are the byte sequences captured today, recorded in [contracts/aap-hid.md](./contracts/aap-hid.md). |
| IV. Unknown traffic surfaced | Report id 2, unknown services and malformed reports become `UnhandledHidReport` with service id, report id and length — surfaced by shape, never by body. |
| V. No fiction | The report layout is read from the accessory rather than assumed. The confidence threshold ships as a provisional constant that says so. SC-002 stays unclaimed until a reference monitor is used, and the unverified list in research.md is carried, not quietly dropped. |
| VI. Driveable over adb | `hr on/off/status`, `health status/count/clear`, and threshold and cadence as settings keys. The dump gains sensing state and counters and **loses** its BPM field. [contracts/adb.md](./contracts/adb.md) is the acceptance surface. |
| VII. Unrooted, permissionless-by-default | No new capability beyond the existing non-SDK exemption. The health permission is an ordinary runtime permission, separately controllable, and the reading works without it. |

No new Gradle module. No feature module gains a dependency on another.

## Project Structure

### Documentation

```text
specs/003-heart-rate/
├── plan.md              # this file
├── research.md          # Phase 0 — includes the 2026-08-04 descriptor capture
├── data-model.md        # Phase 1
├── contracts/
│   ├── aap-hid.md       # the wire contract, opcode 0x17
│   ├── health-connect.md# what other apps will read
│   └── adb.md           # the command surface
├── quickstart.md        # how the feature is proven
├── checklists/
└── tasks.md             # /speckit-tasks, not created here
```

### Source

```text
core/model/
├── HeartRate.kt                    HeartRateReading, HeartRateState, HeartRateSensing
├── PodModel.kt                     HEART_RATE_AAP.isImplemented → true, new explanation
├── PodState.kt                     heartRate: HeartRateState (was HeartRateSample?)
└── Settings.kt                     4 new fields, all off/provisional by default

core/bluetooth/
├── aap/HidTransport.kt             0x17 framing: reassembly, field dispatch, start/stop builders
├── aap/HidDescriptorParser.kt      protobuf field 5/7/12 → HidService, by name not by id
├── aap/HidReportDescriptor.kt      HID short-item walker → HidReportLayout
├── aap/HeartRateReportDecoder.kt   report bytes + layout → HeartRateReading
├── aap/AapDecoder.kt               route 0x17 by content; head tracking by service id
├── aap/AapTransport.kt             length-aware reassembly; buffer raised
└── gatt/HeartRateGattSource.kt     unchanged; finally has a caller

core/data/
├── heartrate/HeartRateController.kt     the session: settings + wear + transport → start/stop
├── heartrate/HeartRateConfidencePolicy.kt  pure: trusted / settling / uncertain / implausible
├── health/HealthConnectLink.kt          availability, permission, batched writes, own-record reads
├── health/HeartRateBatcher.kt           pure: readings → aligned windows with stable ids
└── PodRepository.kt                     onHeartRate → HeartRateState; overlay updated

feature/pods/       heart-rate card: measuring / settling / uncertain / locked / unsupported
feature/settings/   enable toggle with the battery sentence, Health Connect section, delete

app/
├── health/HealthRationaleActivity.kt    FR-022 — reachable from system health settings
├── GreenPodsApplication.kt              controller + link wired into the container
├── service/PodMonitorService.kt         sensing runs here; the notification says so
├── debug/GreenPodsDebugReceiver.kt      hr and health commands
├── debug/StateDump.kt                   heartRateBpm removed; state and counters added
└── AndroidManifest.xml                  health permissions, provider query, rationale activity
```

**Structure decision**: the existing module layout, unchanged. Every piece of this feature
falls on an existing rung of `feature/*` → `core/data` → `core/bluetooth` → `core/model`;
a `core/health` module would add an edge to that graph to isolate one dependency (R-7).

## Architecture decisions

**AD-1 — The service id is discovered, and a test enforces it.** `HidDescriptorParser`
finds the heart-rate service by the `HeartRateService` name or the
`com.apple.hid.heartrate-access` entitlement, and takes the id from beside it. A unit test
feeds the captured frame with the ids renumbered and expects the sensor to still be found.
That test is the enforcement of FR-002; without it, "discovered" decays into "read from a
constant that happens to match".

**AD-2 — The layout comes from the descriptor, by usage.** `HidReportDescriptor` walks HID
short items into an ordered field list; offsets are derived, not written down. The decoder
asks for usage `0x0400B8` and usage `0xFF15:0x0120` rather than for bytes 1 and 2. A
descriptor with no confidence field produces no usable layout — the feature reports itself
unsupported rather than showing numbers it cannot vouch for (R-2).

**AD-3 — One state object, one number.** `HeartRateState.Measuring` is the only variant
that carries a reading. The UI renders states, the writer accepts only what came from
`Measuring`, and the dump prints the state's name. FR-006, FR-007 and FR-023 stop being
three rules that three components must remember and become one property of the type.

**AD-4 — The policy is pure and the controller is thin.** `HeartRateConfidencePolicy` maps
a report to trusted / settling / uncertain / implausible, with enter and leave thresholds
so a reading straddling the gate does not flicker the display once a second.
`HeartRateController` holds the session and does no judging.

**AD-5 — `0x17` is dispatched on content.** The current length-based branch is replaced:
field 5 is descriptors, field 7 an input report tagged with its service id, field 12 the
readiness list. Head tracking is recognised by its own service id. This fixes descriptor
frames currently decoding as head-tracking garbage, which nothing noticed only because
gestures are off by default.

**AD-6 — Reassembly belongs in the transport.** The 16-bit length at offset 10 is used to
buffer partial frames and split coalesced ones, so decoders always see whole frames.
Today's largest observed frame was 996 bytes against a 1024-byte buffer — the margin is
one extra service, and the failure mode without this is silent misparsing rather than an
error (R-3).

**AD-7 — Health Connect batches are wall-clock windows.** One record per aligned
60-second window, `clientRecordId = "greenpods:hr:<windowStartEpochSeconds>"`,
`clientRecordVersion = <monotonic flush counter>`. Deterministic in the window rather than
in the session, which is what makes an interrupted session update its record instead of
writing a second one. A partial window is flushed on stop rather than held for a boundary
that may never arrive, and the version rises on every flush so the newest view of a window
wins even when two flushes carry the same number of samples (R-6, FR-019, SC-009).

**AD-10 — The report timestamp is anchored, never used raw.** The report's 8-byte
nanosecond field is an accessory-local monotonic counter — the captured value is about 15
hours, not a date. It is anchored once per session against the host clock and every
reading's wall-clock time is derived from that anchor, so the accessory's exact 1.000 s
spacing is kept while the absolute time comes from a clock that means something. Using it
raw would put every reading fifteen hours into the user's past (R-4a, FR-016, SC-003).

**AD-11 — Health Connect stays inside `core/data`.** The permission request is exposed as
`ActivityResultContract<Set<String>, Set<String>>`, whose type parameters are plain
strings, so `feature/settings` launches it without linking the library. The dependency
stays `implementation`, and no Health Connect type crosses a module boundary.

**AD-8 — Sensing lives with the channel, and says so.** The controller runs inside the
existing `connectedDevice` foreground service; enabling heart rate implies the service is
running and the toggle states that. One notification satisfies FR-013 and Android's
requirement for continuous Bluetooth work at once.

**AD-9 — The two routes meet only at the state.** The GATT source keeps its own path down
to `HeartRateReading(source = GATT)`; nothing merges, substitutes or falls back between
them (FR-004). Presentation is identical (FR-026) because both produce the same state
type. The GATT route has no confidence field, and per R-10 is gated on plausibility alone,
which the UI labels rather than hides.

## Component work

### `core/model`
- `HeartRateReading` replacing `HeartRateSample`: adds confidence, timestamp, sequence;
  rejects implausible BPM at construction.
- `HeartRateState` sealed hierarchy and `HeartRateSensing` counters (data-model.md).
- `PodFeature.HEART_RATE_AAP.isImplemented = true`, explanation rewritten.
- `GreenPodsSettings`: `heartRateEnabled`, `heartRateHealthConnectEnabled`,
  `heartRateIntervalMillis`, `heartRateConfidenceThreshold`, all sanitised.

### `core/bluetooth`
- `HidTransport`: build start/stop frames for a discovered service id and interval; parse
  the `0x17` body into typed events.
- `HidDescriptorParser`: protobuf walk; property-blob key scan; `HidService` list.
- `HidReportDescriptor`: HID short-item walker; report ids; input field offsets; the
  feature report id for the interval.
- `HeartRateReportDecoder`: layout + bytes → `HeartRateReading`, or an unhandled report.
- `AapDecoder`: content dispatch for `0x17`; new events; head tracking by service id.
- `AapTransport`: length-aware reassembly, larger buffer, and heart-rate report bodies
  excluded from the frame log.
- `AapCommands`/`AapSession`: `startHeartRate(serviceId, intervalMicros)` and `stop`.

### `core/data`
- `HeartRateConfidencePolicy` (pure) and `HeartRateBatcher` (pure).
- `HeartRateController`: owns start/stop, wear gating, resume-on-reconnect, the
  never-converged timeout, and publishes `HeartRateState` plus trusted readings.
- `HealthConnectLink`: availability, permission read, batched insert, own-record count and
  delete.
- `PodRepository`/`PodOverlay`: carry `HeartRateState` instead of a sample.

### `feature/*`
- `feature/pods`: the card, with a distinct visual for settling versus measuring versus
  uncertain, and the locked/unsupported forms with their reasons.
- `feature/settings`: the enable toggle carrying the battery sentence *before* it is
  enabled (FR-012), the Health Connect section with its three availability states, the
  permission request, and delete-my-data with the sentence about the health store.

### `app`
- `HealthRationaleActivity` plus the manifest intent filters and activity-alias.
- Manifest: two health permissions, the provider `<queries>` entry.
- `PodMonitorService`: runs the controller; notification text reflects sensing.
- Debug receiver and `StateDump` per [contracts/adb.md](./contracts/adb.md).
- `docs/adb.md` and `docs/protocol-research.md` updated in the same change as the code.

## Test strategy

| Layer | What is tested | How |
|---|---|---|
| Descriptor parsing | Captured frame → four services; renumbered ids still find the sensor; missing name falls back to the entitlement | JUnit, fixture from the 2026-08-04 capture |
| Report descriptor | Captured 126-byte descriptor → the contract's offsets; a descriptor with no confidence field → no usable layout | JUnit |
| Report decoding | The captured BPM/confidence series; short, long and malformed reports; report id 2 → unhandled | JUnit |
| Framing | Split frame reassembles; two frames in one read yield two; declared length beyond the buffer | JUnit |
| Policy | The settling series is untrusted, the converged series trusted; hysteresis around the gate; 0 and 251 BPM implausible | JUnit, table-driven |
| Controller | Never converges → `Unavailable` and stopped; bud out → stop; reconnect → restart; disable → stop frame sent | turbine + fakes |
| Batching | Window alignment; identical ids across an interruption; version rises with sample count | JUnit |
| Health link | Each availability status maps to its sentence; no permission → no write attempt | JUnit with a fake client |
| State/dump | No state but `Measuring` can produce a number; the dump contains no BPM | JUnit |
| Transport-gated behaviour | Every state above with `AAP_L2CAP` live and gated | JUnit, both states |
| On device | [quickstart.md](./quickstart.md) sections 2–7 | adb |

Everything except the last row runs off-device in CI.

## Complexity Tracking

| Tension | Why it is needed | Why the simpler thing was rejected |
|---|---|---|
| FR-023 (no heart rate in diagnostics) against Principle IV (unknown traffic is surfaced, never dropped) | Both are non-negotiable in their own domains. Resolved by recording unknown HID traffic **by shape** — service id, report id, length — and never by body, so nothing is dropped and no value is written down (R-9). | Exempting heart-rate traffic from diagnostics entirely would hide the one channel where new protocol behaviour is now most likely to appear. Logging bodies and redacting later is a leak with a cleanup step. |
| `READ_HEART_RATE` requested by an app whose scope says it does not consume health data | FR-029 requires verifying that what reached the health store is what was measured, and a count is only ours if it can be filtered to our own `DataOrigin`. | Counting every record in the window reports another app's chest strap as our output — which would make the verification claim false rather than merely imprecise. |
| The confidence threshold ships as a constant, not a measurement | No multi-session data exists yet, and the one capture in hand only bounds the value between 21 and 156. | Blocking the feature until calibration data exists trades a stated provisional value for no feature; the constant carries its provenance and an adb override so calibration is a measurement, not a rebuild. |

## Risks

- **The confidence byte is named from behaviour.** It tracked convergence in one capture on
  one firmware. If it turns out to mean something adjacent, the gate is wrong in a way that
  looks correct. Mitigated by carrying it as a vendor usage rather than as a fact, and by
  SC-002's reference-monitor comparison being a precondition for claiming accuracy.
- **The sensor costs battery in the buds and the cost is unmeasured.** SC-006 covers the
  disabled case only. The cadence is a setting so the enabled case can be measured at more
  than one interval before any default is defended.
- **One model, one firmware.** Every fixture comes from AirPods Pro 3 firmware
  `81.2675…`. Service ids and vendor usages on Powerbeats Pro 2's AAP path are unseen —
  which is exactly why discovery is by name.
- **No Powerbeats Pro 2 hardware exists for this project.** US4 is therefore verifiable
  only off-device, against a fake source. It ships as *implemented and unverified on
  hardware*, and says so, rather than as done. This is not a reason to skip it — the code
  path is small and the alternative is leaving a working `HeartRateGattSource` with no
  caller — but it is a reason not to claim SC-003 for that route.
- **Health Connect is a moving provider.** Availability has three states and they are
  handled, but a provider update can change behaviour under an unchanged app. Availability
  is checked before every use rather than cached across sessions.
- **Fixing `0x17` dispatch touches head tracking.** Head gestures currently consume a
  stream decoded at fixed offsets. Re-routing by service id must keep `HeadGestureTest`
  green, and head tracking should be verified on device in the same session as heart rate.
