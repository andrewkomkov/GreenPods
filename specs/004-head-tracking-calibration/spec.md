# Feature Specification: Head Tracking Calibration

**Feature Branch**: `004-head-tracking-calibration`

**Created**: 2026-08-05

**Status**: Amended 2026-08-09 — the measurement was taken, and it narrows this feature to one
axis. See "What the measurement found"

**Input**: User description: "Мастер калибровки head tracking. Экран в приложении, который ведёт пользователя через размеченные позы головы («смотри прямо», «подбородок к плечу», «ухо к плечу»), удерживает отсчёт, ловит плато в потоке ориентации и выводит масштаб отдельно для каждой оси — вместо одной общей константы HeadPoseMapper.SCALE. Обоснование из живой съёмки на AirPods Pro 3 (Pixel 8, Android 17, 2876 сэмплов за 143 с): поля прошли очень разные диапазоны — o1 6296 единиц, o2 35585, o3 12175, что при текущей общей SCALE даёт 195° наклона головой, физически невозможных. Значит либо оси назначены не тем полям, либо у осей разный масштаб. Мастер должен также проверять назначение осей, а не только выводить числа, и честно сообщать, когда данных не хватает для вывода (неудержанная поза, отсутствие плато)."

## Why This Exists

Head tracking currently converts the accessory's raw orientation integers to degrees
with one shared constant applied to all three axes. That constant is documented in the
code as an approximation, and it is not one that can be repaired by choosing a better
number.

### The original justification, and why it was withdrawn

This spec was first written on 2026-08-05 around a capture that showed `o2` traversing
35 585 units — 195° of pitch at the shared scale, which no neck can perform. Hours later,
PR #6 explained that number and removed it as evidence: the decoder was reading the pose at
**absolute packet offsets**, and the `0x17` body is protobuf whose sequence-counter varint
grows from one byte to two at 127→128, shifting the input report — and everything after it —
partway through every stream. Half of those reads straddled two adjacent values. The 195°
was an artefact of where the bytes were read, not of how they were scaled. That is fixed,
and `HeadTrackingOffsetTest` pins it against four captured frames spanning the boundary.

The original table is left out of this section deliberately rather than corrected in place.
It measured a bug, and a number that has been explained by something else does not become
weaker evidence for its original claim — it stops being evidence for it at all.

### What actually justifies the feature

**The three values do not vary independently.** From a scripted capture — nod, then shake,
then tilt, separated by stillness — nodding moved offsets 24 and 22, shaking moved 22 most,
and tilting moved 24 and 20. No offset belongs to one axis
(`docs/protocol-research.md:520-528`). A per-axis linear scale is therefore not a constant
that is wrong; it is a *model* that does not fit. The obvious alternative was tried and
rejected too: four consecutive int16 at offsets 26/28/30/32 hold a near-constant norm, but
converting them to Euler angles does not make nodding move pitch.

And the accessory will not settle it either. The devmotion descriptor declares report 1 as a
timestamp followed by one opaque vendor blob, with no Logical or Physical range and no unit
(verified byte by byte). `HidReportDescriptor` already honours a declared range where one
exists — the heart-rate service declares one and it is pinned by a test. This service
declares nothing.

**So the gap is not a constant. It is a measurement nobody has taken.** Every capture in
this project is unlabelled: values were recorded while a head moved, with no record of what
the head was doing. The cross-coupling claim above is itself unpinned prose, written from one
scripted session that no fixture reproduces. Deriving anything from an unlabelled plateau is
fitting, not measuring, which Principle V forbids — and labelling a pose means asking the
person wearing the earbuds, at the moment they are holding it.

That is what this feature is for: **to produce the first labelled head-tracking capture**,
and to report what each pose actually moved. A per-axis scale is emitted only where the poses
genuinely separate the raw fields. On present evidence they will not, and the wizard will say
so — which is a result, not a failure, and is the outcome this spec now expects.

### What the measurement found — 2026-08-09

The wizard was run against AirPods Pro 3 on a Pixel 8 with the channel open, and it did the
job this section asked of it. Two things came out, and the second one narrows this feature to
a single axis. Both are recorded in full in `docs/protocol-research.md`; the capture is
`core/bluetooth/src/test/resources/aap/head-tracking-poses.txt`.

