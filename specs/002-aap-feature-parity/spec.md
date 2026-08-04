# Feature Specification: Apple protocol feature parity

**Feature Branch**: `feat/rootless-aap`

**Created**: 2026-08-04

**Status**: Draft

**Input**: Full feature parity with LibrePods for everything reachable on an unrooted
Android phone over the now-working AAP channel, with nothing behind a paywall.

## Context

Until 2026-08-04 this project assumed the Apple protocol channel could not be opened on
an unrooted phone, and shipped only what the BLE advertisement carries. That assumption
was wrong: the channel opens on stock Android once the request has the right shape, and
this has been verified end to end on a Pixel 8 running Android 17 with AirPods Pro 3 —
configuration read back, listening mode written and echoed by the accessory.

Everything below was already reachable and simply had not been asked for. The work is
therefore not research; it is turning a proven channel into the feature surface users
expect, and it is bounded by what the accessory itself reports it can do.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Control what you hear (Priority: P1)

Someone wearing AirPods on an Android phone wants the same control over listening modes
they would have on an iPhone: switch between Off, Noise Cancellation, Transparency and
Adaptive, set how strong Adaptive is, and turn Conversational Awareness on or off —
without reaching for the buds and cycling blindly through whatever the stem is bound to.

**Why this priority**: This is the feature people install a third-party AirPods app for,
and it is the one the transport gate has been denying. Everything else in this spec is
worth less if this does not work.

**Independent Test**: Set each listening mode from the app and from adb, and confirm the
accessory reports the mode back rather than the app echoing its own request. Fully
deliverable on its own: a build with only this story is already useful.

**Acceptance Scenarios**:

1. **Given** the Apple protocol channel is live, **When** the user selects Transparency,
   **Then** the accessory switches audibly and the app shows Transparency because the
   accessory said so, not because the request was sent.
2. **Given** the accessory reports that the Off mode is not enabled, **When** the user
   opens noise control, **Then** Off is shown as unavailable with the reason, and the
   remaining modes still work.
3. **Given** the channel is not available on this phone, **When** the user opens noise
   control, **Then** every mode is shown locked with the reason, and nothing appears
   broken or missing.
4. **Given** the user sets Adaptive strength to 40 %, **When** the setting is re-read
   after a reconnect, **Then** it is still 40 % because it lives in the accessory.

---

### User Story 2 - A live and accurate picture of the accessory (Priority: P1)

The status screen should show what the accessory actually reports — both bud levels and
the case, wear state without waiting for the next advertisement, its name, model, serial
numbers and firmware — and should keep showing it while the buds stay connected, rather
than only at the moment something was written.

**Why this priority**: The channel currently opens per write and closes again, so state
is a snapshot taken during a command. Accurate battery and instant ear detection are the
second reason people install these apps, and every other story depends on the session
being managed rather than incidental.

**Independent Test**: Connect, leave the app idle, and confirm state keeps updating as
buds are removed and replaced; drop the connection and confirm it recovers by itself.

**Acceptance Scenarios**:

1. **Given** the accessory is connected, **When** a bud is taken out, **Then** the wear
   state changes without waiting for a BLE advertisement interval.
2. **Given** the channel is live, **When** the status screen is shown, **Then** battery
   for both buds and the case comes from the accessory, and the case shows a real value
   rather than the advertisement's unknown sentinel where the accessory reports one.
3. **Given** the channel drops because the buds were put away, **When** they are worn
   again, **Then** the session re-establishes without the user doing anything.
4. **Given** a probe previously failed, **When** a session is nonetheless carrying
   traffic, **Then** features are unlocked — the live channel outranks the stale probe.

---

### User Story 3 - Make the buds behave the way you want (Priority: P2)

The configuration that normally lives in iOS Settings: what a single, double, triple
press and a press-and-hold do on each bud, which listening modes a long press cycles
through, how fast a double press has to be, how long a hold counts as a hold, whether
volume swipe is on and how fast it moves, whether noise cancellation works with one bud,
call controls, which bud the microphone uses, and whether media pauses when you fall
asleep.

