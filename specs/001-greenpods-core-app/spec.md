# Feature Specification: GreenPods core application

**Feature Branch**: `001-greenpods-core-app`

**Created**: 2026-08-03

**Status**: Draft

**Input**: User description: "Full GreenPods application: transport-gated AirPods features on unrooted Android"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See my AirPods without pairing anything (Priority: P1)

Someone opens their AirPods case next to an Android phone and immediately sees which
model it is, how charged each bud and the case are, whether each is charging, and
whether the buds are in their ears or in the case. They did not pair, connect, sign in,
or grant a location permission.

**Why this priority**: This is the only thing guaranteed to work on every unrooted
device, and it is the reason the app exists. If nothing else shipped, this alone is a
usable product.

**Independent Test**: Feed captured proximity-pairing advertisements into the scan
pipeline and assert the rendered card. On a device, open a case nearby and watch the
card appear within one advertisement interval.

**Acceptance Scenarios**:

1. **Given** Bluetooth is on and scan permission granted, **When** a known Apple
   accessory advertises nearby, **Then** a card appears naming the model with per-bud
   and case battery levels within 5 seconds.
2. **Given** a bud is taken out of the ear, **When** the next advertisement arrives,
   **Then** its wear state changes from "in ear" to "out of ear".
3. **Given** an accessory stops advertising, **When** 30 seconds pass, **Then** its
   card disappears rather than showing frozen values.
4. **Given** an advertisement reports the unknown-battery sentinel, **When** it is
   decoded, **Then** the UI shows "—" rather than 0 %.
5. **Given** no accessory is nearby, **When** the screen is shown, **Then** an empty
   state explains that the case should be opened near the phone.

---

### User Story 2 - Understand what this phone can and cannot do (Priority: P1)

A user wonders why there is no noise-control button. The app tells them: this phone's
Bluetooth stack refuses the L2CAP channel AirPods use for settings; battery and ear
detection still work. Every hardware feature their model has is listed, each either
usable or locked with the specific reason.

**Why this priority**: Without this, a gated feature is indistinguishable from a broken
app. It is the difference between a bug report and an understood limitation.

**Independent Test**: Force each `AapAvailability` outcome and assert the diagnostics
text and the locked/unlocked state of each capability chip.

**Acceptance Scenarios**:

1. **Given** the AAP transport is unavailable, **When** the controls screen is opened,
   **Then** every write control is disabled and an inline explanation names the reason.
2. **Given** a model with head tracking but no AAP transport, **When** capabilities are
   listed, **Then** head tracking appears as locked, not hidden.
3. **Given** the phone runs API < 29, **When** the transport is probed, **Then** the
   reason reported is that the L2CAP API does not exist on this Android version.
4. **Given** a probe has never run, **When** diagnostics are opened, **Then** the state
   reads "not probed" rather than claiming unavailability.
5. **Given** the AAP transport becomes available, **When** capabilities are recomputed,
   **Then** previously locked features become usable without restarting the app.

---

### User Story 3 - Music pauses when I take a bud out (Priority: P2)

A user pulls one AirPod out; playback pauses. They put it back; playback resumes. This
works even though the phone cannot open the AAP channel, because wear state is carried
in the advertisement.

**Why this priority**: It is the single most-missed AirPods behaviour on Android, and —
unusually — it is reachable over the always-available transport.

**Independent Test**: Drive the auto-pause policy with a sequence of wear-state
transitions and assert the emitted media actions, with no Android media stack involved.

**Acceptance Scenarios**:

1. **Given** auto-pause is enabled and both buds are in the ears with audio playing,
   **When** one bud leaves the ear, **Then** a pause action is emitted exactly once.
2. **Given** playback was paused by GreenPods, **When** the bud returns to the ear,
   **Then** a resume action is emitted.
3. **Given** playback was paused by the *user*, **When** a bud returns to the ear,
   **Then** no resume action is emitted.
4. **Given** both buds are put into the case, **When** the transition is observed,
   **Then** exactly one pause action is emitted, not two.