**The response matrix, at last.** Deltas from the neutral hold:

| pose | `o1` | `o2` | `o3` |
|---|---:|---:|---:|
| yaw, 90° right | 668 | **+14398** | −8673 |
| pitch, chin down | −719 | −711 | **+4301** |
| roll, ear to shoulder | −1171 | **−8076** | +3623 |

The verdicts were `INCONCLUSIVE`, `MISMATCHED` and `MISMATCHED` — **not `CROSS_COUPLED`**.
Two of the three poses produced a dominant field, so the fields *do* separate and a per-axis
scale is not the unfittable model this section expected. What is wrong is the assignment:
pitch responds in `o3`, roll in `o2`, and `o1` — the field mapped to yaw — moved less than the
plateau tolerance for every pose.

**The accessory was already sending gravity, and nobody had looked.** The devmotion report is
58 bytes; five int16 of it were decoded and the rest never examined. A search for a stable
norm found a three-vector at offsets **44/46/48**, norm 1034 (≈2¹⁰), spread 0.37 %. Over a
labelled sequence it did not move during the 90° turn — nine units on one axis, while `o2` and
`o3` moved by twelve and seven thousand — and moved by 21° and 29° for the two tilts. Only
gravity behaves that way: rotation *about* the gravity axis cannot change gravity's direction.

**So this feature is now needed for yaw, and for nothing else.**

- **Pitch and roll need no calibration at all.** They are the angle between the current gravity
  vector and the one recorded upright — trigonometry, with the unknown scale cancelling in the
  ratio. That work belongs to `specs/006-gravity-orientation`, not here.
- **Yaw cannot be obtained from gravity at any price.** That is geometry, not a decoding gap,
  and it is what keeps this feature alive.
- **It explains the 195°.** The app reads `o2` for pitch, and `o2` is dominated by yaw.

The paragraph above about offsets 26/28/30/32 is now **disproved rather than merely rejected**:
that norm looked constant because offset 32 is nearly constant and about a hundred times larger
than its neighbours, so a large constant plus small noise had a stable norm trivially. Recorded
because it is a trap that cost this project two sessions.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Derive a scale that matches a real head (Priority: P1)

Someone with head tracking working, but reading obviously wrong angles, opens
calibration. The app asks them to hold a small number of named poses, counting down for
each. It watches the orientation stream, finds where the values held steady, and derives
a separate scale for each axis. From then on the angles the app reports match the head
that produced them.

**Why this priority**: This is the whole point. Without it every angle in the app,
and every gesture threshold expressed in degrees, rests on a constant already shown to
be impossible. It is also the smallest slice that delivers value on its own.

**Independent Test**: Run the wizard end to end on a device with a live head-tracking
session, then confirm a deliberate 90° turn is reported as roughly 90° where before it
was reported as something else.

**Acceptance Scenarios**:

1. **Given** a live head-tracking session and a person willing to hold poses, **When** they complete every step of the wizard, **Then** the app stores one scale per axis and reports angles using them.
2. **Given** a completed calibration, **When** the person returns to the head-tracking screen, **Then** the poses they hold read as plausible angles and no axis can report an angle a neck cannot reach.
3. **Given** a completed calibration, **When** the person opens calibration again, **Then** the app shows what is currently stored and offers to re-run or discard it.
4. **Given** a calibration stored for one accessory, **When** a different accessory model is connected, **Then** that calibration is not applied to it.

---

### User Story 2 - Catch an axis that is not what it claims (Priority: P2)

The wizard does not assume the mapping it was given. Because each pose moves a known
axis of the head, the wizard can check which raw field actually responded. If turning the
head moved the field the app calls pitch, the wizard says so plainly instead of quietly
scaling the wrong axis.

**Why this priority**: A scale derived on top of a wrong axis assignment is worse than no
calibration, because it looks calibrated. The capture above cannot distinguish "wrong
scale" from "wrong axis", so a feature that only produced numbers would bake the
ambiguity in. Deliverable on its own: the check is worth running even if the person then
abandons the wizard.

**Independent Test**: Run the wizard, and confirm it names, for each pose, which raw
field moved most — and that it refuses to finish when the field that moved is not the one
the app expects for that pose.

**Acceptance Scenarios**:

1. **Given** a pose that moves one head axis, **When** the wizard analyses the held segment, **Then** it reports which raw field responded most strongly to that pose.
2. **Given** a pose where the responding field is not the one currently mapped to that axis, **When** the wizard completes its analysis, **Then** it reports the mismatch and does not silently store a scale as though the mapping were confirmed.
3. **Given** a pose where two fields responded comparably, **When** the wizard analyses it, **Then** it reports the result as inconclusive rather than picking the larger.

---

### User Story 3 - Refuse rather than guess (Priority: P3)

When someone moves during a pose, cuts it short, or the stream stops mid-way, the wizard
says which pose failed and why, and offers to redo that pose. It never emits a constant
it could not measure.

**Why this priority**: This is Principle V applied to a screen. It is listed third only
because the two above must exist for it to have anything to refuse; in practice it is
what makes the result trustworthy.

**Independent Test**: Deliberately move during one pose and confirm the wizard names that
pose as unusable, keeps the poses that succeeded, and produces no number for the failed
axis.

**Acceptance Scenarios**:

1. **Given** a pose during which the orientation never settles, **When** the countdown ends, **Then** the wizard reports that pose as not held and offers to repeat it.
2. **Given** a pose that was held for less than the required time, **When** the wizard analyses it, **Then** it is treated as not held.
3. **Given** one failed and two successful poses, **When** the person chooses to finish anyway, **Then** the wizard stores nothing for the failed axis and states which axis remains uncalibrated.
4. **Given** the head-tracking stream stopping mid-wizard, **When** the person is partway through, **Then** the wizard explains that the stream stopped and no partial result is stored as though complete.

---

### Edge Cases

- **The transport is gated.** Calibration needs a live orientation stream, which needs the Apple protocol channel. With no channel the entry point is shown locked with the reason, never hidden (Principle II), and never presented as a fault in the earbuds.
- **A bud leaves an ear mid-pose.** Orientation may stop or jump. The affected pose is reported as not held rather than analysed.
- **The person cannot perform a pose.** Neck mobility varies and some people cannot reach the reference angles. Each pose is skippable, and skipping leaves that axis uncalibrated and clearly labelled as such rather than blocking the whole wizard.
- **A pose is held, but at an angle far from the one requested.** The wizard cannot see the true angle, only that the values were steady. This is the feature's central limitation and is stated on screen, not hidden.
- **Two poses produce contradictory scales for the same axis.** Reported as inconclusive; nothing is averaged into a number that neither measurement supports.
- **A calibration is already stored.** Re-running replaces it only when the new run completes; a failed re-run never destroys a good stored calibration.
- **The accessory is swapped mid-wizard.** The run is abandoned rather than attributed to whichever accessory happens to be primary at the end.
- **Derived scale is wildly implausible** (for example, implying a full turn from a few hundred units). Reported as a suspect result with the numbers shown, not stored silently.

## Requirements *(mandatory)*

### Functional Requirements

**Running the wizard**

- **FR-001**: The app MUST offer a calibration entry point reachable from the head-tracking area of the app.
- **FR-002**: The entry point MUST be shown in a locked state, with the reason attached, whenever the orientation stream cannot be started; it MUST NOT be hidden.
- **FR-003**: The wizard MUST guide the person through a sequence of named poses, each with a plain-language instruction naming the movement and the reference angle it approximates.
- **FR-004**: The wizard MUST include a neutral reference pose ("look straight ahead") and at least one pose per axis of head movement.
- **FR-005**: Each pose MUST display a visible countdown for the hold period, so the person knows when the measurement is being taken.
- **FR-006**: Each pose MUST be individually skippable and individually repeatable without restarting the whole wizard.
- **FR-007**: The wizard MUST be abandonable at any point, and abandoning it MUST leave any previously stored calibration untouched.

**Measuring**