**Why this priority**: This is the bulk of the parity surface by count and the part that
is genuinely unavailable to Android users today. It is not P1 only because it is
configuration rather than something felt every time the buds are worn.

**Independent Test**: Change each binding, confirm the accessory echoes the new value,
then perform the physical gesture and confirm the new behaviour.

**Acceptance Scenarios**:

1. **Given** the channel is live, **When** the user binds press-and-hold to the voice
   assistant, **Then** holding the stem invokes the Android assistant.
2. **Given** the user selects which modes a long press cycles through, **When** fewer
   than one mode is selected, **Then** the change is refused with an explanation rather
   than leaving the accessory in a state the phone cannot undo.
3. **Given** a setting the accessory does not report as supported, **When** the settings
   screen is shown, **Then** that control is locked with its reason rather than absent.

---

### User Story 4 - Head gestures and head tracking (Priority: P2)

Nod to answer a call and shake to decline, and a live view of head orientation for
anyone who wants to see that the sensors work.

**Why this priority**: Distinctive, already partly built in this codebase, and blocked
only by the transport. Below P1 because it applies to a narrower set of moments.

**Independent Test**: Start the head-tracking stream, confirm samples arrive, and drive a
simulated incoming call to confirm nod and shake are recognised and acted on.

**Acceptance Scenarios**:

1. **Given** head gestures are enabled and a call is ringing, **When** the user nods,
   **Then** the call is answered.
2. **Given** head tracking is streaming, **When** the user turns their head, **Then**
   orientation updates smoothly and the stream stops when nothing needs it.

---

### User Story 5 - Know which accessory is which (Priority: P3)

The app should identify the accessory correctly when more than one Apple accessory is
paired, and should keep showing battery and wear state without holding a channel open.

**Why this priority**: Correctness rather than a visible feature, and today's heuristic —
assume the only paired Apple accessory — silently gives up when a user owns two. Lower
priority because most users own one.

**Independent Test**: Pair two Apple accessories and confirm advertisements are attributed
to the right one; confirm battery still updates with no channel open.

**Acceptance Scenarios**:

1. **Given** two paired Apple accessories, **When** an advertisement arrives from a
   rotating private address, **Then** it is attributed to the accessory it belongs to
   rather than reported as ambiguous.
2. **Given** the keys have been obtained once, **When** the channel is closed, **Then**
   battery and wear state still update from the advertisement alone.
3. **Given** no keys have been obtained yet, **When** an advertisement arrives, **Then**
   behaviour is exactly as it is today — degraded, never broken.

---

### User Story 6 - Name it, and tune it (Priority: P3)

Rename the accessory, and adjust the audio profile the accessory stores for itself.

**Why this priority**: Genuinely useful and stored in the accessory, but neither is
reached for often. Renaming carries a caveat outside the app's control.

**Independent Test**: Rename and confirm the accessory reports the new name; change the
audio profile and confirm it survives a reconnect.

**Acceptance Scenarios**:

1. **Given** the channel is live, **When** the user renames the accessory, **Then** the
   new name is stored in the accessory and the app explains that Android may keep showing
   the old name until the buds are paired again.
2. **Given** an audio profile is set, **When** the accessory is reconnected, **Then** the
   profile is still in effect.

---

### Edge Cases

- **The accessory says it cannot do something this model normally can.** The accessory's
  own capability report wins over the model table. A unit on older firmware must not be
  offered a control it will ignore.
- **A write is accepted but never echoed.** Treated as failure, not success. This is the
  characteristic failure of this transport and must never be reported as a completed
  change.
- **The channel drops mid-write.** The setting is left as the accessory last reported,
  never as what the user asked for, and the failure is visible.
- **Two accessories advertise at once.** Each is attributed independently; one being
  unidentifiable never suppresses the other.
- **The phone cannot open the channel at all.** Every story above degrades to locked
  controls with reasons. Battery, ear detection and auto-pause continue to work.
