# Phase 0 Research: Head Tracking Calibration

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-08-06

## The headline: the spec's premise has been superseded by the repository's own notes

The spec was written on 2026-08-05 in PR #5. Later the same day, PR #6 — *"fix: make heart
rate start, and stop reading the head pose from a moving offset"* — changed two things that
the spec could not have known and that between them invalidate its central mechanism.

This section is first because nothing below it matters if it is skipped.

### R-1: The 195° that motivates the feature was a decoder bug, and it is fixed

`docs/protocol-research.md:490-509` records that the decoder read the pose at **absolute
packet offsets 43/45/47**, and that those offsets do not hold still:

> The `0x17` body is protobuf, field 1 is a sequence counter, and a varint takes one byte up
> to 127 and two from 128 — so the input report, and everything else after that field, shifts
> a byte partway through every stream. At 25 Hz that is about five seconds in. […] the shipped
> decoder read one pair of bytes for the first few seconds of a stream and a different,
> one-byte-shifted pair thereafter, for the same physical pose — and half of those reads
> straddled two adjacent values, **which is where an impossible 195° of tilt came from**.

The spec's evidence table — `o1` 6 296 units, `o2` 35 585, `o3` 12 175, giving 195° of pitch —
was measured through that bug. It is not evidence about the scale. It is evidence about the
offsets, and that finding has already been acted on: the decoder now reads from the input
report the protobuf delimits, pinned by `HeadTrackingOffsetTest` against
`core/bluetooth/src/test/resources/aap/head-tracking-varint-boundary.txt`.

**Decision**: the spec's "Why This Exists" section must be rewritten before this feature is
built. Not because the feature is unnecessary — see R-3 — but because it is currently
justified by a number that has since been explained by something else. Building against a
superseded justification is how the last wrong constant got its year.

### R-2: The accessory does not declare a scale, and now we know that for certain

`docs/protocol-research.md:511-518`:

> The devmotion descriptor (96 bytes, service `0x10`, name `devmotion6`) declares report 1 as
> an 8-byte timestamp on usage `FF15:0004` […] followed by an **opaque vendor blob** on usage
> page `FF0C`. It breaks out no orientation fields, so there is no Logical/Physical range and
> no unit to read: the accessory does not say what its motion numbers mean.
>
> `HidReportDescriptor` now parses Logical/Physical Minimum and Maximum and the Unit Exponent,
> so a descriptor that *does* declare a scale can be honoured. This one does not.

Verified against the bytes rather than against the prose. The 96-byte descriptor in
`core/bluetooth/src/test/resources/aap/hid-descriptors-live.txt` was walked item by item and
contains no Logical Minimum/Maximum (`0x14/0x15/0x16`, `0x24/0x25/0x26`), no Physical range,
no Unit and no Unit Exponent anywhere. Report 1 is an 8-byte timestamp on `FF15:0004` followed
by **one opaque 182-byte vendor field** on `FF0C:0x0D`. By contrast the heart-rate service in
the same capture does declare `26 FF 00` (logical 0..255), and that declaration is pinned by
`HidReportDescriptorTest.kt:124-142` — so the machinery works and this descriptor simply says
nothing.

Two corrections to how this is usually described in conversation:

- The class that parses HID items is **`HidReportDescriptor`**, not `HidDescriptorParser`. The
  latter parses Apple's protobuf/property blob and never looks at a HID item.
- **The `Unit` global (tag 6) is not parsed** — only Logical/Physical Minimum and Maximum and
  the Unit Exponent are (`HidReportDescriptor.kt:260-269`; tag 6 falls through to `else`). So
  the code can honour a declared *range* but cannot tell degrees from radians. The wording in
  `docs/protocol-research.md:518` is precise about this and should not be widened when quoted.

**Decision**: the cheap alternative to a wizard — read the units off the descriptor — has been
tried and is closed on this model. The parser support exists, so if a future firmware or a
different model declares a range, it will be honoured without this feature. Calibration is the
fallback for accessories that do not describe themselves, not the first resort — and that
ordering should be **implemented, not merely stated**: `HidReportField.hasPhysicalScale` must
outrank a stored calibration wherever it is true. Nothing implements that precedence today, and
the plan carries it as a task.

