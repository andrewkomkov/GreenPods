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
| AAP over L2CAP PSM `0x1001` | Permissive Bluetooth stacks only | Usually yes | Everything else: noise control, gestures, CA, head tracking, HRM toggle, rename. |

### Why AAP is gated

Historically, Android's public `BluetoothDevice.createInsecureL2capChannel` validated
that the PSM is in `0x0001..0x00FF`, so `0x1001` was rejected outright; the hidden
`createInsecureL2capSocket` got past that check, and real AirPods then refused the
channel with *"Peer does not support our desired channel types"*, because the stack
negotiates a channel mode the buds do not accept.

On Android 17 the first half of that no longer holds — the public call accepts PSM
`0x1001` and the connect fails afterwards instead. See the Pixel 8 field notes below.
The outcome is unchanged; only the symptom moved.

LibrePods works around this with a Magisk module (`btl2capfix.zip`) that patches
`libbluetooth_jni.so`, or with an Xposed hook. Both require root or LSPosed.

CAPod — the most mature unrooted app — has never shipped noise-control writing for
exactly this reason.

**Consequence for GreenPods:** L2CAP availability is *probed at runtime and never
assumed*. The UI degrades per feature, not per app. See `AapTransport.probe()`.

## Heart rate

Two completely separate paths, and the difference is a firmware decision by Apple:

- **Powerbeats Pro 2** broadcasts the standard Bluetooth SIG Heart Rate Profile.
  Any Android app can read it with ordinary GATT. This is implemented and needs no
  root. Beats is positioned as cross-platform, which is why it kept the open profile.
- **AirPods Pro 3** deliberately does *not* broadcast the standard profile. Its HR
  data is only available inside Apple's ecosystem, which means over AAP.

For the AAP path, `ControlCommand.HRM_STATE` (`0x30`) is known to enable and disable
the sensor. **The measurement frame layout is not publicly documented.** LibrePods
declares an `HRM` capability and the toggle, but ships no BPM decoder.

### The sensor is gated on a workout, not just on the toggle

Apple only collects heart rate on AirPods Pro 3 **while a workout is running, or while
the Health app is open**. There is no setting for continuous monitoring; owners report
that the sensor is simply idle the rest of the time, and Apple's own material describes
it as a workout feature. That is a product decision, not a protocol limitation — the
battery cost of running an optical sensor continuously is the stated reason.

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
  ret: -1`. So on current Android the blocker has moved: the socket is created and the
  channel simply never comes up. Whether the range check was relaxed or moved is
  **unverified**; what is certain is that a PSM rejection is no longer the symptom to
  look for. The route actually taken is now reported in the failure text.
- **The accessory being paired, connected and playing audio changes nothing.** All of
  that was true during this test. The Apple protocol channel still did not establish,
  which is consistent with everything above: without a patched stack it does not matter
  how healthy the ordinary Bluetooth connection is.

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

## Sources

- LibrePods protocol notes — `docs/AAP Definitions.md`, `opcodes.md`,
  `control_commands.md`. Captured against AirPods Pro 2 firmware 7A305 and the
  iOS 19.1 beta Bluetooth stack. <https://github.com/librepods-org/librepods>
- CAPod device registry, used to cross-check every model id in `PodModel`.
  <https://github.com/d4rken-org/capod>
- DC Rainmaker's teardown of heart-rate behaviour on AirPods Pro 3 vs Powerbeats
  Pro 2, which is where the standard-profile difference is documented.
  <https://www.dcrainmaker.com/2025/09/airpods-pro-3-in-depth-sports-fitness-review.html>