- **The accessory reports a value the app does not have a name for.** It is shown as the
  raw value and logged as unhandled rather than discarded or guessed at.
- **A setting is changed on an Apple device afterwards.** The app shows what the
  accessory currently reports, without claiming ownership of settings it did not set.

## Requirements *(mandatory)*

### Functional Requirements

**Session**

- **FR-001**: The system MUST maintain a single managed Apple protocol session per
  accessory, shared by every consumer, rather than opening a channel per command.
- **FR-002**: The system MUST re-establish a dropped session automatically while the
  accessory remains connected, without user action and without a retry storm.
- **FR-003**: The system MUST treat a live session as authoritative over any earlier
  availability probe.
- **FR-004**: The system MUST release the session when the accessory disconnects or when
  nothing needs it, so an idle app does not hold the accessory's radio open.
- **FR-005**: The system MUST NOT report a write as successful until the accessory
  confirms it.

**Capabilities**

- **FR-006**: The system MUST read the accessory's own capability report and offer only
  controls that this unit supports.
- **FR-007**: The system MUST show unsupported and unreachable controls in a locked state
  with the reason, never hidden.
- **FR-008**: The system MUST distinguish "this accessory cannot" from "this phone
  cannot" in what it tells the user.

**Listening and audio**

- **FR-009**: Users MUST be able to set the listening mode among the modes the accessory
  reports as enabled, including Off where available.
- **FR-010**: Users MUST be able to set Adaptive strength.
- **FR-011**: Users MUST be able to turn Conversational Awareness on and off.
- **FR-012**: Users MUST be able to turn adaptive volume and personalised volume on and
  off where supported.
- **FR-013**: Users MUST be able to adjust the audio profile the accessory stores, and
  the current profile MUST be read from the accessory rather than assumed.

**Controls and gestures**

- **FR-014**: Users MUST be able to bind single, double and triple press and
  press-and-hold, per bud where the accessory supports per-bud bindings.
- **FR-015**: Users MUST be able to choose which listening modes a long press cycles
  through, and the system MUST refuse a selection of fewer than one mode.
- **FR-016**: Users MUST be able to set press speed and press-and-hold duration.
- **FR-017**: Users MUST be able to turn volume swipe on and off and set its speed.
- **FR-018**: Users MUST be able to turn on noise cancellation with a single bud.
- **FR-019**: Users MUST be able to configure call controls and which bud carries the
  microphone.
- **FR-020**: Users MUST be able to turn on pausing media when falling asleep, and the
  in-case tone, where the accessory supports them.
- **FR-021**: Users MUST be able to answer a call by nodding and decline by shaking, when
  head gestures are enabled.
- **FR-022**: The system MUST stream head orientation on demand and stop when no consumer
  needs it.

**State**

- **FR-023**: The system MUST report battery for both buds and the case from the
  accessory when the session is live, and from the advertisement otherwise, without the
  two contradicting each other.
- **FR-024**: The system MUST report wear state from the session when live, so ear
  detection does not wait for an advertisement interval.
- **FR-025**: Users MUST be able to turn ear detection on and off in the accessory.
- **FR-026**: The system MUST show the accessory's model, name, separate left and right
  serial numbers, and firmware versions.
- **FR-027**: Users MUST be able to rename the accessory, and MUST be told that Android
  may continue to show the previous name until the accessory is paired again.

**Identity**

- **FR-028**: The system MUST obtain the accessory's identity resolving key and use it to
  attribute a rotating private address to the correct paired accessory.
- **FR-029**: The system MUST obtain the accessory's encryption key and use it to read
  the encrypted portion of the advertisement.
- **FR-030**: The system MUST degrade to today's behaviour when either key is unavailable,
  never worse.
- **FR-031**: The system MUST store these keys no less securely than any other
  accessory-identifying data it holds, and MUST NOT include them in diagnostics output or
  bug reports.

**Verifiability**

- **FR-032**: Every capability above MUST be exercisable from adb, and every value it
  reads MUST appear in the state dump.
