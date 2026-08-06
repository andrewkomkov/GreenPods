# Feature Specification: Live Activities

**Feature Branch**: `005-live-activities`

**Created**: 2026-08-05

**Status**: Clarified — ready for planning

**Input**: User description: "Live Activities for GreenPods — a persistent, glanceable surface on the lock screen and status bar that shows what the earbuds are doing right now, using Android's Live Updates (promoted ongoing notifications, Android 16+). Candidate content: per-bud and case battery, in-ear state, current noise-control mode, and an active heart-rate session. Must degrade honestly on older Android where the API does not exist, and must not show a feature the transport gate has locked. Note the project already runs a foreground monitoring service with an ongoing notification, so this is an upgrade of that surface rather than a new one, and heart rate has a standing rule that no reading appears in any diagnostic path."

## Overview

GreenPods already runs a monitoring service with an ongoing notification. That notification
exists to disclose that the app is running; it is not something anyone reads for
information. This feature turns it into a surface worth glancing at — battery, wear and
listening mode, visible without unlocking the phone — on phones whose platform supports a
promoted ongoing status surface, and states plainly what it cannot do on the phones that
do not.

The point is *glanceability*: "how much battery is left" should be answerable without
unlocking the phone, opening the app, or pulling down the shade.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See battery without unlocking (Priority: P1)

Someone is walking with their earbuds in and wants to know whether the buds will last the
journey. Today that means waking the phone, unlocking it and opening GreenPods. With this
feature the answer is on the lock screen, and on the status bar as a compact chip while the
phone is in use.

**Why this priority**: It is the most-asked question of any earbud app, it is answerable
from the always-available advertisement transport alone, and it needs no writes — so it
works on every phone that can see the earbuds at all. It is the whole feature in miniature
and is independently shippable.

**Independent Test**: Wear the earbuds, lock the phone, read both bud levels and the case
level from the lock screen without unlocking. Drive a level change with an injected
synthetic advertisement and watch the surface follow.

**Acceptance Scenarios**:

1. **Given** the earbuds are connected and monitoring is running, **When** the phone is
   locked, **Then** the surface shows left, right and case battery and the wear state, and
   is readable without unlocking.
2. **Given** the surface is showing, **When** a bud's battery changes, **Then** the surface
   reflects the new level without the user opening the app.
3. **Given** the earbuds go out of range, **When** advertisements stop arriving, **Then**
   the surface says the accessory is not currently visible rather than continuing to show
   the last levels as though they were current.
4. **Given** the case level has never been reported, **When** the surface is shown, **Then**
   the case reads as unknown rather than as zero or absent.

---

### User Story 2 - Change noise control from the lock screen (Priority: P2)

The same person walks indoors and wants transparency. Today: unlock, open the app, tap.
With this feature the mode is on the surface and switching it is one tap from where they
already are.

**Why this priority**: The highest-value *action*, but it depends on the AAP transport —
exactly the thing that may not be available. It must come after the read-only story and
must obey the gate.

**Independent Test**: With the channel open, switch modes from the surface and confirm the
accessory echoes it. With the channel unavailable, confirm the control is shown locked with
its reason and cannot be actuated.

**Acceptance Scenarios**:

1. **Given** the AAP channel is open, **When** the user taps a listening mode on the
   surface, **Then** the accessory changes mode and the surface shows the mode the
   accessory reports, not the one requested.
2. **Given** the AAP channel is unavailable, **When** the surface is shown, **Then** the
   noise-control affordance appears locked with the gate's reason, and interacting with it
   explains rather than silently doing nothing.
3. **Given** a mode change was sent, **When** the accessory has not echoed it, **Then** the
   surface does not claim the new mode.

---

### User Story 3 - Know that the heart-rate sensor is running (Priority: P2)

While a heart-rate session is active the earbuds' optical sensor is on and costing battery.
The user must be able to tell that from outside the app, and to stop it from there.

**Why this priority**: A disclosure obligation, not a convenience. Something is sensing the
user's body; the surface that says so should be the most visible one the phone has. It sits
at P2 rather than P1 only because it applies to fewer models.

**Independent Test**: Start a session, lock the phone, confirm the surface discloses that
sensing is active and that its stop control stops the sensor in the earbuds.

**Acceptance Scenarios**:

1. **Given** a heart-rate session is running, **When** the phone is locked, **Then** the
   surface discloses that sensing is active.
2. **Given** the disclosure is showing, **When** the user uses its stop control, **Then**
   sensing stops in the earbuds, not merely on screen.
