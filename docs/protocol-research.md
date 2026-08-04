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
| 6 | 8 | Timestamp, nanoseconds, LE |
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

Fully solved and portable. `Opcode.HEAD_TRACKING` (`0x0017`) starts and stops the
stream; samples carry three orientation values and two acceleration values at the
byte offsets in `HeadTrackingSample`. Gated behind L2CAP like all other writes.

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