- **FR-033**: Traffic the system cannot interpret MUST remain visible as unhandled rather
  than discarded.

**Availability**

- **FR-034**: Every feature in this specification MUST be available to every user at no
  cost.

### Key Entities

- **Session**: A live conversation with one accessory. Knows whether it is writable, what
  the accessory has reported, and why it ended.
- **Capability report**: What this particular unit says it can do, which bounds the UI
  independently of the model table.
- **Accessory configuration**: The settings stored in the accessory itself — listening
  modes, press bindings, timings, toggles. Survives a host switch, and can be changed
  from an Apple device outside this app's knowledge.
- **Device identity**: Model, name, per-bud serial numbers, firmware versions, and the
  keys that link a rotating advertisement address to a paired accessory.
- **Feature gate**: The pairing of a transport's availability with a capability report,
  producing what the UI may offer and the reason for anything it may not.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a phone where the channel opens, a user can change listening mode and
  hear the result within 2 seconds of tapping.
- **SC-002**: Every setting offered is confirmed by the accessory before it is shown as
  changed; no setting is ever displayed as applied when it was not.
- **SC-003**: Removing a bud registers within 1 second while a session is live, compared
  with up to an advertisement interval today.
- **SC-004**: With two Apple accessories paired, advertisements are attributed to the
  right accessory in every observation over a 10-minute session.
- **SC-005**: On a phone where the channel does not open, every control in this
  specification is visible, locked, and carries a reason; no screen is empty and nothing
  reads as missing.
- **SC-006**: 100 % of the capabilities in this specification can be driven and observed
  from adb with no hardware interaction beyond wearing the buds.
- **SC-007**: An idle session costs no more battery than the existing background
  monitoring does today, measured over an hour with the app in the background.
- **SC-008**: No capability in this specification is gated behind payment.

## Out of Scope

Each for a different reason, and the reason matters more than the exclusion:

- **Hearing aid, transparency customisation, loud sound reduction.** These are reached
  over a second channel that the accessory only opens to a host presenting Apple's vendor
  identity. Achieving that on Android requires modifying the Bluetooth stack, which means
  Xposed and therefore root — outside this project's baseline.
- **Heart rate on AirPods Pro 3.** Decoded and streaming as of 2026-08-04, after this
  specification was first written — live readings at a requested interval, with no
  workout and no root. It stays out of this specification because it is a feature in its
  own right with its own questions (readings must be gated on a confidence value, and
  continuous sensing has a battery cost the user should choose), not because it is
  unreachable. It should be specified next.
- **Find My, automatic device switching, Siri invocation, audio sharing.** These need
  Apple account services, not protocol access, and no third-party client can join them.
- **Providing head tracking to Android for spatial audio.** Not explored; likely needs
  system integration beyond an ordinary app.

## Assumptions

- **The session is held while the accessory is connected**, not always and not only
  during a command. Holding it only during a write makes accurate battery and fast ear
  detection impossible; holding it while the buds are away wastes power on both ends.
- **The accessory's capability report bounds the UI**, and where it is absent or
  unreadable the existing model table is the fallback rather than a blocker.
- **Settings live in the accessory, not in this app.** The app displays what the
  accessory reports and does not attempt to restore its own idea of a setting after
  something else changed it.
- **Per-bud bindings are offered only where the accessory supports them**; older models
  that take a single binding for both buds are shown a single control.
- **Renaming is expected to leave Android showing the old name** until the accessory is
  paired again. This is Android's behaviour, is documented to the user, and is not
  treated as a defect.
- **The audio profile is read and written as a whole**, not merged field by field with
  whatever an Apple device last wrote.
- **This works on current Android and is not promised for every device.** Availability is
  probed at runtime; an Android release that moves what the channel depends on degrades
  the app to its advertisement-only baseline rather than breaking it.
- **Existing work is reused**: head gesture detection, head pose mapping, the transport
  gate and the adb surface already exist and are extended rather than replaced.
