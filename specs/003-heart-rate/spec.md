# Feature Specification: Heart rate

**Feature Branch**: `feat/heart-rate`

**Created**: 2026-08-04

**Status**: Implemented; working on hardware. Accuracy and battery cost unclaimed.

**Verified 2026-08-04** on a Pixel 8 (Android 17, unrooted) with AirPods Pro 3: the
accessory's heart-rate service is discovered from its own announcement rather than
assumed, reports arrive at the requested cadence, the confidence gate holds, the stop
frame reaches the earbuds, report bodies never enter any log, and a heart rate reaches
the screen.

**Not claimed — and this is load-bearing, not a caveat.** SC-002 (accuracy against a
reference monitor) and SC-006 (battery cost) were never measured. They are **deferred**,
with their procedures and what each would establish recorded in
[deferred-verification.md](./deferred-verification.md); they are not quietly dropped and
they are not satisfied by anything else in this spec. Until they are done, GreenPods
presents a sensor reading whose agreement with a reference instrument has never been
checked, and whose cost to the earbuds' battery is unmeasured. The confidence threshold
therefore still ships as the provisional 128 it started as. The Powerbeats Pro 2 route
remains implemented and unverified on hardware, as the Assumptions below record.

**Input**: Ship heart rate from AirPods Pro 3, and write it into Google's health store on
Android so other apps can use it.

## Context

AirPods Pro 3 have an optical heart-rate sensor that Apple only reads during a workout or
while the Health app is open. On 2026-08-04 the format was decoded and readings were
streamed on an unrooted Android phone with no workout running: the accessory publishes a
description of its own sensor, and streams to any host that asks for a report interval in
the ordinary way. Details in `docs/protocol-research.md`.

No Android app offers this today. That makes it the most valuable thing this project can
ship, and also the one where being wrong is most costly — a heart rate is a number people
believe, and once it is written into the system health store other apps believe it too.

Two facts from the capture shape everything below:

- The sensor's first readings are **wrong**. It reported 169 BPM from someone sitting
  still, converging to 81 over about twenty seconds. A confidence value published
  alongside each reading is low for exactly those readings.
- Sensing is continuous while enabled, and an optical sensor costs battery in the buds.
  Apple's decision to run it only during workouts was about power, and that trade-off does
  not disappear by being on Android.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See a heart rate you can trust (Priority: P1)

Someone wearing AirPods Pro 3 opens GreenPods and sees their current heart rate, updating
live, with no workout started and no Apple device involved.

**Why this priority**: It is the feature. Everything else here supports it.

**Independent Test**: Wear the buds, turn the feature on, and confirm a stable reading
appears and tracks reality — rising with exertion, falling with rest.

**Acceptance Scenarios**:

1. **Given** the sensor has just started, **When** the first readings are still settling,
   **Then** the app shows that it is measuring rather than showing those readings — a
   number is never displayed before it is trustworthy.
2. **Given** a stable reading, **When** the user's heart rate changes, **Then** the
   displayed value follows within a few seconds.
3. **Given** the reported confidence falls — a bud loosens, the user moves — **Then** the
   app says the reading is uncertain rather than continuing to present it as fact.
4. **Given** the buds are removed, **When** measurement can no longer be taken, **Then**
   the reading is cleared rather than frozen at its last value.

---

### User Story 2 - Decide whether it runs, and know what it costs (Priority: P1)

The user turns heart-rate sensing on deliberately, sees that it is running, and can turn
it off. They are told plainly that it draws on the buds' battery.

**Why this priority**: Equal to the reading itself. A sensor that runs unasked, drains the
buds and is discovered later is a worse outcome than not shipping the feature.

**Independent Test**: Confirm the feature is off on a fresh install, that enabling it is a
deliberate act, that its running state is visible, and that turning it off stops the
sensor rather than just hiding the number.

**Acceptance Scenarios**:

1. **Given** a fresh install, **When** the user has not enabled heart rate, **Then** the
   sensor is never started.
2. **Given** the feature is enabled, **When** the user is not looking at the app, **Then**
   the fact that sensing is active is discoverable without opening it.
3. **Given** the user turns it off, **When** they do, **Then** the accessory stops
   sensing — not merely stops being displayed.
4. **Given** the accessory disconnects or the buds are put away, **When** that happens,
   **Then** sensing stops on its own and resumes when they are worn again if the feature
   is still enabled.

---

### User Story 3 - Feed it to the phone's health store (Priority: P1)