5. **Given** auto-pause is disabled in settings, **When** any wear transition occurs,
   **Then** no media action is emitted.
6. **Given** the accessory is not the current audio output, **When** a bud leaves the
   ear, **Then** no media action is emitted.

---

### User Story 4 - Control noise mode when the phone allows it (Priority: P2)

On a device whose stack permits the L2CAP channel, the user switches between Off,
Noise Cancellation, Transparency and Adaptive, adjusts adaptive noise strength, toggles
Conversational Awareness, and chooses which modes the stem long-press cycles through.

**Why this priority**: It is the headline capability of the AAP transport, but it is
unreachable on most phones, so it cannot outrank the two stories above.

**Independent Test**: Assert the exact bytes each command produces against captured
fixtures, and assert that every control is inert when the transport is gated.

**Acceptance Scenarios**:

1. **Given** the AAP transport is live, **When** the user selects Transparency, **Then**
   the control packet `04 00 04 00 09 00 0D 03 00 00 00` is written.
2. **Given** the transport is live, **When** the accessory reports a mode change made on
   the buds themselves, **Then** the UI selection follows it.
3. **Given** the transport is gated, **When** the user taps a mode chip, **Then** nothing
   is written and the chip stays disabled.
4. **Given** the user picks fewer than one mode for the long-press cycle, **Then** the
   selection is rejected — the accessory requires at least one.
5. **Given** adaptive strength is changed, **Then** a single command carrying 0..100 is
   written, and it is only enabled while Adaptive mode is selected.

---

### User Story 5 - Bind head gestures to actions (Priority: P3)

A user maps a nod to "accept call" and a shake to "reject call", sets how confident a
detection must be before it fires, and sees a live head-orientation readout while
calibrating.

**Why this priority**: Delightful, but it needs both the AAP transport and head-tracking
hardware, so it reaches the fewest users.

**Independent Test**: Replay synthetic pose streams through the detector and assert
which gestures fire, with what confidence, and that the cooldown suppresses doubles.

**Acceptance Scenarios**:

1. **Given** a nod-shaped pitch excursion, **When** it is replayed, **Then** exactly one
   NOD event fires with confidence ≥ the binding's minimum.
2. **Given** a single head turn with no reversal, **When** it is replayed, **Then** no
   SHAKE fires.
3. **Given** two gestures within the cooldown window, **Then** only the first fires.
4. **Given** a binding is disabled, **When** its gesture is detected, **Then** no action
   is dispatched.
5. **Given** bindings are edited, **When** the app restarts, **Then** they are restored.

---

### User Story 6 - Keep watching in the background (Priority: P2)

The user turns on background monitoring. A low-priority ongoing notification shows the
nearest accessory and its lowest bud level; when a bud drops below the configured
threshold, a separate one-shot notification warns them.

**Why this priority**: Battery readings are only useful if they are there when the phone
is locked in a pocket.

**Independent Test**: Drive the notifier with a descending battery series and assert it
fires once per threshold crossing, not once per advertisement.

**Acceptance Scenarios**:

1. **Given** monitoring is on, **When** the app is backgrounded, **Then** scanning
   continues and the ongoing notification tracks the nearest accessory.
2. **Given** the threshold is 20 %, **When** a bud reports 19 %, **Then** one warning
   fires; further 19 % reports do not re-fire it.
3. **Given** the bud is charged back above the threshold and drops again, **Then** the
   warning fires again.
4. **Given** monitoring is off, **When** the app is backgrounded, **Then** no service
   runs and no notification is posted.
5. **Given** notification permission is denied, **Then** monitoring degrades to
   foreground-only without crashing.

---

### User Story 7 - Heart rate where it is actually available (Priority: P3)

A Powerbeats Pro 2 owner sees live BPM. An AirPods Pro 3 owner sees the feature listed
as not implemented, with the reason: the AAP measurement frame is not publicly decoded.

**Why this priority**: It affects one model, but getting it *honest* protects the whole
app's credibility.

**Independent Test**: Parse Heart Rate Measurement characteristic values in both 8-bit
and 16-bit flag encodings; assert AirPods Pro 3 never claims a BPM.