- **FR-008**: The wizard MUST detect, from the orientation stream alone, the segment during which the values were held steady — it MUST NOT assume the person obeyed the countdown exactly.
- **FR-009**: A segment MUST count as held only when every axis stays within a stated tolerance for at least a stated minimum duration.
- **FR-010**: The wizard MUST measure, for every pose, the response of **every** raw field as the difference between that pose's held values and the neutral reference pose's held values, never from absolute values. The full response set — not only the largest — MUST be recorded and exportable.
- **FR-010a**: The wizard MUST derive a scale for an axis **only where one field's response to that axis's pose dominates the others** by a stated margin. Where no field dominates, or where the same fields respond to every pose, the wizard MUST report the axes as cross-coupled and MUST NOT store a scale. *On the evidence in `docs/protocol-research.md` this is the expected outcome on AirPods Pro 3, not an error path.*
- **FR-011**: Where a scale is derived, it MUST be derived and stored per axis independently. It MUST NOT apply one axis's result to another.
- **FR-011b**: The wizard MUST NOT derive or store a scale for pitch or roll once gravity-derived orientation is available for them (`specs/006-gravity-orientation`). Measured 2026-08-09: gravity gives those two axes as real angles with no pose held and no reference angle assumed, so a scale fitted from "chin toward the shoulder is about 45°" would be a worse number competing with a better one. Yaw is unaffected — gravity cannot see it.
- **FR-011a**: Where an accessory's HID report descriptor declares a physical range and unit for an orientation field, that declaration MUST take precedence over any stored calibration. The accessory describing itself outranks a measurement inferred from a person holding a pose.
- **FR-012**: The wizard MUST report, for each pose, which raw field responded most strongly to that pose.
- **FR-013a**: The currently mapped assignment `O1→YAW, O2→PITCH, O3→ROLL` is **known wrong** as of 2026-08-09: pitch responds in `o3`, roll in `o2`, and `o1` responds to nothing measurable. It is left in place as the thing FR-013 compares against rather than silently corrected, because correcting it from one session's poses would replace a wrong assignment with an unverified one — and for pitch and roll the question is moot once gravity supplies them.
- **FR-013**: When the responding field for a pose is not the field currently mapped to that axis, the wizard MUST report the mismatch and MUST NOT store a scale for that axis as though the mapping were confirmed.
- **FR-014**: When two or more fields respond comparably to one pose, the wizard MUST report that pose as inconclusive.

**Refusing**

- **FR-015**: When no held segment can be found for a pose, the wizard MUST report that pose as not held, name it, and offer to repeat it.
- **FR-016**: The wizard MUST NOT produce, store, or display a scale for any axis whose pose was skipped, not held, or inconclusive.
- **FR-017**: When the orientation stream stops mid-wizard, the wizard MUST say so and MUST NOT store a partial run as a completed one.
- **FR-018**: When a derived scale is outside a plausible range for a human head, the wizard MUST present it as suspect, with the underlying numbers visible, and MUST require explicit confirmation before storing it.
- **FR-019**: The wizard MUST state on screen that its reference angles are approximate and that its accuracy depends on how closely the poses were performed.

**Using and keeping the result**

- **FR-020**: A stored calibration MUST be applied to the angles the app reports, in place of the shared constant, for the accessory it was measured on.
- **FR-021**: A stored calibration MUST be associated with the accessory model it was measured on and MUST NOT be applied to a different model.
- **FR-022**: The app MUST fall back to the current documented approximation, clearly labelled as uncalibrated, for any axis or accessory with no stored calibration.
- **FR-023**: The person MUST be able to view what is currently stored and discard it, returning to the uncalibrated fallback.
- **FR-024**: Gesture thresholds expressed in degrees MUST continue to be interpreted in degrees after calibration, so that calibration corrects their real-world meaning rather than requiring them to be re-tuned.

**Driveable and observable (Principle VI)**

- **FR-025**: The whole wizard MUST be runnable from the debug command surface without touching the screen, including starting it, advancing poses, skipping, and finishing.
- **FR-026**: Synthetic orientation samples MUST be injectable so that every outcome — held, not held, inconclusive, axis mismatch, implausible result — is reproducible without a person, without earbuds, and without a head.
- **FR-027**: The state dump MUST expose the current calibration, per axis and per accessory, including which axes are uncalibrated and why.
- **FR-028**: The measured result MUST be exportable in machine-readable form, so a validated calibration can be recorded in `docs/protocol-research.md` as evidence rather than retyped.
- **FR-029**: Every command added MUST be documented in `docs/adb.md` with a runnable example, in the same change.

**Purity (Principle III)**