3. **Given** no session is running, **When** the surface is shown, **Then** it makes no
   mention of heart rate at all.

---

### User Story 4 - Understand why this phone cannot do it (Priority: P3)

On a phone whose platform has no promoted live surface, the user should learn that from the
app rather than concluding the feature is broken or that they have missed a setting.

**Why this priority**: Required by "locked, not hidden", and it affects every phone below
the supporting platform version — likely most of them. P3 only because it delivers
information rather than function.

**Independent Test**: On a phone below the supporting version, confirm the settings entry
is present, disabled and reasoned; and that the existing ongoing notification is unchanged
and still discloses that monitoring runs.

**Acceptance Scenarios**:

1. **Given** the platform provides no promoted live surface, **When** the user opens
   settings, **Then** the option is visible, disabled, and gives the reason.
2. **Given** the same, **When** monitoring is running, **Then** the existing ongoing
   notification continues to work exactly as before.

---

### Edge Cases

- **Two sets of earbuds in range.** The surface describes one accessory. It must be the one
  this phone is bonded to and connected to, and it must be named, so the surface is never
  ambiguous about whose battery it shows.
- **The accessory disappears mid-session.** "Not in range", "unknown" and "0%" must be
  three visibly different things. Showing a stale level as current is the failure this case
  exists to prevent.
- **Monitoring is switched off.** The surface goes with it. It is a view onto the
  monitoring session, not something that outlives it.
- **Notifications are denied.** The monitoring service still runs (existing behaviour) and
  the surface simply cannot appear. Nothing may sense silently as a result, and the
  settings copy must say so.
- **Someone else picks up the locked phone.** Whatever the surface shows is readable by
  them. This is why heart-rate content needs an explicit decision rather than a default.
- **The case is charging.** Charging must be distinguishable from a static level, or a
  rising number reads as a glitch.
- **Rapid wear changes** — a bud in and out repeatedly — must not produce a flickering or
  repeatedly re-posted surface.

## Requirements *(mandatory)*

### Functional Requirements

#### The surface

- **FR-001**: The system MUST present a persistent, glanceable status surface for the
  connected accessory for as long as monitoring is running and the platform supports one —
  not only when something noteworthy is happening. (Resolved 2026-08-06 — see Q2.)
- **FR-002**: The surface MUST show left bud, right bud and case battery, and MUST
  distinguish "unknown" from any numeric level.
- **FR-003**: The surface MUST show the accessory's wear state.
- **FR-004**: The surface MUST name the accessory it describes.
- **FR-005**: The surface MUST be readable without unlocking the phone.
- **FR-006**: The surface MUST follow state changes without user action.
- **FR-007**: The surface MUST indicate when the accessory is no longer in range, and MUST
  NOT present a stale reading as current.
- **FR-008**: The surface MUST disappear when monitoring stops.
- **FR-009**: Acting on the surface's main affordance MUST open the app's status screen.
- **FR-010**: Charging MUST be distinguishable from a static level.

#### The gate

- **FR-011**: The surface MUST derive what it offers from `PodState.usableFeatures` and
  `gatedFeatures`, never from the accessory model alone.
- **FR-012**: A control for a gated feature MUST be shown locked with the gate's reason
  attached, not hidden.
- **FR-013**: The system MUST NOT report a control's new value until the accessory has
  confirmed it.
- **FR-014**: Where the platform provides no promoted live surface, the system MUST leave
  the existing ongoing notification exactly as it is today. No fallback layout is built and
  no existing behaviour changes. (Resolved 2026-08-06 — see Q3.)
- **FR-014a**: Those phones MUST still be told why, in settings, with the same shape of
  reason a locked transport carries. "Nothing new" is a scope decision; it is not licence
  to leave a user unable to tell a missing feature from a broken one.

#### Heart rate

- **FR-015**: While a heart-rate session is active, the system MUST disclose that sensing
  is running on a surface visible outside the app.
- **FR-016**: The disclosure MUST offer a control that stops sensing in the accessory
  itself, not only in the app.
- **FR-017**: When no session is running, the surface MUST make no mention of heart rate.
- **FR-018**: The surface MUST display the current heart rate while a session is active,
  including on the lock screen. A setting MUST exist to hide the value while keeping the
  disclosure, and the value MUST be shown by default. (Resolved 2026-08-06 — see Q1.)
- **FR-018a**: Hiding the value MUST NOT hide the disclosure. FR-015 is an obligation and
  is not subject to the same setting: a user may choose not to display their heart rate,
  and may not choose to have the sensor run without saying so.