**Acceptance Scenarios**:

1. **Given** a Powerbeats Pro 2 exposing service `0x180D`, **When** connected, **Then**
   notifications produce BPM values tagged as coming from GATT.
2. **Given** a device without `0x180D`, **When** connection is attempted, **Then** the
   attempt ends cleanly and the feature shows as unsupported.
3. **Given** an AirPods Pro 3, **Then** heart rate is listed as not implemented and no
   number is ever displayed.

---

### User Story 8 - Stay up to date (Priority: P3)

The user checks for updates and is told either that they are current or that a newer
release exists, with its notes and a download.

**Why this priority**: The app ships outside any store, so the updater is the only
delivery path — but it is worthless without the features above.

**Independent Test**: Feed release JSON — newer, same, older, malformed, missing APK
asset — and assert the resulting summary.

**Acceptance Scenarios**:

1. **Given** the latest release is newer, **Then** the version and its notes are shown
   with a download action.
2. **Given** the installed version is current, **Then** the app says so and offers no
   download.
3. **Given** the check fails or the release carries no APK asset, **Then** a plain
   message is shown and nothing crashes.

---

### Edge Cases

- Bluetooth is switched off while scanning: the list empties and the UI says scanning
  is unavailable; it recovers when Bluetooth returns, without a restart.
- Scan permission denied or revoked mid-session: the empty state explains it and offers
  a way to grant it; no repeated permission prompts.
- Two accessories advertising at once: both are listed; the closest by RSSI is the one
  the notification and controls act on.
- An unknown Apple model id: the accessory is ignored for display purposes but the raw
  id is recorded in diagnostics so the registry can grow.
- Malformed or truncated advertisement/AAP packets: dropped or surfaced as unknown,
  never crashing and never producing a half-decoded state.
- The AAP channel opens and then drops mid-session: controls return to their gated
  state and the reason updates; no retry storm.
- Advertisements arrive faster than the UI can render: state is conflated, not queued.

## Requirements *(mandatory)*

### Functional Requirements

**Discovery and state**

- **FR-001**: The app MUST discover Apple/Beats accessories by passively decoding
  Apple's proximity-pairing BLE advertisement, without pairing or connecting.
- **FR-002**: The app MUST decode model id, per-bud and case battery level, charging
  flags, wear state, and the lid-open counter from that advertisement.
- **FR-003**: The app MUST represent an unknown battery level as absent, never as zero.
- **FR-004**: The app MUST age out accessories that have not advertised for 30 seconds
  rather than removing them on a single missed packet.
- **FR-005**: The app MUST keep values that only AAP or GATT can supply when refreshing
  an accessory from an advertisement.
- **FR-006**: The app MUST rank accessories by RSSI to pick the one the user most likely
  means.

**Transport gate**

- **FR-007**: The app MUST probe the AAP transport per accessory and cache the outcome
  with a reason: available, PSM rejected, API unavailable, channel mode refused, not
  permitted, or a specific failure.
- **FR-008**: The app MUST derive every offered capability from live transports, never
  from the model's hardware feature set alone.
- **FR-009**: The app MUST display hardware features that no live transport can reach as
  locked, with the reason, rather than hiding them.
- **FR-010**: The app MUST treat a failed AAP probe as a normal outcome — no crash, no
  error dialog, no automatic retry loop.
- **FR-011**: The app MUST surface undecodable AAP traffic in diagnostics rather than
  discarding it.

**Control (AAP-gated)**

- **FR-012**: When AAP is live, users MUST be able to set the listening mode, adaptive
  noise strength (0..100), conversational awareness, and the long-press mode cycle.
- **FR-013**: The app MUST reflect changes made on the accessory itself back into the UI.
- **FR-014**: The app MUST reject a long-press mode cycle containing no modes.
- **FR-015**: Every write control MUST be inert and visibly disabled when AAP is gated.

**Ear detection actions**

- **FR-016**: Users MUST be able to enable auto-pause on bud removal and auto-resume on
  reinsertion, driven by advertisement wear state alone.