- **FR-030**: The plateau detection and the scale derivation MUST be free of I/O and of Android dependencies, and MUST be unit-tested off-device against sample sequences that are **declared synthetic where they are synthetic**, plus the real captured frames in `head-tracking-varint-boundary.txt` for anything touching the decode path.
- **FR-030a**: A run of the wizard MUST be able to emit a labelled sample series as a checked-in fixture, in the style of `hr-report-series.txt`, carrying a provenance header naming device, firmware, host, date, service and rate. *The capture this spec was originally written around is not in the repository and was taken through the decoder bug described above; no command in the app can currently produce a replacement. This feature is the instrument that closes that gap, which is why its own tests may not depend on a capture predating it.*

### Key Entities

- **Calibration pose**: A named instruction, the head axis it exercises, its approximate reference angle, and its required hold duration.
- **Held segment**: A run of consecutive orientation samples that stayed within tolerance long enough to count as a pose, with its per-field median values and its duration.
- **Axis calibration**: One axis's derived scale, the pose it came from, which raw field responded, and a verdict — measured, skipped, not held, inconclusive, mismatched, or suspect.
- **Accessory calibration**: The set of axis calibrations belonging to one accessory model, with when it was measured.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person can complete the full wizard in under three minutes, including reading the instructions.
- **SC-002**: *Conditional on the axes separating.* Where the wizard derives scales, a deliberate quarter-turn of the head is afterwards reported within 15° of a right angle, where the uncalibrated app is wrong by more than double that.
- **SC-003**: *Conditional on the axes separating.* Where scales are stored, no axis reports an angle beyond what a human neck can reach.
- **SC-003b**: *Settled 2026-08-09, and not as expected.* The axes **did** separate: two of three poses produced a dominant field and the run reported `INCONCLUSIVE, MISMATCHED, MISMATCHED` rather than `CROSS_COUPLED`. The labelled capture exists. What the run also produced — a gravity vector nobody had decoded — is worth more than the scale it was looking for, which is the argument for building instruments rather than guessing constants.
- **SC-003a**: Where the axes do **not** separate, the wizard says so, names the fields that responded to each pose, stores no scale, and leaves the labelled capture behind. This is a success, not a failure: it converts a claim currently resting on one unreproducible session into an artefact anyone can re-run, and it is the outcome present evidence predicts.
- **SC-004**: When a pose is deliberately not held, the wizard reports that pose as unusable in 100% of attempts and emits no number for it.
- **SC-005**: Every outcome the wizard can reach is reproducible from adb with injected samples alone, with no earbuds present. Verified 2026-08-08 on an Android 17 emulator. "No earbuds present" means none in range, not none ever paired: an injected sighting that resolves to no bond is discarded as a stranger's before it reaches the wizard, so a phone that has never paired an Apple or Beats accessory cannot run this. That filter is `BondedPodResolver` and it is deliberate — it exists because a stranger's AirPods once took the bonded key — so the constraint is recorded here rather than worked around.
- **SC-006**: A person who reads only the final screen can tell which axes were measured, which were not, and why — without opening diagnostics.
- **SC-007**: Re-running calibration and abandoning it midway leaves the previously stored result unchanged in 100% of attempts.

## Assumptions

- **Reference angles are nominal, not measured.** "Chin toward the shoulder" is treated as approximately 90° of yaw and "ear toward the shoulder" as approximately 45° of roll. The app cannot observe the true angle, so this is the feature's accuracy floor; FR-019 requires saying so rather than implying precision. Getting from "obviously impossible" to "roughly right" is the goal here, not metrology.
- **Calibration is per accessory model, stored locally on the device.** No account, no sync, no upload. The research need — adopting validated constants as project defaults — is served by the export in FR-028 rather than by any network path.
- **The existing head-tracking session model is reused.** Calibration is another consumer of the same orientation stream, with the same start-on-collect, stop-on-leave lifetime, so it costs the earbuds nothing once the screen is closed.
- **Three axes, as currently decoded.** This feature calibrates and checks the three orientation fields the decoder already reads. Re-deriving *where* those fields sit inside the frame is a separate open question, recorded in `docs/protocol-research.md`; FR-012 and FR-013 exist so that this feature reports evidence bearing on it instead of hiding it.
- **The two acceleration fields are out of scope.** Nothing in the app converts them to physical units today.
- **A person can hold a pose for a few seconds.** Poses that cannot be held are covered by the skip path in FR-006 rather than by an alternative measurement method.
