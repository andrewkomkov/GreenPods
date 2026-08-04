# Deferred verification: three measurements this feature does not claim

**Status**: open, and deliberately outside the scope of `003-heart-rate`.

**Deferred**: 2026-08-04, at the point the feature was working on hardware and the
hardware became unavailable.

The heart-rate feature is implemented and verified working on a Pixel 8 with AirPods
Pro 3. Three things were never measured. They are recorded here rather than left as
open tasks on a finished feature, because "not yet measured" and "not finished" are
different states and conflating them hides which one is true.

**Nothing below is claimed by the shipped feature.** `spec.md` marks SC-002 and SC-006
as unclaimed, and the confidence threshold carries its provenance in `Settings.kt`. If
that ever stops being true, this file is the thing that was skipped.

---

## 1 — Accuracy against a reference monitor (was T071, SC-002)

**What it establishes**: that the number shown is the user's heart rate, rather than a
plausible number that behaves like one.

**Why nothing substitutes for it**: the decoders are pinned against real captures, the
series behaves correctly under exertion, and 81 bpm at rest is entirely believable. None
of that is a comparison. The confidence byte is *named* from behaviour rather than from
documentation — if it means something adjacent to confidence, the gate is wrong in a way
that looks right, and only a second instrument reveals that.

**Procedure**: wear the buds and a reference heart-rate monitor simultaneously for ten
minutes at rest. Record both series. Name the reference device. Compare, and record the
comparison in `docs/protocol-research.md` as a capture rather than a summary.

**Passes when**: no displayed reading differs from the reference by more than the margin
SC-002 states.

## 2 — Battery cost (was T072, SC-006)

**What it establishes**: what the feature costs the person using it.

**Why nothing substitutes for it**: this is the trade-off Apple made when it chose to run
the sensor only during workouts. It is a property of the hardware and cannot be reasoned
about from the protocol. The cadence is a user-visible setting *specifically* so the cost
can be measured at more than one interval before any default is defended.

**Procedure**: an hour with the feature disabled against an hour with the app not
installed, to establish the floor; then an hour each at 1 s and at a slower cadence.
Record all of it in `docs/protocol-research.md`.

**Passes when**: the disabled case is indistinguishable from the app not being installed,
and the enabled cost is stated as a number rather than an impression.

## 3 — The expressive pass, on a screen (was T081, and the visual halves of T077/T079)

**What it establishes**: how the feature feels, which is the only thing the Material 3
Expressive pass was ever about.

**Why nothing substitutes for it**: the code-checkable half is done and tested — motion
goes through `GreenPodsMotion`, the heart icon's period is the measured rate, the card is
announced as a state, emphasis is decided in `HeartRateUi` where a test can reach it. No
unit test sees a clipped label at the largest font size, a contrast failure in dark
theme, or a transition that reads as a glitch.

**Procedure**: with the buds in, walk enable → settle → measure → uncertain → off. Repeat
with dark theme, with the largest font size, and with TalkBack on. Record a short screen
capture. Then walk the flows again and fix whatever makes you hesitate — the walk-through
decides, the screenshots only corroborate.

**Passes when**: a stranger handed the phone understands that it is measuring, that the
number is not yet trustworthy, and that they may switch it off, without being told.

---

## Two smaller things, also outstanding

- **The live privacy sweep** (`quickstart.md` §6). Its network claim is already proved
  more strongly than the sweep would prove it — `NoReadingLeavesTheDeviceTest` establishes
  by source scan that one outbound client exists, that it is a GET with no request body,
  and that it names no heart-rate type. What remains is running the greps against a live
  dump, and confirming in a health app that written timestamps sit at the session rather
  than fifteen hours earlier (the anchoring is unit-tested; the end-to-end result is not).
- **The Powerbeats Pro 2 route** has never run on hardware, because no such hardware
  exists for this project. `spec.md`'s Assumptions record what was tested instead.
