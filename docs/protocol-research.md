# Protocol research notes

GreenPods talks to AirPods over protocols Apple has never published. Everything in
`core/bluetooth` is derived from community reverse-engineering, so this file records
where each fact came from and what is still unknown. Treat anything marked
**unverified** as a hypothesis until it is confirmed against a real device.

## Transports

| Transport | Reach | Root needed | What it gives |
|---|---|---|---|
| BLE proximity-pairing advertisement | Every unrooted device | No | Battery, charging, in-ear/in-case, lid counter. Read-only. |
| Standard GATT Heart Rate Profile (`0x180D`) | Every unrooted device | No | BPM — but only from Powerbeats Pro 2. |
| AAP over L2CAP PSM `0x1001` | Recent Android, unrooted | **No** | Everything else: noise control, gestures, CA, head tracking, HRM toggle, rename. |

### How AAP is actually reached

This section said for a long time that the channel needs a patched Bluetooth stack and
therefore root. **That was wrong**, and it was wrong in a way worth recording, because
every symptom pointed at the stack while the real obstacle was somewhere else entirely.

Three things have to be right at once:

1. **The channel must be secure.** `createInsecureL2capChannel` builds an
   unauthenticated, unencrypted channel. AirPods accept the connection and then never
   bring the channel up: the first read returns -1. That is the failure this document
   previously attributed to a stock stack refusing PSM `0x1001`. The channel the
   accessory actually wants is `auth = true, encrypt = true`, carrying Apple's service
   UUID `74ec2172-0bad-4d01-8f77-997b2be0722a` — the same UUID the accessory publishes
   in its SDP record.
2. **No public API constructs that channel.** Only a hidden `BluetoothSocket`
   constructor does. Its signature has moved across releases, so the known forms are
   tried in order; on Android 17 the live one is
   `(BluetoothAdapter, BluetoothDevice, int type, boolean auth, boolean encrypt, int psm, ParcelUuid)`.
3. **That constructor is on the non-SDK blocklist.** This is the real gate. Reflection
   is denied outright, and the denial is silent unless you go looking for it:

   ```
   hiddenapi: Accessing hidden method Landroid/bluetooth/BluetoothSocket;-><init>(…)V
   (runtime_flags=0, domain=platform, api=blocked) from …/AapTransport;
   (domain=app, TargetSdkVersion=37) using reflection: denied
   ```

   `HiddenApiAccess` lifts it with `VMRuntime.setHiddenApiExemptions`, scoped to
   `Landroid/bluetooth/BluetoothSocket;` and `Landroid/bluetooth/BluetoothDevice;`
   rather than the blanket `""`. That is an app-local runtime flag, not a permission
   and not a system patch — every Bluetooth permission is still enforced.

LibrePods reaches the same call through a small JNI library whose strings are
XOR-obfuscated, presumably to survive Play Store static analysis. GreenPods uses
`org.lsposed.hiddenapibypass`, which needs no NDK and is verified working on API 37.

Their `RootlessSupport.isSupported` gates the rootless path to SDK ≥ 37, plus Android 16
on Pixel builds starting `CP1A` and on the Oppo/OnePlus/Realme family — a useful hint at
where the blocklist entry differs, and **unverified** by us on anything but a Pixel 8.

**Consequence for GreenPods:** availability is still *probed at runtime and never
assumed*. Working on one Android build says nothing about the next one moving the
constructor, and the UI still degrades per feature rather than per app.

## Heart rate

Two completely separate paths, and the difference is a firmware decision by Apple:

- **Powerbeats Pro 2** broadcasts the standard Bluetooth SIG Heart Rate Profile.
  Any Android app can read it with ordinary GATT. This is implemented and needs no
  root. Beats is positioned as cross-platform, which is why it kept the open profile.
- **AirPods Pro 3** deliberately does *not* broadcast the standard profile. Its HR
  data is only available inside Apple's ecosystem, which means over AAP.

For the AAP path, `ControlCommand.HRM_STATE` (`0x30`) is known to enable and disable
the sensor. LibrePods declares an `HRM` capability and the toggle, but ships no BPM
decoder, and no public source documents the measurement frame.

### The accessory describes its own heart-rate format

It does not have to be guessed at. On connecting to AirPods Pro 3 (firmware `81.2675…`,
captured 2026-08-04), the buds send several large frames under opcode `0x17` that are
HID service descriptors in plain text, and one of them is the heart-rate service:

```
HeartRateService   HeartRate   com.apple.hid.heartrate-access
HIDServiceAccessEntitlement / HIDDeviceAccessEntitlement
```

followed by a real HID report descriptor. Decoded:

| Bytes | Meaning |
|---|---|
| `05 20` | Usage Page — Sensors |
| `09 16` | Usage — biometric sensor collection |
| `85 01` | Report ID 1 |
| `0A 0E 03` … `75 20 95 01 B1 02` | Feature report: report interval, 32-bit |
| `0A B8 04` `26 FF 00` `75 08 95 01 81 02` | **Input: usage `0x04B8` (Heart Rate), 8 bits, max 255** |
| `26 FF 7F` `0A 21 01` `95 01 75 10 81 02` | Input: 16-bit vendor field |
| `1A 04 01 2A 05 01 81 00` | Input: measurement-confidence style enum |

So BPM is a single byte in a HID input report, and the accessory publishes the layout
itself.

### Heart rate, decoded and streaming — 2026-08-04

**Solved.** Writing the report-interval feature report starts the stream. No workout, no
Apple device, no root. Captured from AirPods Pro 3 on an unrooted Pixel 8.

Opcode `0x17` is not "head tracking"; it is a HID-over-AAP transport carrying several
sensor services, and head tracking is only one of them. Frames are protobuf:

```
04 00 04 00 | 17 00 | 00 00 10 00 | <len u16 LE> | <protobuf>
```

To **start** a service, send field 8 containing the service id, an operation, and a
feature report holding a 32-bit report interval in microseconds:

```
08 78                      seq (arbitrary, echoed back incremented)
42 0B                      field 8, length 11
   08 13                     service id — 0x13 is HeartRateService
   10 02                     operation: set feature report
   1A 05                     field 3, length 5
      01                       report id
      40 42 0F 00              interval, µs, LE — 0x000F4240 = 1 000 000 = 1 Hz
```