Readings are written into Android's system health store, so the user's other apps — a
fitness tracker, a sleep app, whatever they already use — can read heart rate from their
AirPods as if it came from a dedicated wearable.

**Why this priority**: This is what makes the feature useful beyond a number on a screen,
and it is why the confidence gate is not merely a nicety: bad data written here spreads to
every app the user trusts.

**Independent Test**: Enable the integration, wear the buds, then confirm from a separate
health-reading app that the readings arrived, attributed to GreenPods, with the right
times.

**Acceptance Scenarios**:

1. **Given** the user has not granted permission to write health data, **When** heart rate
   is measured, **Then** it is shown in the app and nothing is written anywhere else.
2. **Given** permission is granted, **When** trustworthy readings are produced, **Then**
   they appear in the system health store attributed to GreenPods.
3. **Given** readings whose confidence is low, **When** they are produced, **Then** they
   are **never** written to the health store, regardless of settings.
4. **Given** the system health store is not available on the device, **When** the user
   looks for the integration, **Then** it is shown as unavailable with the reason, and
   the reading on screen still works.
5. **Given** the user revokes permission later, **When** they do, **Then** writing stops
   immediately and the app does not nag.

---

### User Story 4 - Powerbeats Pro 2 owners get the same screen (Priority: P2)

Someone with Powerbeats Pro 2 sees heart rate in the same place, in the same form, even
though it reaches the phone by a completely different route.

**Why this priority**: The capability already exists in the codebase and is currently
under-presented. Below P1 only because it affects fewer users.

**Independent Test**: With Powerbeats Pro 2, confirm the same screen, the same health-store
integration, and no behaviour borrowed from the AirPods path.

**Acceptance Scenarios**:

1. **Given** Powerbeats Pro 2, **When** heart rate is enabled, **Then** it is presented
   identically to the AirPods case.
2. **Given** an accessory with no heart-rate sensor of either kind, **When** the user looks
   for the feature, **Then** it is shown as unsupported by that model, not as broken.

---

### User Story 5 - Health data that stays yours (Priority: P2)

The user can see what has been recorded, delete it, and be confident it has not leaked
into logs or bug reports.

**Why this priority**: Heart rate is health data. This is not a feature so much as a
condition of shipping the others honestly.

**Independent Test**: Produce readings, then inspect every diagnostic and export path the
app offers and confirm none contains them; clear the data and confirm it is gone.

**Acceptance Scenarios**:

1. **Given** readings have been taken, **When** the user opens diagnostics or shares a bug
   report, **Then** no heart-rate value appears in it.
2. **Given** stored readings, **When** the user clears them, **Then** they are removed
   from the app, and the user is told that what was already written to the system health
   store is managed there.

---

### Edge Cases

- **The sensor never converges.** Confidence stays low — a loose fit, a cold ear. The app
  says it cannot get a reliable reading, and does not display or record a number. It must
  not wait silently forever.
- **Only one bud is worn.** If a reading is available it is used; if not, this is stated
  rather than presented as a sensor failure.
- **Confidence drops mid-session.** Recording pauses. It resumes without the user acting.
- **The accessory disconnects mid-measurement.** The reading is cleared, sensing state is
  remembered, and it resumes on reconnection.
- **The reading is implausible.** A value outside any survivable range is discarded and
  logged as unhandled rather than shown or recorded.
- **The user is on a model whose sensor service is numbered differently.** Discovery is
  from what the accessory reports about itself; a model that numbers its services
  differently must work without a code change.
- **The channel the readings arrive on is unavailable on this phone.** The feature is
  locked with its reason, exactly as every other channel-dependent feature is.
- **The system health store is present but the user denies permission.** Everything except
  writing continues to work, and the denial is not re-asked on every launch.

## Requirements *(mandatory)*

### Acquisition

- **FR-001**: The system MUST obtain heart rate from AirPods Pro 3 and comparable models
  over the Apple protocol, with no workout, no Apple device and no root.
- **FR-002**: The system MUST discover the sensor from what the accessory reports about
  itself, so a model that identifies its sensors differently works without a code change.
- **FR-003**: The system MUST request a measurement cadence rather than accepting whatever
  arrives, and MUST stop the sensor when nothing needs it.
- **FR-004**: The system MUST keep the AirPods route and the standard-profile route
  separate in acquisition, and MUST NOT blend or substitute readings between them.
- **FR-005**: The system MUST record which route a reading came from.

### Trust

