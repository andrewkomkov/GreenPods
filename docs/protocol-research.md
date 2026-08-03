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

Android's public `BluetoothDevice.createInsecureL2capChannel` validates that the PSM
is in `0x0001..0x00FF`, so `0x1001` is rejected outright. The hidden
`createInsecureL2capSocket` gets past that check but real AirPods then refuse the
channel with *"Peer does not support our desired channel types"*, because the stack
negotiates a channel mode the buds do not accept.

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

## Sources

- LibrePods protocol notes — `docs/AAP Definitions.md`, `opcodes.md`,
  `control_commands.md`. Captured against AirPods Pro 2 firmware 7A305 and the
  iOS 19.1 beta Bluetooth stack. <https://github.com/librepods-org/librepods>
- CAPod device registry, used to cross-check every model id in `PodModel`.
  <https://github.com/d4rken-org/capod>
- DC Rainmaker's teardown of heart-rate behaviour on AirPods Pro 3 vs Powerbeats
  Pro 2, which is where the standard-profile difference is documented.
  <https://www.dcrainmaker.com/2025/09/airpods-pro-3-in-depth-sports-fitness-review.html>