Whole frame, 1 Hz heart rate:
`04 00 04 00 17 00 00 00 10 00 0F 00 08 78 42 0B 08 13 10 02 1A 05 01 40 42 0F 00`

**Interval 0 stops the stream.** That is the whole on/off mechanism, and it is the same
one head tracking uses — LibrePods' start/stop packets are this frame with service `0x0E`
and interval `0x9C40` (40 ms, 25 Hz).

**Service ids differ per model.** On AirPods Pro 3 the accessory advertises `0x10`
(devmotion — head tracking), `0x11` (SPL0), `0x12` (HostLibHID), `0x13` (HeartRate).
LibrePods' primary head-tracking packet uses `0x0E`, which this model ignores silently,
which is why they carry an "alternate" packet using `0x10`. **Read the service ids from
the descriptors; do not hard-code them.**

Input reports arrive as field 7:

```
3A 16  08 13  1A 12  <18-byte report>
       service  report
```

The report layout, matching the HID descriptor field for field:

| Offset | Size | Meaning |
|---|---|---|
| 0 | 1 | Report id (`0x01`) |
| 1 | 1 | **Heart rate, BPM** (usage `0x04B8`) |
| 2 | 1 | **Confidence** — low while the sensor settles, ~230-240 once locked |
| 3 | 2 | Sequence counter, LE, +1 per report |
| 5 | 1 | Status enum, observed constant `0x02` |
| 6 | 8 | Timestamp, nanoseconds, LE — an **accessory-local counter, not an epoch**; see the implementation notes below |
| 14 | 4 | Vendor field, observed constant `00 20 00 00` |

A real capture, one report per second, showing why confidence matters:

```
bpm  conf  seq   timestamp_ns
169    20    0   56339327885000
147    20    1   56340327882000   +1.000s
128    20    2   56341327878000   +1.000s
 96    20    3   56342327872000   +1.000s
 94   156    4   56343327870000   +1.000s
 94   205    7   56346327866000
 91   233   12   56351327804000
 81   237   23   56362327729000
```

The first four readings are the optical sensor settling and are **wrong** — 169 BPM from
someone sitting still. Confidence is `20` for exactly those readings and climbs as the
value converges. **Any implementation must gate on the confidence byte**, or it will open
by showing the user a heart rate of 169.

Timestamps are exactly 1.000 s apart, which confirms the interval field controls the rate
rather than merely enabling the sensor.

Still **unverified**: BPM has not been compared against a reference monitor — 81 resting
is plausible and the series behaves correctly, but plausible is not measured. The
confidence and status fields are named from behaviour, not from documentation, and the
constant trailing 4 bytes are unexplained.

`HRM_STATE` (`0x30`) was already `1` on this device throughout, and was never written.
Whether it must be `1` for this to work is **untested**.

### The accessory announces its services, and the id is in the announcement — 2026-08-04

Captured on the Pixel 8 from AirPods Pro 3 (firmware `81.2675…`) by opening the channel
with `gp --es cmd raw` and reading `log.tag.AapTransport DEBUG`. This is the piece that
makes "read the service ids from the descriptors" implementable rather than aspirational.

The `0x17` protobuf body carries, at the top level:

| Field | Wire type | Meaning |
|---|---|---|
| 1 | varint | Sequence, increments per frame |
| 2 | varint | Constant `1` on descriptor frames |
| 5 | bytes, repeated | One **service descriptor** |
| 7 | bytes | Input report — `08 <service id> 1A <len> <report>` |
| 8 | bytes | Start/stop request, host → accessory |
| 12 | bytes, repeated | **Service ready** — `08 <service id>` |

A field-5 descriptor is `08 <service id>` then `12 <len> <blob>`, where the blob is a
serialised Apple property dictionary: `D3 <count>`, then entries of
`<u16 keylen> 00 00 09 <ASCII key> <typed value>`. The four services announced:

| Id | `AccessoryService` | Notable keys |
|---|---|---|
| `0x10` | `devmotion` | SerialNumber, CFG#, PrimaryVendorUsages, ReportDescriptor |
| `0x11` | `SPL0` | MaxReportSize, MaxFIFOSize, ReportDescriptor |
| `0x12` | `HostLibHID` | MultipleInterfaceEnabled, ReportDescriptor |
| `0x13` | `HeartRateService` → `HeartRate` | MaxReportSize 601, HIDServiceAccessEntitlement and HIDDeviceAccessEntitlement both `com.apple.hid.heartrate-access`, ReportDescriptor |

Two frames later the accessory sent the readiness list — field 12 carrying `0x10`, `0x11`,
`0x12`, `0x13`. **The id is data in every one of these places, and a name sits beside it.**
Find the service by `HeartRateService` (or the entitlement string) and read the id from the
protobuf; a model that numbers its services differently then needs no code change.

The heart-rate service's own `ReportDescriptor`, 126 bytes verbatim:

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

It reproduces the 18-byte report layout above field for field, from the accessory rather
than from a diff — which is the confirmation that was missing. It also **corrects two
things written earlier on this page**:

- The item group `1A 04 01 2A 05 01 81 00`, described above as a "measurement-confidence
  style enum", is the **status** field (logical 1..2, usages `0x0104..0x0105`). The
  confidence byte is the *preceding* vendor usage **`0xFF15:0x0120`**, 8 bits. Confidence
  is still a name given from behaviour, but it is now a name given to a specific usage.
- `MaxReportSize` is 601 because the same service declares a **second report, id 2**, of
  600 bytes under vendor usage `0xFF0A:0x13`. Its purpose is unknown and it is not
  decoded. It is real traffic that a heart-rate implementation will meet.

Also learned from the same capture, and it is a framing fact rather than a protocol one:
the descriptor frames were 488, 996, 20 and 28 bytes, and the declared 16-bit length at
offset 10 matched the delivered bytes in all four. The 996-byte frame carried three service
descriptors and sat 28 bytes under `AapTransport`'s 1024-byte read buffer. One more service
crosses it, so frames must be reassembled against the declared length rather than trusted
to arrive whole in a single read.

### What building the decoder taught that reading the capture did not — 2026-08-04

The two entries above are what the wire showed. This is what only writing the decoders
surfaced, and each item is here because it was a wrong assumption first.