- **FR-006**: The system MUST NOT display a reading whose reported confidence is below the
  threshold at which readings are known to be unreliable.
- **FR-007**: The system MUST NOT record or export a reading that it would not display.
- **FR-008**: The system MUST distinguish "measuring" from "measured" in what the user
  sees, so a settling sensor is never mistaken for a result.
- **FR-009**: The system MUST discard physiologically impossible values and surface them
  as unhandled rather than presenting them.
- **FR-010**: The system MUST NOT present heart rate as a medical measurement, and MUST NOT
  offer clinical interpretation of it.

### Control and cost

- **FR-011**: Heart-rate sensing MUST be off until the user enables it.
- **FR-012**: The system MUST tell the user, before they enable it, that continuous sensing
  consumes the accessory's battery.
- **FR-013**: The system MUST make active sensing discoverable without opening the app.
- **FR-014**: Turning the feature off MUST stop the sensor in the accessory.
- **FR-015**: Sensing MUST stop when the accessory disconnects or is not being worn, and
  resume when it is worn again while the feature remains enabled.

### Health store

- **FR-016**: The system MUST be able to write readings to Android's system health store,
  with accurate timestamps.
- **FR-017**: Writing MUST require the user's explicit permission and MUST be separately
  controllable from displaying the reading.
- **FR-018**: The system MUST stop writing immediately when permission is revoked, and MUST
  NOT repeatedly prompt for it.
- **FR-019**: Each reading MUST carry a stable identity, so a reading is not stored twice
  if sensing is interrupted and resumed, or if a write is retried.
- **FR-020**: Where the system health store is absent or unsupported on the device, the
  system MUST show the integration as unavailable with the reason and continue to work
  otherwise.
- **FR-021**: Readings MUST be attributed to the accessory that measured them — its
  manufacturer and model — and not only to GreenPods, so a user reading their history in
  another app can tell an AirPods measurement from a chest strap.
- **FR-022**: The system MUST provide an explanation of why it wants health-data access
  that is reachable **from the system's health settings**, not only from inside GreenPods.
  Users decide about these permissions from outside the app, and an app that cannot
  explain itself there is one they should decline.

### Privacy

- **FR-023**: Heart-rate values MUST NOT appear in diagnostics, state dumps, logs or any
  bug-report path.
- **FR-024**: Readings MUST remain on the device except where the user has enabled the
  health-store integration.
- **FR-025**: Users MUST be able to delete what the app has stored, and MUST be told that
  data already written to the system health store is managed there.

### Presentation and verifiability

- **FR-026**: The feature MUST be presented identically regardless of which route supplied
  the reading.
- **FR-027**: Models with no heart-rate sensor MUST show the feature as unsupported by the
  model, distinct from unavailable on this phone.
- **FR-028**: Sensing MUST be startable, stoppable and observable from adb, and the
  current state — measuring, confident, stopped, and why — MUST appear in the state dump
  without the values themselves.
- **FR-029**: It MUST be possible to verify from adb that what reached the health store is
  what was measured, by counting stored readings over a window without printing them.

## Key Entities

- **Reading**: A heart rate at an instant, with its confidence and the route it came from.
  Not interchangeable between routes.
- **Sensing state**: Whether the accessory is being asked to measure, at what cadence, and
  why it stopped.
- **Confidence**: The accessory's own statement of how much a reading is worth. The gate
  between a number that may be shown and one that may not.
- **Health store link**: The user's permission and the record of what has been written, so
  the same instant is not recorded twice.

## Success Criteria

- **SC-001**: A user wearing the buds sees a trustworthy heart rate within 30 seconds of
  enabling the feature, and never sees an untrustworthy one before it.
- **SC-002** *(deferred, unmet — see [deferred-verification.md](./deferred-verification.md))*:
  Across a 10-minute session at rest, no displayed reading differs from a reference
  heart-rate monitor by more than 5 BPM for more than 5 % of the session.
- **SC-003**: Readings recorded to the system health store are readable by an independent
  health app, attributed to GreenPods and to the accessory that measured them, with times
  correct to the second.
- **SC-004**: No reading below the confidence threshold is ever displayed or recorded,
  across every test session.
- **SC-005**: Heart-rate values appear in no diagnostic or bug-report output, verified by
  inspecting every such path after a measuring session.
- **SC-006** *(deferred, unmet — see [deferred-verification.md](./deferred-verification.md))*:
  With the feature disabled, the accessory's battery drain over an hour is
  indistinguishable from the app not being installed.