**Alternatives considered**: deriving scale from the acceleration fields (out of scope per the
spec's assumptions, and nothing in the app converts them to physical units); deriving it from
a known-good reference sensor on the phone (needs the wearer to keep phone and head aligned,
which is a harder pose to hold than the ones the wizard already asks for).

### R-3: The three values are cross-coupled, so a per-axis scale is the wrong model

This is the finding that changes the design, and it is stated plainly in
`docs/protocol-research.md:520-535`:

> - They are signed 16-bit, little-endian, and they move with the head.
> - **They are cross-coupled.** Over a scripted capture — nod, then shake, then tilt,
>   separated by stillness — nodding moved offsets 24 and 22; shaking moved 22 most; tilting
>   moved 24 and 20. No offset belongs to one axis.
>
> That last point is why `HeadPoseMapper.SCALE` cannot be repaired by choosing a better
> constant: **a per-axis linear scale is the wrong model** for values that do not vary
> independently.

**How well-evidenced is that, actually?** It has to be asked, because R-1 above dismisses the
spec's motivating numbers as unpinned, and it would be dishonest to then accept a claim that
overturns the feature without holding it to the same standard.

The answer is: **the cross-coupling claim is a recorded device measurement with no fixture
behind it.** It appears twice, both times as hand-written prose —
`docs/protocol-research.md:526-528` and the KDoc at `AapDecoder.kt:434-439` — and no test,
fixture or stored capture exercises the scripted nod/shake/tilt session it describes. It sits
at exactly the same evidentiary level as the 2 876-sample figures.

What separates them is not their pedigree but what happened to them since. The 195° has a
*pinned explanation*: `HeadTrackingOffsetTest` demonstrates that the same report rewrapped with
a one-byte and a two-byte sequence counter used to decode differently, and the doc's own varint
table locates the boundary at 127→128. So R-1 stands on tested ground. R-3 does not — it stands
on one person's scripted session, unreproducible from this repository.

**That does not weaken the conclusion; it sharpens what the feature is for.** A per-axis scale
must not be *assumed valid*, and it must not be *assumed invalid* either. The wizard is the
instrument that settles it, because a labelled pose is exactly the measurement the
cross-coupling claim was made from and never recorded. Its response matrix either reproduces
the coupling — in which case the claim is finally pinned by an artefact anyone can re-run — or
it does not, and the claim was wrong. Either outcome is worth more than the argument.

And the obvious next hypothesis has already been tested and rejected:

> A quaternion was the obvious hypothesis and it was tested — four consecutive int16 at
> offsets 26/28/30/32 do hold a near-constant norm (0.9695, spread 0.18%), but converting them
> to Euler angles does not make nodding move pitch or shaking move yaw, so that is not the
> head's orientation either.

**A contradiction inside the repository, found while checking that quotation.** The doc
describes offsets 26/28/30/32 as four consecutive int16 holding a near-constant norm. The
shipped decoder reads two of those same bytes as something else entirely —
`AapDecoder.kt:456-457` takes `horizontalAcceleration = le16(28)` and
`verticalAcceleration = le16(30)`. Both descriptions cannot be right about what those bytes
are, and neither is pinned by a test. Checked against the one real capture in the repository
(frame seq 126): `le16(26)=3, le16(28)=12, le16(30)=11, le16(32)=-31973`, giving a norm of
≈0.976 against 32768 — consistent with the doc's 0.9695, from a single frame, which
corroborates the *shape* of the claim and settles nothing about the conclusion.

**Decision**: out of scope for this feature, recorded here and to be added to
`docs/protocol-research.md` as an open question. It is exactly the kind of thing Principle IV
exists for, and it costs nothing to write down now versus rediscovering it later. The wizard's
export should carry all five decoded fields, not three, so a run of it also produces evidence
bearing on this.

The spec's FR-010 and FR-011 — derive and store *one scale per axis, independently* — describe
a computation that this evidence says cannot be valid. A wizard that ran today would find, for
every pose, that two fields responded comparably, and would correctly report `INCONCLUSIVE`
for all three axes. That is the honest outcome, and it is also a wizard that can never succeed
as specified.

**Decision**: keep the wizard, change what it produces. Its primary output becomes the thing
the project actually lacks — **labelled pose captures and a response matrix** — with a per-axis
scale emitted only in the case where the poses do separate the fields. The spec's own User
Story 2 already contains this instinct ("catch an axis that is not what it claims"); R-3 says
that story is not the secondary check, it is the feature.

**Alternatives considered, and why not yet**:

- *Solve a 3×3 linear map instead of three scalars.* This is the natural next model and the
  wizard's captures are exactly its input. Rejected **for this feature** because with three
  poses at nominal angles, the solve is exactly determined and unfalsifiable — every capture
  would produce a matrix that fits, including a wrong one. Principle V calls that a fitted
  number, not a measurement. The wizard should *collect the data that would make the solve
  honest* (more poses than unknowns, so the residual means something) before anything inverts
  a matrix.
- *Assume the values are a rotation in some unknown convention and search the conventions.*
  Twenty-four Euler orderings times sign conventions is a search over hypotheses fitted to one
  capture. Same objection.

### R-4: The motivating capture is not in the repository

The only head-tracking fixture is `head-tracking-varint-boundary.txt` — four consecutive
frames spanning the varint boundary. The 2 876-sample, 143-second capture the spec quotes is
**not** stored anywhere in the repo.

FR-030 requires the pure components to be unit-tested "against captured sample sequences,
including the 2026-08-05 capture that motivated this feature". As written, that requirement
cannot be satisfied — and it was measured through the offset bug, so pinning a new detector to
it would pin the detector to garbage.

Worse than absent: **nothing in the app can produce such a capture**. `gp --es cmd hid` prints
descriptors only — and `docs/adb.md:161-184` is explicit that descriptors carry no measurements
— while raw frames are reachable only as hex through `log.tag.AapTransport DEBUG`. There is no
command that emits a decoded orientation series.

**Decision**: the fixtures for `PlateauDetectorTest` and `CalibrationSolverTest` are synthetic
and *declared* synthetic, plus the real varint-boundary frames for the decode path. And the
wizard gains the missing instrument: a sample-series fixture written in the established style
of `hr-report-series.txt` — decoded columns alongside raw bytes, with a provenance header — is
one of its outputs. That closes FR-030 properly rather than by weakening it: the first real
head-tracking series in this repository is produced by a run of the feature whose tests need
it, through the fixed decoder, with its poses labelled.

Two supporting facts make this safe. `AapFixtures.text()` throws on a missing fixture by design
("it is a capture, not something to regenerate"), so a test naming a capture that does not
exist fails loudly rather than passing vacuously. And the varint-boundary fixture's own header
already sets the standard the new one must meet: source device, firmware, host, date, service,
rate, and the instruction not to edit it to make a test pass.

---

## Design research, given the above

### R-5: Where the pure logic goes, and what already exists to reuse

`HeadGestureDetector` (`core/bluetooth/.../head/HeadGestureDetector.kt`) already contains the
two ingredients plateau detection needs, in a form that shows the house style: a bounded
`ArrayDeque` window pruned by timestamp, and a peak-to-peak amplitude test over that window
(`detectOscillation`, `HeadGestureDetector.kt:125-145`). Its `detectTilt` is a hold test —
"the same direction, sustained past a duration" — which is structurally what a plateau is.

**Decision**: `PlateauDetector` is new code, not a refactor of the detector — and the decisive
reason is not stylistic. **Every entry point of `HeadGestureDetector` takes a `HeadPose` in
degrees** (`onPose(pose, atMillis)`), which means it sits *downstream of the very constant this
feature exists to replace*. Calibrating through it would make the measurement depend on the
uncalibrated scale. That is circular, and it is the kind of circularity that produces a
plausible number rather than an obvious failure.

Two further reasons it is not a parameterisation: `detectTilt` is threshold-crossing-plus-hold
and needs a direction and a threshold, where a plateau is variation-below-tolerance and needs
neither; and nothing in the detector tracks the three fields **jointly**, which the
axis-assignment check requires precisely because of the cross-coupling in R-3.

What *is* reused: the timestamped-deque shape, the peak-to-peak statistic with its comparison
inverted, the purity, and the test style.

### R-6: The tolerance and hold constants are provisional and must say so

FR-009 needs a stated tolerance and a stated minimum duration. Neither can be derived today:
the noise floor of a held pose is a property of the capture that is not in the repo (R-4).

There is one number to start from, and it is in this feature's own checklist:
`checklists/requirements.md:46` records that in the motivating session *"plateaus were found at
a tolerance of 900"*. It comes from the pre-fix capture (R-1), so it is a starting point and
not a measurement — but it is a recorded one, and it is six times wider than the ±150 that
looked reasonable before reading it. Shipping ±150 would have reported every pose as not held
and looked like a broken detector.

**Decision**: tolerance 900 units per field and a minimum hold of 2 000 ms, shipped as named
constants with that provenance attached, and overridable from adb the way
`hrConfidenceThreshold` already is — the precedent is explicit in `GreenPodsSettings.kt:49-68`,
where a provisional threshold is a setting *precisely so that calibrating it stays a
measurement rather than a rebuild*. At 25 Hz a 2 000 ms hold is 50 samples, enough for a median
to mean something. Both numbers are replaced by the first capture taken through the fixed
decoder — which, per R-4, this feature is the instrument for producing.

Stating a provisional number and its status is the pattern this project already uses. Choosing
one from a single capture and presenting it as measured is the pattern it forbids.

### R-7: Raw samples must reach the session

`HeadTrackingController.stream()` maps every sample through `HeadPoseMapper` before publishing
(`HeadTrackingController.kt:160`). Calibration derives the mapping, so it cannot consume values
that have already been through it.

**Decision**: `HeadTrackingController.Sample` grows a `raw: HeadTrackingSample`. One stream,
two consumers. The alternative — a second collector on `repository.aapEvents` — would mean two
sensor sessions in the earbuds for one screen, and the controller's start/stop lifetime exists
specifically to prevent a sensor being left running.

### R-8: Persistence follows the codec precedent

`GestureBindingCodec` (`core/data/.../settings/GestureBindingCodec.kt`) already encodes a
structured list into one preference string, decoded tolerantly. `DataStoreHidServiceMemory` is
the other precedent but is keyed by **address**, and calibration is keyed by **model**
(FR-021).

**Decision**: `HeadCalibrationCodec` with `DataStoreHidServiceMemory`'s *structure* — a small
interface over a **prefixed key per model**, `head_calibration_${model.name}` — and
`GestureBindingCodec`'s *codec discipline*: a flat, human-readable row format, with any
malformed row dropped rather than the whole record lost. `PodModel` is an enum with a stable
`name`, so the key is well-defined. Everything goes through the single `GreenPodsStore`
DataStore file, because a second store on the same file corrupts it.

**One thing deliberately not copied**: `GestureBindingCodec` fills unmentioned entries from
`Defaults`. Calibration must not. An absent calibration reads as **absent**, never as a default
scale — otherwise a fabricated constant re-enters through the back door, which is precisely
what `HidReportDescriptor.kt:53-57` warns about: *"a made-up scale is indistinguishable from a
measured one once it is past this point."*

**A divergence worth choosing on purpose**: the existing precedent keys by Bluetooth
**address** — one record per physical pair of earbuds. Physically that is arguably the better
key, since a calibration is a property of a head *and* a particular pair. The spec says model
(FR-021, acceptance scenario 4), so model it is; recorded here so that if it later proves
wrong, it is visible as a decision rather than as an oversight.

### R-9: Injection reuses the pipeline, and must not need the transport

FR-026 and SC-005 require every outcome to be reproducible with no earbuds. `PodRepository`
already exposes `onAapEvent`, which the debug receiver already calls
(`GreenPodsDebugReceiver.kt:658`), so synthetic `AapEvent.HeadTracking` frames can walk the
real pipeline.

But `HeadTrackingController.stream()` refuses before any sample arrives when the transport is
gated (`HeadTrackingController.kt:87-147`), so injection through the repository alone cannot
exercise the wizard on a phone without AAP.

**Decision**: `cal feed` addresses `CalibrationSession` directly, and the session takes its
samples from an injectable `Flow<HeadTrackingSample>`. Debug-only, and documented as the one
place the wizard runs without a live stream.

### R-10: Calibration silently changes what every gesture threshold means

`HeadGestureDetector.Config` thresholds are in degrees (12°, 15°, 20°) and those degrees come
from `HeadPoseMapper`. Changing the mapper changes what a nod has to be, physically.

That is the intent (FR-024) — the thresholds keep their real-world meaning instead of being
re-tuned around a wrong constant — but it means calibrating is also the single most likely
cause of "head gestures stopped working after I calibrated".

**Decision**: no code change; a task to verify gesture firing after calibration on hardware
(already in `quickstart.md` §7), and a note in the release changelog when this ships. Recording
it here because the connection is not visible from either file alone.

---

## Open items to settle before Phase 2

1. **The spec must be amended** (R-1, R-3). Its evidence is superseded and its FR-010/FR-011
   describe a computation the repository's own notes say is invalid. Amending it is the
   prerequisite for the Constitution Check passing — see the plan's re-check.
2. **FR-030 needs rewording** (R-4): the motivating capture is not in the repo, was taken
   through a fixed bug, and no command in the app can produce a replacement. The feature should
   be given the job of producing the fixture its own tests need.
3. **Tolerance and hold duration** (R-6) stay provisional until a capture through the fixed
   decoder exists — which this feature is the instrument for producing. Start at 900 units,
   from this feature's own checklist, not at a number that felt right.
4. **A descriptor that declares a physical range must outrank a stored calibration** (R-2). The
   machinery exists (`hasPhysicalScale`, `toPhysical`) and the precedence is implemented
   nowhere. Small, and it belongs in Phase A while the mapper is being opened up anyway.
5. **The repository contradicts itself about offsets 28 and 30** (R-3): the decoder calls them
   accelerations, the research notes describe them as part of a four-int16 near-unit-norm
   vector. Not this feature's job to settle, but its export should carry all five decoded
   fields so a run produces evidence either way, and the contradiction belongs in
   `docs/protocol-research.md` as an open question rather than in nobody's head.