**The report timestamp is an accessory-local counter, not an epoch.** The 8-byte
nanosecond field reads `56339327885000` — about 15.65 hours, which is an uptime, not a
date. Nothing in the field's shape says so; it is a plausible-looking 64-bit nanosecond
value, and using it directly puts every reading fifteen hours into the user's past with
no error anywhere to notice. The counter is worth keeping because its spacing is exact
(1.000 s at 1 Hz, better than the host clock's scheduling jitter), so the resolution is to
**anchor it once per session against the host clock and derive every wall-clock time from
that anchor**. The anchor is never persisted: a new session gets a new one, because the
accessory's counter restarts on its own schedule and an anchor carried across sessions is
a silent multi-hour error.

**`0x17` must be dispatched on protobuf field, never on packet length.** GreenPods
previously branched on the length of the frame, which happened to work while head tracking
was the only consumer. Against a real descriptor frame that branch decodes 996 bytes of
service dictionary as a head-pose sample and produces garbage quaternions. Nothing noticed
because head gestures are off by default — which is the general shape of the hazard: a
length-based branch on a multiplexed transport fails silently and looks like noise.
Field 5 is descriptors, field 7 an input report tagged with its service id, field 12 the
readiness list; head tracking is recognised by *its own service id*, not by size.

**Report fields must be resolved by usage, not by offset.** The offsets in the table
above are correct for this firmware and are also *derivable* from the report descriptor
the accessory sends. Asking the layout for usage `0x0400B8` (heart rate) and usage
`0xFF15:0x0120` (confidence) costs nothing and survives a firmware that inserts a field.
Hard-coded offsets would keep parsing after such a change and report the wrong byte as a
heart rate — the failure mode is a plausible number, not an exception.

**A descriptor with no confidence field must yield no usable layout.** This falls out of
resolving by usage and is deliberate: the confidence gate is the only thing standing
between the settling series and the user, so a model whose descriptor lacks that usage has
to report itself unsupported rather than show numbers it cannot vouch for. Degrading to
"show it ungated" would be the one failure mode this feature exists to prevent.

**Report id 2 is real traffic and arrives.** The 600-byte report under vendor usage
`0xFF0A:0x13` is declared by the heart-rate service itself, so any implementation that
subscribes to that service meets it. It is surfaced as unhandled **by shape** — service
id, report id, length — and never by body, which is how "nothing is dropped silently"
and "no heart rate in any diagnostic" both hold at once.

**`HRM_STATE` (`0x30`) is not the on switch, and GreenPods never writes it.** The id table
names it for the heart-rate sensor, and it was `1` throughout the capture without ever
being written. The actual on switch is the report-interval feature report. GreenPods keeps
`0x30` as an *identifier* so diagnostics can name it if an accessory ever sends it, and has
removed the command builder that existed on the assumption it started the sensor — its
bytes had been pinned in a test against a guess rather than a capture, which made an
untested command look verified. Whether `0x30` must be `1` for the stream to start remains
**untested**; on this device it never had to be changed.

### The descriptors are not sent unprompted, and that gates the whole feature — 2026-08-04

Found while verifying heart rate end to end on the Pixel 8. It contradicts what the entry
above assumes, so it is recorded here rather than quietly edited in.

**On a freshly opened channel the accessory sends no `0x17` frames at all.** The handshake
is answered and the full configuration comes back — 30-odd control frames including
`30 01`, so `HRM_STATE` is already 1 without anyone writing it — but no service
descriptors and no readiness list. `HidDescriptorParser` therefore never runs, the
heart-rate service id is never discovered, and `HeartRateController` waits in `STARTING`
indefinitely. That wait is correct behaviour given no service id (FR-002 forbids a
constant), but the id never arrives.

The earlier capture that *did* show descriptors was taken on a channel where **LibrePods
had been running first**. That is the difference, and it means "descriptors arrive
unprompted after opening the channel" was an artefact of the capture conditions rather
than a property of the protocol. Something must ask, and GreenPods does not know the
request.

What is established, by sending the start frame directly with the id from the earlier
capture:

- **The sensor itself is willing with no discovery at all.** Writing the 1 Hz start frame
  for service `0x13` produced heart-rate reports within a second, on a channel that had
  never carried a descriptor.
- **The start is acknowledged on `0x17` field 9.** The reply is
  `04 00 04 00 17 00 00 00 10 00 08 00 08 70 10 03 4A 02 08 13` — sequence `0x70`, field 2
  = 3, then field 9 (`0x4A`) of length 2 carrying `08 13`, the service id. Field 9 was not
  in the field table above; it is the accessory confirming *which* service it started.
- **Reports without a descriptor are undecodable, and correctly so.** They arrive and are
  counted (`reportsReceived` climbed to 23) but every one becomes `UnhandledHidReport` with
  "no usable layout", because the field offsets are resolved from the report descriptor by
  usage. `trustedCount` stayed 0. Nothing was shown, which is the designed outcome — a
  layout guessed from one firmware is exactly what R-2 refuses.

So the remaining unknown is narrow and specific: **the request that makes the accessory
announce its HID services.** It is one frame. Until it is known, the AAP heart-rate route
only completes on a channel some other client has already prompted, which is not a
shippable dependency.

**Ruled out, by reading LibrePods' source (`kavishdevar/librepods`, 2026-08-04):**

- *That LibrePods asks for the descriptors.* It does not. `createStartHeadTrackingPacket`
  hard-codes service `0x0E` and `createAlternateStartHeadTrackingPacket` hard-codes `0x10`,
  with a user-facing "use alternate head tracking packets" preference to switch between
  them — which is what guessing an id looks like when it reaches a settings screen, and
  exactly what FR-002 exists to avoid. Their head-tracking *detector* is also the same
  length-based test (`data[10] == 0x44 || 0x45`) that this project replaced with protobuf
  field dispatch.
- *That claiming the connection prompts them.* LibrePods sends control command
  `OWNS_CONNECTION` (`0x06`) before starting head tracking, so it was the obvious
  candidate. Sending `04 00 04 00 09 00 06 01 00 00 00` on a live channel produced **no
  `0x17` frames and no echo of the command at all** — the accessory ignored it. Consistent
  with LibrePods' own code, which refuses to attempt a takeover unless a Xposed
  `vendor_id_hook` is making the phone report Apple's vendor id; unrooted, this route is
  not available.

**Answered — the descriptors are tied to the ACL link, not to the L2CAP channel.**
Tested by closing the case, waiting for the ACL to drop, then putting a bud back in and
opening the channel the moment the link came up. Five `0x17` frames arrived unprompted
within seconds: two descriptor frames (field 5) covering services `0x10`–`0x13`, two
readiness frames (field 12, `08 10` then `08 11 08 12 08 13`), and one field 9 carrying
`08 13`. Heart-rate input reports followed immediately.

**The announcement is an answer, not a broadcast — and the earlier reading of this was
wrong.** It first looked as though the accessory volunteered its HID services shortly
after the link came up, and that a client merely had to be listening in time. It does not.
A channel opened at the instant of the ACL connection receives the accessory's entire
configuration and *not one word* about its HID services, across every reconnection tested.
Sending a single request on that already-open channel produced ten `0x17` frames
immediately — descriptors, readiness and a field 9 — followed by heart-rate reports.

The earlier captures looked spontaneous only because every one of them was taken by
firing a control command to open the channel; the command that opened it was also the
command that provoked the answer, and that went unnoticed for several sessions.

The same request goes out as part of the handshake, so "just ask" is not the whole story
either: asked at handshake time it is answered with the configuration alone. Asked again
once the channel has settled — a second or so later — it is answered with the services.

So an implementation needs three things, and missing any one of them looks identical from
the outside:

1. **Open the channel on the ACL connection**, not on the first write a feature wants.
   Heart rate's first write is the start command, and that needs the service id the
   announcement carries.
2. **Ask again once the channel is up.** Listening is not enough.
3. **Do not tear the channel down to retry.** A retry loop that reconnects before each
   attempt destroys the decoder session holding the announcement — observed as descriptors
   arriving and every subsequent report being undecodable.

Two details from that capture, now pinned in
`core/bluetooth/src/test/resources/aap/hid-descriptors-live.txt` and
`HidDescriptorLiveCaptureTest`, both of which the hand-transcribed fixture got wrong:

- **The announcement is split across two frames**, not one: 488 bytes carrying `0x10`
  alone, then 996 bytes carrying `0x11`, `0x12` and `0x13` together. Anything that treats
  the first descriptor frame as the complete list finds three of the four services
  missing. The 996-byte frame also sat 28 bytes under the old 1024-byte read buffer, which
  is the concrete reason reassembly against the declared length is not optional.
- **The heart-rate service names itself with a different key.** The other three carry
  `AccessoryService` → `devmotion` / `SPL0` / `HostLibHID`; `0x13` carries
  `HeartRateService` → `HeartRate`. A parser reading only `AccessoryService` therefore sees
  it as unnamed. That is cosmetic and not a discovery failure — the service is found by the
  `HeartRateService` key *or* the `com.apple.hid.heartrate-access` entitlement, and the
  live frame carries both, twice (`HIDServiceAccessEntitlement` and
  `HIDDeviceAccessEntitlement`).

**This does not invalidate the decoders.** Everything downstream of a descriptor is
verified against real hardware in the same session — see the field notes below.

### The workout gate is host policy, not accessory firmware — confirmed

Apple only collects heart rate on AirPods Pro 3 **while a workout is running, or while
the Health app is open**. There is no setting for continuous monitoring; owners report
that the sensor is idle the rest of the time, and Apple's own material describes it as a
workout feature. That is a product decision, not a protocol limitation — the battery
cost of running an optical sensor continuously is the stated reason.

An iOS app called **AirPulse** (App Store id 6760625679, shipped 2026) reads AirPods Pro
3 heart rate continuously in the background, outside any workout. That matters here for
one reason: it demonstrates the buds will stream heart rate whenever a host asks
properly. The restriction lives in whatever iOS requires an app to hold open, not in a
mode the accessory refuses to enter. There is no evidence of a "start workout" command
on the wire, and looking for one is probably the wrong search — the HID contract in the
descriptor above (write the report interval, receive input reports) is the mechanism
that a host-side subscription would ultimately drive.

That prediction was tested the same day and held. Writing the report-interval feature
report produces heart-rate reports immediately, with no workout running and no Apple
device involved — see the decode above. **There is no "start workout" command, and
looking for one would have been the wrong search.** The accessory streams to any host
that asks in the ordinary HID way; iOS simply chooses to ask only during a workout or
while the Health app is open.

AirPulse itself remains closed-source and unexamined; it is cited here only as the
observation that prompted the right question.

Two consequences, and they change what "implement heart rate" even means:

1. **A passive listener will see nothing.** Opening the AAP channel and waiting is not
   a test of anything. The sensor has to be told to start, and `HRM_STATE` (`0x30`) is
   the only known candidate for saying so. Whether that command alone starts the stream,
   or whether the accessory additionally expects a workout/session context to be
   declared, is **unverified** — and it is now the first question to answer, ahead of
   decoding the frame.
2. **Captures must be taken during a workout.** A PacketLogger session recorded while
   sitting still will contain no heart-rate frames at all, however long it runs. Start a
   Fitness workout on the iPhone first — see the capture recipe below.

GreenPods ships `AapCommands.heartRateSensor(enabled)` for the toggle and nothing else.
Sending a command whose reply cannot be read would be a button that does nothing, so it
is not offered in the UI until there is a decoder to pair it with.

### How to close that gap

This is a capture-and-correlate job, the same method that produced every other packet
definition in these notes:

1. Install Apple's Bluetooth logging profile on an iPhone, tether it to a Mac, and
   capture HCI traffic with **PacketLogger** (Additional Tools for Xcode). macOS alone
   is not enough — it does not drive the HR sensor; an iPhone workout session does.
2. Start a workout in Fitness so the buds begin streaming heart rate.
3. Record ground-truth BPM simultaneously from a chest strap.
4. Diff the AAP frames against the BPM series and find the field that tracks it.
   Likely candidates are a new opcode or a sensor frame under `0x0017`, which the
   opcode table already describes as carrying "multiple things".

Nothing about this is blocked — it simply has not been done yet.

## Head tracking

**Started and stopped, decoded only in part.** `Opcode.HEAD_TRACKING` (`0x0017`) carries
the devmotion service; interval `0x9C40` (40 ms, 25 Hz) starts it and interval 0 stops it,
the same mechanism heart rate uses. Gated behind L2CAP like all other writes.

What arrives is an input report on service `0x10`, 58 bytes, report id 1, at 25 Hz —
measured on AirPods Pro 3, 2026-08-05.

### The report offsets were read from the wrong place — fixed 2026-08-05

The decoder used to read the pose at **absolute packet offsets** 43/45/47. Those offsets
do not hold still. The `0x17` body is protobuf, field 1 is a sequence counter, and a
varint takes one byte up to 127 and two from 128 — so the input report, and everything
else after that field, shifts a byte partway through every stream. At 25 Hz that is about
five seconds in.

Measured, on one capture:

| Sequence | Report starts at packet offset |
|---|---|
| 16 – 127 | 22 |
| 128 – 316 | 23 |

The boundary lands exactly at 127 → 128. So the shipped decoder read one pair of bytes
for the first few seconds of a stream and a different, one-byte-shifted pair thereafter,
for the same physical pose — and half of those reads straddled two adjacent values, which
is where an impossible 195° of tilt came from.

The fix is to read from the input report the protobuf delimits, not from the packet.
Pinned by `HeadTrackingOffsetTest` against four consecutive captured frames spanning the
boundary, in `head-tracking-varint-boundary.txt`.

### The scale is still not derived, and cannot be read off the descriptor

The devmotion descriptor (96 bytes, service `0x10`, name `devmotion6`) declares report 1
as an 8-byte timestamp on usage `FF15:0004` — the same timestamp usage the heart-rate
service uses — followed by an **opaque vendor blob** on usage page `FF0C`. It breaks out
no orientation fields, so there is no Logical/Physical range and no unit to read: the
accessory does not say what its motion numbers mean.

`HidReportDescriptor` now parses Logical/Physical Minimum and Maximum and the Unit
Exponent, so a descriptor that *does* declare a scale can be honoured. This one does not.

What is known about the three values at report offsets 20/22/24 is empirical:

- They are signed 16-bit, little-endian, and they move with the head.
- They are where the decoder already read for most of a stream, so this is not a new
  guess replacing an old one — it is the same bytes, read where they actually are.
- **They are cross-coupled.** Over a scripted capture — nod, then shake, then tilt,
  separated by stillness — nodding moved offsets 24 and 22; shaking moved 22 most; tilting
  moved 24 and 20. No offset belongs to one axis.

That last point is why `HeadPoseMapper.SCALE` cannot be repaired by choosing a better
constant: a per-axis linear scale is the wrong model for values that do not vary
independently. A quaternion was the obvious hypothesis and it was tested — four
consecutive int16 at offsets 26/28/30/32 do hold a near-constant norm (0.9695, spread
0.18%), but converting them to Euler angles does not make nodding move pitch or shaking
move yaw, so that is not the head's orientation either.

Everything the app shows in degrees, and every gesture threshold, therefore still rests on
a number known to be wrong. `specs/004-head-tracking-calibration` is the way out and is
still unbuilt.

Deriving offsets 43/45/47 was listed as an open question; it is now closed as *the
question was malformed* — they were packet offsets for a report whose position moves.

## Settings that persist in the accessory

Some configuration lives in the buds' own firmware and survives a host switch. That
makes "provision on an Apple device, consume on Android" a legitimate workaround for
features Android cannot write while the L2CAP transport is unavailable:

- Stem long-press action and the set of listening modes it cycles through
- Conversational Awareness on/off
- Ear detection on/off

Caveat: these are *overwritten* whenever the buds connect to an Apple device that is
not iCloud-synced with the one that set them, so the configuration has to be re-applied.

This trick cannot create capability that is not in firmware — it will never make an
AirPods Pro 3 broadcast a standard heart-rate profile.

## Not reachable at all

These depend on Apple-account or ecosystem services that no third-party client can
join, and are explicitly out of scope:

- Find My network participation
- Automatic device switching across Apple devices
- "Hey Siri" voice trigger (the trigger byte can be set; the assistant cannot be invoked)
- Audio sharing between two sets of AirPods

## Field notes

Things learned by running GreenPods on real hardware, as opposed to from captures.

### The clock was never stopped — the diagnosis above was wrong — 2026-08-06

**Corrected the same day, by measuring instead of reasoning.**

The note that stood here concluded that `HeartRateController`'s ticks were starved,
because a thirty-second timeout had not fired in two minutes. It reasoned from a comment
already in the file describing exactly that failure, and it was wrong.

A counter on the tick branch — `ticks=` in `hr status` — settled it in one run:

```
[23s] ticks=19  state=MEASURING service=0x13 reports=17
[46s] ticks=42  state=MEASURING service=0x13 reports=40
[92s] ticks=90  state=MEASURING service=0x13 reports=88
```

Ticks arrive at one a second and the session converges. The clock runs.

What actually differed between the two observations was the **service announcement**. In
the failing run the app had just been reinstalled, which cleared `HidServiceMemory`, and
the Bluetooth link predated the install — so the announcement had already happened and
could not be asked for again. `service=none` was the accessory never having described
itself to *this install*, not a stalled collector. Re-seating the earbuds gave a fresh
link, the announcement arrived, and everything worked.

The thirty-second no-convergence timeout not firing in that state remains **unexplained**
and is worth returning to. It is now a narrow question — a session with no service id —
rather than a claim about the controller's clock.

**Why this is recorded rather than quietly deleted.** The previous note asserted a
mechanism from a plausible chain of reasoning and one negative observation, and stated
that a shipped fix was inert in production. That claim reached a merged PR. The tick
counter cost one build. Principle V's harder direction is exactly this: an absence — a
timeout that did not fire — is not evidence of the mechanism you happen to have a comment
about.

### The withdrawal of a stale reading, verified — 2026-08-06

The v0.5.0 fix, confirmed on hardware once a session could reach `MEASURING`:

```
before          ticks=131 state=MEASURING  reports=129
stop frame sent to the accessory, controller not told
+11s            ticks=139 state=UNCERTAIN  reports=133
+33s            ticks=166 state=UNCERTAIN  reports=133
```

The number is withdrawn about four seconds after reports stop, the session stays up, and
the count stays frozen — the sensor really had stopped. On screen the card reads "Reading
uncertain" with no BPM.

That last check also found a copy bug worth the trip: the uncertain state said "the earbuds
report low confidence" on **both** paths, and on this one the earbuds had said nothing at
all. `Uncertain` now carries its cause and the screen states the true one.

### A foreground-service notification does get promoted — 2026-08-06

The single assumption the live-activity feature rested on, settled on hardware. Pixel 8,
Android 17 (API 37).

Two things were genuinely unknown and both came out in favour:

**A foreground-service notification is promotable.** The documentation says it can be if it
qualifies, which is not the same as saying it will. `dumpsys notification` on the running
service:

```
flags=ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE|PROMOTED_ONGOING
originalFlags=ONGOING_EVENT|FOREGROUND_SERVICE|PROMOTED_ONGOING
android.template=android.app.Notification$BigTextStyle
android.requestPromotedOngoing=Boolean (true)
```

`PROMOTED_ONGOING` is set by the system, not by us — `requestPromotedOngoing` is the
request and the flag is the answer. It also appears as a chip on the lock screen.

**`BigTextStyle` qualifies.** Two Android documentation pages disagree about the permitted
styles — one lists standard, `BigTextStyle`, `CallStyle`, `ProgressStyle` and `MetricStyle`,
another only the middle three. `hasPromotableCharacteristics()` returned true, so the
question is answered for this style on this platform. The check stays in the code regardless:
it is what turns a future refusal into a stated reason rather than a feature that quietly
stops working.

Also confirmed, from the notification's own text: `Left 80% · Right 90% · Case —`. An
unreported case renders as a dash, not `0%`.

**A separate defect found while verifying it.** `gp --es cmd set` answered `set foo=bar` for
*any* key and silently changed nothing when the key had no branch — indistinguishable from
success. It had cost exactly that: `liveActivityEnabled=off` was reported as applied while
the surface carried on posting. Unknown keys now say so and list the known ones, and the
list is cross-checked against the branches.

### Remembered services never reached the features that needed them — 2026-08-05

Heart rate sat in `STARTING` with `service=none` indefinitely on a phone where the very
same channel could list the heart-rate service on demand. Three minutes of polling, no
state change, `reportsReceived: 0`.

The accessory announces its HID services **once per Bluetooth link**, in answer to the
first request after the link comes up. `HidServiceMemory` exists precisely because of
that, and `AapControlGateway` seeded the decoder from it on every channel open — but
silently. Every consumer learns which service is which from `AapEvent.HidServices` and
from nothing else, so seeding the decoder without emitting that event left the
heart-rate controller waiting for an announcement that a remembered channel has no
reason to make.

The failure is invisible from either side. The decoder knows the service. The controller
does not. Nothing errors, and `hid` reports the service correctly the whole time — which
is what makes it worth writing down: "the app knows X" and "the part of the app that
needs X knows X" are different claims, and remembering something is only discovery if
you also say it out loud.

Fixed by emitting `AapEvent.HidServices` for restored services, exactly as a live
announcement would. Verified on hardware the same day: `state=MEASURING service=0x13
interval=1000ms reports=116 trusted=112 discarded=0`, steady at 1 Hz.

**Still open, found while verifying that:** `hr off` answers `disabled` and reports
genuinely cease, but the published state stays `MEASURING enabled=true` with
`lastStop=none`. The sensor stops; the state lies about it. Not diagnosed.

### Pixel 8, AirPods Pro 3 — heart rate end to end — 2026-08-04

The first run of the whole feature against hardware rather than fixtures. Three findings,
in descending order of how much they cost to learn.

**Only one app may hold PSM `0x1001` at a time, and the loser gets no error.** LibrePods
was installed and running on the same phone. GreenPods' socket connected — `mPort=4097`,
`connect(), socket connected` — the handshake wrote without exception, and then
`socket EOF, returning -1` about six seconds later with nothing ever received. No
`IOException`, so no diagnostic; the read loop simply ended. From inside the app this is
indistinguishable from an accessory that has nothing to say. Force-stopping the other
client made the same code work immediately. Anyone diagnosing a silent channel should
check for a second AAP client **before** suspecting the transport.

**The decoders are correct against a live accessory.** With a channel that had descriptors
on it, service `0x13` was discovered from the accessory's own announcement rather than
assumed, the start frame went out, reports arrived at 1.00 s intervals, and the session
reached `MEASURING` with 89 of 93 reports trusted and none discarded as implausible. The
stop frame was byte-identical to the contract —
`04 00 04 00 17 00 00 00 10 00 0F 00 08 78 42 0B 08 13 10 02 1A 05 01 00 00 00 00` — and
reports genuinely ceased in the earbuds rather than merely stopping on screen (FR-014).
Heart-rate report bodies were withheld from the frame log throughout, appearing as
`rx 40 bytes, HID input report (body withheld)` (FR-023, R-9).

**The accessory identity has to survive rotation, and must not swallow its neighbours.**
Everything downstream of an advertisement was keyed by the advertised address, which
rotates on every reconnection, so the overlay, the open-channel record and the heart-rate
session were orphaned each time the buds came out of the case. Resolving to the bonded
classic address fixes that — but `BondedPodResolver` answers "the one paired Apple
accessory" for *any* Apple advertisement, which is right for its own question and ruinous
as an identity: applied to every sighting it merged every Apple device in the room into
one pod whose model changed with whichever beacon landed last, and AirPods Pro 3 then
reported heart rate as *unsupported*. The identity is now taken only when the model
already filed under that key agrees, which absorbs rotation for the paired accessory and
leaves strangers alone.

**A blocking health-store call can wedge the entire feature.** `HeartRateController` runs
its state machine on one collector. `stop()` awaited the trusted-reading sink *before*
publishing the new state, and on this device that call did not return: the stop frame went
out, the state was never published, and no later input was ever processed. The screen
stayed on `MEASURING` with the sensor off, and heart rate could not be re-enabled without
restarting the app — `hr on` produced no start frame at all. Health-store work is now
queued to a separate consumer and never awaited by the state machine, which is what
`HealthConnectLink`'s own rule — the health store is an output of this feature, not a
dependency of it — requires of the caller. Verified fixed on the same hardware: after the
change, `hr off` lands on `state=OFF enabled=false lastStop=disabled`.

### Samsung Galaxy S20 FE (SM-G780F), Android 13 / API 33 — 2026-08-03

- **One scan registration per app, enforced.** A second `BluetoothLeScanner.startScan`
  from the same process fails with `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`
  (code 2) rather than being merged with the first. GreenPods has three independent
  consumers of the sighting stream — the device list, the ear-detection controller and
  the monitoring service — so `PodRepository.pods` is shared rather than cold. A cold
  flow silently leaves two of the three consumers with no data at all, which looks
  exactly like "AirPods not detected".
  With the shared flow, a clean start registers exactly one scanner and survives
  backgrounding and returning. The one case that still trips code 2 is installing a
  new build *over the running process* — the outgoing process has not released its
  registration yet. It clears on the next subscription, so the app recovers by itself
  when the screen is left and re-entered.
- **The advertisement filter is accepted as written.** Company id `0x004C`, first
  payload byte `0x07`, mask `0xFF` registers and matches; the stack reports it as
  `BluetoothLeScanFilter[ ManufacturerId=4c ManufacturerData=07 ManufacturerDataMask=FF ]`.
  No need to filter in the app.
- **`neverForLocation` works.** Scanning proceeds on API 33 with `BLUETOOTH_SCAN` only —
  no location permission requested and none needed.
- **AAP not exercised.** No AirPods were paired to this device, so the L2CAP path could
  not be reached at all. That is itself a case worth handling: the gate reports "these
  AirPods are not paired with this phone" rather than blaming the Bluetooth stack for a
  refusal that never happened. Whether this stack would refuse PSM `0x1001` with buds
  present remains **unverified**.

### Pixel 8 (shiba), Android 17 / API 37, unrooted, AirPods Pro 3 paired and connected — 2026-08-04

The first run against real hardware, and it moved three things from theory to fact.

- **The advertised address is never the paired address.** The buds advertise from a
  resolvable private address that rotates — observed changing between
  `59:66:D4:DD:DB:E6`, `62:CC:F7:AC:6F:E7` and `79:F8:27:66:05:F5` within minutes —
  while the bond sits on the classic address the system shows in Bluetooth settings.
  Looking the advertised address up among bonded devices therefore finds nothing, on
  every phone, always. `BondedPodResolver` correlates the two the only way an
  unprivileged app can: by noticing there is exactly one paired Apple audio accessory.
  With more than one, the ambiguity is reported rather than guessed at.
- **The public API no longer rejects PSM 0x1001.** These notes previously said
  `createInsecureL2capChannel` validates the PSM into `0x0001..0x00FF`. On Android 17
  that call *succeeded* — the reflective fallback was never reached, and the failure
  came later, from the connect: `read failed, socket might closed or timeout, read
  ret: -1`.
- **The conclusion drawn from that was wrong.** It was read as "the stack will not carry
  this channel without root". The actual cause was that an *insecure* channel is the
  wrong request; see the next entry. Recorded here because the reasoning is the trap:
  every observable fact was consistent with a refusing stack, and none of them
  distinguished it from asking incorrectly.

Also confirmed on this device:

- Model detection from the advertisement is *more precise than the system's*: Android
  knows the accessory only as "AirPods Pro", while the proximity payload identifies it
  as AirPods Pro 3 (`0x2720`).
- **The case reports its charge only when it has reason to.** With both buds in the
  ears and the case closed, the case nibble is the `0x0F` unknown sentinel on every
  advertisement — polled repeatedly, never a value — while the per-bud levels update
  live (left drifted 70 % → 60 % during testing). The UI shows a dash and "open the
  lid" rather than inventing a number. This is the protocol behaving as designed, and
  it is the single most common thing mistaken for a bug.
- Auto-pause and auto-resume were driven end to end over adb against Spotify:
  `PLAYING → PAUSED` on a bud leaving an ear, `PAUSED → PLAYING` on it going back in.
- `AudioManager.isMusicActive` **lags a pause**. It keeps reporting audio as active for
  a moment afterwards, and since advertisements arrive every couple of seconds, a naive
  "anything playing means nothing is paused" check hands ownership of our own pause away
  within milliseconds of making it — after which auto-resume correctly refuses to resume
  a pause it no longer owns, and silently never fires. A short settle window after our
  own pause is what makes the two rules coexist.

### Pixel 8 (shiba), Android 17 / API 37, unrooted — AAP channel open — 2026-08-04

The same phone, later the same day, with the secure-channel and non-SDK fixes in place.
The channel opened and stayed open.

- **Reads work.** On connecting, the accessory volunteers its whole state without being
  asked: device information (`0x1D`) with model `A3063`, separate left and right serial
  numbers and firmware `81.2675000075000000.6877`; a capability list (`0x02`); around
  twenty control commands (`0x09`) carrying current values — listening mode, click
  intervals, volume-swipe settings, call management, sleep detection, `0x2E = 0x64`
  (adaptive strength 100); a headphone-accommodation block (`0x53`); and the HID service
  descriptors under `0x17` described above.
- **Writes work.** `09 00 0D 03` set Transparency and `09 00 0D 02` set Noise
  Cancellation; both came back as control updates from the accessory, and
  `PodState.noiseControlMode` followed. Adaptive strength and Conversational Awareness
  read back as `100` and `false` from the device rather than from our own assumptions.
- **A probe is not a session.** Opening a socket succeeded well before any traffic
  flowed. Two separate defects hid behind that: writes raced the cold flow that creates
  the socket (fixed with `AapSession.awaitReady`), and the control gateway built a
  transport with no `BluetoothAdapter`, which on this Android skips the only constructor
  that exists. Both produced "accepted, nothing happened", which is the failure mode to
  expect from this transport and the reason the adb surface reports what the *accessory*
  says rather than what was sent.
- **The name over AAP differs from the advertisement.** The accessory calls itself
  "AirPods Pro"; the proximity payload identifies the model as AirPods Pro 3 (`0x2720`).
  Neither is wrong — one is the user-set name, the other is the model.

### Which bud is which — the advertisement says, the AAP payload does not — 2026-08-05

The proximity advertisement describes a **primary** bud and a **secondary** one, and
carries a flag (`0x20` in the status byte) saying which physical side the primary is.
Either bud can be primary, and which one it is changes — putting one away is enough.

`AppleBeaconDecoder` applied that flag to the battery nibbles and **not** to the in-ear
bits, so with the right bud primary the app reported "Left, in ear" about a bud lying on
a table. Found by wearing one bud and reading the screen. Both now resolve through the
same flag, and `EarDetectionState` is keyed by side rather than by role so a caller
cannot reintroduce it.

**Settled — and neither way round was right. 2026-08-05.** The AAP ear-detection payload
(`04 00 04 00 06 00 xx yy`) carries two bytes and *neither* a component id nor a primary
flag — unlike AAP battery, which labels its components explicitly. It was read in the same
order the advertisement uses, on the reasoning that the swap was the only thing that could
be wrong. The measurement says otherwise.

Pixel 8 (shiba), Android 17, AirPods Pro 3, live channel held by the monitoring session.
Each bud taken out and put back, twice for the left and once for the right:

| Time | Frame | What was physically true |
|---|---|---|
| 12:43:44 | `… 06 00 00 01` | left out of the ear |
| 12:43:46 | `… 06 00 00 00` | left back in |
| 12:43:49 | `… 06 00 00 01` | left out again |
| 12:43:55 | `… 06 00 00 00` | left back in |
| 12:58:21 | `… 06 00 00 00` | both in — baseline |
| 12:58:57 | `… 06 00 00 01` | **right** out of the ear |
| 12:59:04 | `… 06 00 00 00` | right back in |

`payload[1]` moves for **either** bud, with `payload[0]` at `0x00` throughout. That alone
rules out both left-then-right and right-then-left: a pair where either bud drives the same
byte cannot be a pair of sides, and swapping them would have moved the fault rather than
fixed it.

**And the order is not even fixed.** A third capture, taken while verifying the fix, went
further than the first two — it contradicted the conclusion they had suggested:

| Time | Frame | What was physically true |
|---|---|---|
| 13:10:28 | `… 06 00 01 00` | left bud out of the ear |
| 13:10:38 | `… 06 00 00 01` | left bud **still** out, untouched |

Ten seconds apart, the same physical state, encoded the other way round — and `payload[0]`,
written up moments earlier as a byte that "never moved", had moved. Nothing was touched
between the two frames; the app's own dump reported `left: OUT_OF_EAR` across both.

The reading that fits all three captures is that the two bytes are the **primary** and
**secondary** bud, and the role passes between them — a bud leaving an ear being exactly
the moment it would.

**Confirmed, with the advertisement alongside. 2026-08-05, later the same day.** The
measurement the paragraph above asked for: each bud removed in turn while the app's dump
was polled, so the side is known independently of the frame.

| Advertisement says | AAP frame |
|---|---|
| `left: OUT_OF_EAR, right: IN_EAR` | `01 00`, then `00 01` |
| `left: IN_EAR, right: OUT_OF_EAR` | `01 00`, then `00 01` |

Two full alternating cycles, 21 frames, show one pattern and no exceptions:

```
00 00   both in the ears
01 00   a bud comes out — either bud
00 01   ~0.9 s later, untouched
00 01   held
00 00   put back
```

The flip is not drift and not a race: it happened after **every** removal, on both sides,
0.85–0.95 s later. That is a role handover with a settling time, and it fits the frame
exactly — the bud that leaves is still primary at the instant it leaves (`01 00`, primary
out), the role then passes to the bud still in an ear, and the removed bud is thereafter
the secondary one (`00 01`).

So: **`payload[0]` is the primary bud's wear and `payload[1]` the secondary's**, and which
bud holds which role changes about a second after a bud is removed. Neither position is
bound to a side, and no amount of relabelling would bind it.

This also explains why the earlier captures disagreed: they sampled at different points
either side of that ~0.9 s handover. A single frame taken shortly after a removal and a
single frame taken a few seconds later encode the same physical state oppositely, which is
precisely what the 13:10:28 / 13:10:38 pair showed.

**A note on how this was nearly recorded wrong.** The first two captures agreed, and the
conclusion drawn from them — "the second byte is the wear state, the first is unused" — was
written into a decoder, a test and this file before the third capture existed. It was
falsified twenty minutes later by ordinary use. Two agreeing observations of a role-ordered
field look exactly like one fixed field; only a role change tells them apart, and nothing in
the first two captures caused one.

**Fixed.** `AapDecoder.decodeEarDetection` used to emit a per-side `EarDetectionState` from
these two bytes, and `PodOverlay` folded it into pod state — where it overwrote the
advertisement's side-resolved reading, so taking out the left bud made the app say the right
one was out. This was the same defect `e3ac1ed` fixed on the advertisement path — reporting
by role instead of by side — surviving on the AAP path, and it could not be fixed there by
relabelling, because the side is not in the frame.

The decoder now reports both states and attributes neither, and the merge point no longer
lets them become per-side state; per-side wear comes from the advertisement alone, which
carries the primary flag. Verified end to end on 2026-08-05: live channel held open,
left bud removed, dump reports `wear: {left: OUT_OF_EAR, right: IN_EAR}`.

Still worth having, and not built: the channel knows about a wear change immediately, while
the advertisement arrives every couple of seconds. Using the frame as a prompt to trust the
next advertisement sooner would keep that latency without inventing a side. It needs a way
to say "something changed, ask again", which does not exist yet.

## Sources

- LibrePods protocol notes — `docs/AAP Definitions.md`, `opcodes.md`,
  `control_commands.md`. Captured against AirPods Pro 2 firmware 7A305 and the
  iOS 19.1 beta Bluetooth stack. <https://github.com/librepods-org/librepods>
- LibrePods Android sources for the rootless L2CAP route: `BluetoothConnectionManager.kt`
  (the constructor signature list) and `cpp/bluetooth_socket.cpp` (the non-SDK
  exemption). The README's feature table is about the app; these two files are about
  what is actually possible.
- tyalie's AAP protocol definition — the earliest public write-up, a Kaitai grammar and
  raw captures, and the source of the fact that proximity/encryption keys can be
  requested straight after the connect handshake.
  <https://github.com/tyalie/AAP-Protocol-Defintion>
- rithvikvibhu's hearing-aid notes — audiogram and transparency packet layouts, IEEE-754
  float fields. <https://gist.github.com/rithvikvibhu/45e24bbe5ade30125f152383daf07016>
- CAPod device registry, used to cross-check every model id in `PodModel`.
  <https://github.com/d4rken-org/capod>
- DC Rainmaker's teardown of heart-rate behaviour on AirPods Pro 3 vs Powerbeats
  Pro 2, which is where the standard-profile difference is documented.
  <https://www.dcrainmaker.com/2025/09/airpods-pro-3-in-depth-sports-fitness-review.html>
