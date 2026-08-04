# Specification Quality Checklist: Heart rate

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-04
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Outcome — 2026-08-04

Every item above was already ticked before implementation; what follows is what the
implementation did to them, recorded because a checklist that is never revisited is a
formality.

- **"Success criteria are measurable" held, and two of them stayed unmet.** SC-002
  (accuracy against a reference monitor) and SC-006 (battery cost) were measurable exactly
  as written, and were not measured — the hardware became unavailable. They are recorded
  as unclaimed rather than quietly dropped, which is the behaviour the criterion was
  written to make possible.
- **"Requirements are testable and unambiguous" was the item that paid for itself.** FR-002
  ("the service id is discovered, not assumed") is why this feature works at all: the
  reference implementation everyone else follows hard-codes two candidate ids and ships a
  settings toggle to guess between them. Discovery survived a firmware whose heart-rate
  service names itself with a different key from its siblings.
- **"Edge cases are identified" was incomplete in a way no review would have caught.** The
  spec anticipated a sensor that never converges; it did not anticipate an accessory that
  never *announces* its sensor, which turned out to be the normal case for a channel opened
  at the wrong moment. That gap became FR-002's hardest constraint and three new tasks.

## Notes

**The confidence gate is the spine of this specification, not a detail.** It appears in
FR-006 through FR-009, in SC-004, and in the acceptance scenarios of three separate user
stories. This is deliberate. The decoding capture showed the sensor reporting 169 BPM from
someone sitting still, and the same capture showed the accessory publishing a confidence
value that was low for exactly those readings. A version of this feature without the gate
would work in a demo and be wrong in a way users cannot detect — and once the health-store
integration exists, wrong in a way that spreads to their other apps.

**Two requirements come from studying a working Health Connect integration** rather than
from the original request, and both would have been discovered late:

- FR-022, the rationale screen reachable from system health settings. Users approve or
  deny these permissions from outside the app entirely.
- FR-019 and FR-021, stable per-reading identity and device attribution. Without the first,
  an interrupted session duplicates data; without the second, a user cannot tell an
  earbud measurement from a chest strap in their own history.

**Validation against a reference monitor is recorded as an assumption, not a requirement**,
because it constrains the project rather than the product. SC-002 cannot honestly be
claimed until it happens, and the project's rule against inventing protocol facts applies
just as much to trusting a decoded one that has never been checked.

**FR-010 — not presenting this as a medical measurement — is a scope boundary with teeth.**
It is what keeps the out-of-scope list (no alerts, no zones, no interpretation) from being
re-litigated feature by feature.