- **FR-017**: The app MUST NOT resume playback it did not itself pause.
- **FR-018**: The app MUST emit at most one media action per wear transition.
- **FR-019**: The app MUST NOT act when the accessory is not the active audio output.

**Head gestures (AAP-gated)**

- **FR-020**: Users MUST be able to bind head gestures to actions, enable or disable each
  binding, and set a per-binding minimum confidence.
- **FR-021**: The app MUST NOT dispatch an action for a disabled binding or for a
  detection below its binding's minimum confidence.
- **FR-022**: Bindings MUST persist across restarts.

**Background and notifications**

- **FR-023**: Users MUST be able to turn background monitoring on and off; when on, a
  `connectedDevice` foreground service keeps scanning and shows the nearest accessory.
- **FR-024**: The app MUST warn once per downward crossing of a user-set low-battery
  threshold, per accessory, per component.
- **FR-025**: The app MUST function without notification permission, degrading to
  foreground-only monitoring.

**Heart rate**

- **FR-026**: The app MUST read heart rate over the standard Bluetooth SIG Heart Rate
  Profile where the accessory exposes it.
- **FR-027**: The app MUST NOT display a heart rate derived from AAP, and MUST state
  that the AAP measurement frame is not publicly decoded.

**Settings and updates**

- **FR-028**: All user preferences MUST persist and MUST be observable as a stream so
  the UI updates without a restart.
- **FR-029**: Users MUST be able to check for a newer release and see whether they are
  current, with failures reported as plain text.

**Permissions**

- **FR-030**: The app MUST request `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on API 31+ and
  `ACCESS_FINE_LOCATION` below that, MUST scan with `neverForLocation` where available,
  and MUST explain — not silently fail — when a permission is missing.

### Key Entities

- **PodState**: everything known about one accessory — address, model, battery, wear
  state, noise mode, heart rate, RSSI, live transports, last-seen time. Derives
  `usableFeatures` and `gatedFeatures`.
- **Transport**: `BLE_ADVERTISEMENT`, `GATT`, `AAP_L2CAP` — independent, not a ladder.
- **PodFeature**: a hardware capability plus the minimum transport that can reach it.
- **AapAvailability**: the probe outcome and its reason; the source of every "why not".
- **GreenPodsSettings**: auto-pause, auto-resume, background monitoring, low-battery
  threshold, scan mode, gesture bindings.
- **DiagnosticEvent**: a timestamped record of transport probes and undecoded traffic.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a phone with no AirPods paired and none nearby, the app opens to a
  self-explanatory empty state and never crashes, on API 26 through the current target.
- **SC-002**: A nearby accessory appears within 5 seconds of the case opening.
- **SC-003**: 100 % of a model's hardware features are accounted for in the UI as either
  usable or locked-with-reason; none are silently missing.
- **SC-004**: Every AAP command the app can send is byte-pinned by a test against a
  captured fixture.
- **SC-005**: Auto-pause emits exactly one media action per wear transition across the
  full transition matrix, verified by test.
- **SC-006**: The unit test suite covers every pure decoder, mapper, detector, policy,
  repository and view model, and runs off-device in under two minutes.
- **SC-007**: `spotlessCheck`, `lintDebug` and `testDebugUnitTest` pass from a clean
  checkout.
- **SC-008**: With the AAP transport gated — the common case — the app remains fully
  functional for battery, wear state, auto-pause and diagnostics.

## Assumptions

- Most target devices cannot open the AAP L2CAP channel; the gated path is the default
  experience, not the exception.
- Advertisement decoding is lossy and unordered; state is accumulated and aged, not
  replaced wholesale.
- No AirPods are available for on-device verification of this change, so device testing
  covers the empty-state, permission, gating and navigation paths, and protocol
  behaviour is verified against captured fixtures off-device.
- The heart-rate AAP frame layout remains undecoded; nothing in this feature depends on
  it.
- Find My, automatic device switching, "Hey Siri" and audio sharing require Apple
  account services and are out of scope.