- **SC-007**: The whole feature can be driven and observed from adb, including starting and
  stopping the sensor and reading its state.
- **SC-008**: On a model or phone where heart rate is unreachable, the feature is visible,
  locked, and carries the reason for which of the two it is.
- **SC-009**: Over a measuring session, the number of readings stored in the health store
  equals the number displayed as trustworthy — no gaps, and no duplicates after an
  interruption.

## Out of Scope

- **Fitness features.** No calories, no training load, no zones, no workout tracking. This
  ships a pulse, and stays a pulse.
- **Notifications about heart rate.** High or low alerts imply clinical meaning the project
  cannot stand behind.
- **Historical analysis, trends and charts beyond the current session.** Worth doing later;
  not what makes this valuable now.
- **Reading heart rate written by other apps.** The integration writes; it does not consume.
- **Heart rate on any model that does not have the sensor.** No estimation, ever.

## Assumptions

- **A reference monitor is used before this ships.** Values decoded so far are plausible
  and behave correctly, but have not been compared against a known-good device. SC-002
  cannot be claimed without that comparison, and the project's own rule against inventing
  protocol facts applies equally to trusting an unvalidated one.
- **The confidence threshold is determined from measurement**, not chosen in advance. The
  observed capture had confidence around 20 while readings were wrong and above 150 once
  they converged; the actual threshold is set from data across sessions and models.
- **Sensing runs while the buds are worn and the feature is enabled**, including with the
  app in the background — consistent with the decision that the protocol session is held
  while the accessory is connected. It does not run while the buds are in the case.
- **One reading per second** is the starting cadence, as used in the capture. If battery
  cost proves significant this becomes a user-visible choice rather than a silent change.
- **The system health store is Health Connect**, the platform's health data store, present
  on current Android and unavailable on older devices — which FR-020 covers. Writing to it
  is what "goes into Google Health" means on Android today; the older Fit APIs are
  retired.
- **The Health Connect integration follows the pattern already proven in `xoss-health`**
  (`android/app/.../HealthWriter.kt`): availability checked before use rather than assumed,
  a per-reading client identity for deduplication, readings batched into records, the
  measuring device recorded as the source, and a rationale activity declared so the system
  health settings can show why access is wanted. That project also reads back only its own
  records when verifying, which is the technique FR-029 relies on — otherwise another app
  writing heart rate in the same window is counted as ours.
- **Existing work is reused**: the standard-profile route, the transport gate, the feature
  locking model and the adb surface already exist and are extended.
- **No Powerbeats Pro 2 is available to test with.** User Story 4 is therefore built and
  tested off-device against a fake source, and ships as *implemented and unverified on
  hardware*. SC-003 is not claimed for that route. The work is still worth doing —
  `HeartRateGattSource` exists, is tested, and today has no caller — but the distinction
  between "tested" and "verified" is recorded rather than blurred.

  **What was tested instead**, so the claim is checkable rather than a disclaimer:

  - `HeartRateGattParserTest` pins the `0x2A37` characteristic decode against the
    Bluetooth SIG Heart Rate Service specification — both BPM widths, the endianness of
    the wide form, truncated values, the plausibility edges at 25 and 250 BPM, and the
    flag bits that must be ignored rather than misread as a width selector. These byte
    layouts come from the specification, **not from a capture**; that is the difference
    from the AAP route, where every fixture is a real device's output.
  - `HeartRateControllerTest` drives the controller through a fake `GattHeartRateReadings`
    on a `POWERBEATS_PRO_2` state: the route reaches `Measuring`, every reading records
    `source = GATT`, and a GATT source offered to a model without that feature is ignored
    rather than used as a fallback (FR-004).
  - `PodStateTest` covers the model carrying both routes — the live one is preferred, and
    with neither live the lock names the preferred transport rather than the last tried.

  What none of that establishes: that a Powerbeats Pro 2 actually advertises `0x180D`,
  that its notifications arrive at the cadence assumed, or that the connection lifecycle
  in `HeartRateGattSource` — service discovery, descriptor write, reconnection — behaves
  against real hardware. Those need the device.
- **The heart-rate report's timestamp is not a wall clock.** The captured value is about
  15 hours, which is an accessory-local counter, not a date. Times written anywhere are
  derived by anchoring that counter against the phone's clock once per session. Recorded
  here because reading it as an epoch would put every reading fifteen hours into the past
  and nothing downstream would notice.