#### Control and observability

- **FR-019**: The user MUST be able to turn the surface on and off independently of
  monitoring.
- **FR-020**: Whether the surface is showing, what it currently says, and why it is
  unavailable MUST all be inspectable from `adb` without touching the screen, and its
  content MUST be drivable with an injected synthetic advertisement.
- **FR-021**: `docs/adb.md` MUST document those commands in the same change that adds them.

### Key Entities

- **Live status summary**: The user-visible description of one accessory at one moment —
  identity, battery, wear, listening mode, and any active sensing disclosure. Derived
  state; it owns nothing.
- **Surface availability**: Whether this phone can present the richer surface, and the
  reason when it cannot. The same shape as an existing transport gate, and shown the same
  way.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can read both bud levels and the case level without unlocking the
  phone, in one glance, with no taps.
- **SC-002**: The surface reflects a battery or wear change within one advertisement
  interval of the app knowing about it.
- **SC-003**: Changing the listening mode from the surface takes one tap, and is reflected
  only after the accessory confirms it.
- **SC-004**: On a phone that cannot present the richer surface, a user can state why from
  what the app tells them — and monitoring still works exactly as before.
- **SC-005**: A user with an active heart-rate session can tell that sensing is running,
  read their current rate, and stop it, without opening the app.
- **SC-005a**: A user who has hidden the value can still tell that sensing is running, and
  still stop it.
- **SC-006**: No surface shows a locked feature as available, and no control reports a
  change the accessory has not confirmed.
- **SC-007**: Every behaviour above is reproducible from `adb` on a device with no
  accessory present, using injected advertisements.

## Assumptions

- The richer surface is an upgrade of the existing monitoring notification, not a second
  concurrent one. Two ongoing notifications for one service would be a regression.
- It is opt-out rather than opt-in where supported: it replaces a notification the user
  already sees, so it adds no new interruption.
- Battery, wear and charging come from the always-available advertisement transport, so the
  P1 story works on every phone that can see the accessory — including those where the AAP
  channel never opens.
- Listening-mode control is gated on AAP and is therefore present-but-locked on phones
  without it.
- "The accessory" means the one this phone is bonded to and currently connected to.
  Identity resolution is an existing solved problem and is reused, not redesigned.
- No new permission is introduced. Where notification permission is denied the surface is
  simply absent, and the existing disclosure rules continue to apply.

## Decisions

Resolved by the project owner, 2026-08-06. Recorded with their consequences, because two
of the three narrow the feature and one widens what it exposes.

### D1: The heart rate is shown, lock screen included — FR-018

**Decided: yes.** While a session is active the current reading appears on the surface,
without unlocking. A setting hides the value for anyone who would rather it did not; it is
on by default.

The concern that prompted the question stands and is not withdrawn: a lock screen is
readable by whoever picks the phone up, and this is a health measurement. What the existing
"no reading in any diagnostic path" rule protects is logs pasted into bug reports, and it
does not reach this case — a user choosing to display their own heart rate to themselves is
not the same act as a number leaking into a file they forward to a stranger. The rule is
unchanged and still binds every diagnostic path, including `dump`, `hr status` and the
frame log.

Two consequences to carry into planning:

- The value is shown **only while a session is active**. This grants no new visibility to
  anything else, and FR-017 still forbids mentioning heart rate at all otherwise.
- Hiding the value must not hide the disclosure (FR-018a). The sensor running is a fact the
  user is owed; the number is a convenience they may decline.

### D2: The surface is permanent — FR-001

**Decided: permanent.** It is present for as long as monitoring runs, not promoted only on
low battery, charging or an active session.

This is what makes SC-001 mean anything: an answer that is only sometimes there is not a
glanceable answer. It also matches what the monitoring notification already does, so it
adds no new persistent thing to the user's phone — it improves one that is already there.

### D3: Older phones get nothing new — FR-014

**Decided: nothing.** Phones whose platform has no promoted live surface keep exactly the
ongoing notification they have today. No fallback layout is designed, and no existing
behaviour changes.

That is a deliberate scope cut and it lands on most installs, given the project's Android
8.0 floor. It buys a single surface to build and keep true, rather than two that drift
apart.

It does **not** extend to silence. Those phones are still told why, in settings, in the
same shape as a locked transport (FR-014a) — the constitution's "locked, not hidden" is
about the user being able to tell "my phone can't" from "the app is broken", and a scope
decision does not change what they are owed. User Story 4 survives this decision intact and
is the only part of the feature those phones receive.
