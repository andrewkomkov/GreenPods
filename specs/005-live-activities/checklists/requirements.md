# Specification Quality Checklist: Live Activities

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-05
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

## Constitution Alignment

The project constitution is unusually load-bearing here, so it is checked explicitly.

- [x] **I. The transport gate is the law** — FR-011 forbids deriving the surface from the
      model; FR-013 forbids reporting an unconfirmed change. The P1 story deliberately
      rests on the always-available transport so the feature is not hostage to AAP.
- [x] **II. Locked, not hidden** — FR-012 and FR-014; User Story 4 exists solely to make
      the unavailable case legible.
- [x] **III. Protocol code is pure and pinned** — no new protocol work; the surface is a
      view over existing decoded state.
- [x] **IV. Unknown traffic is surfaced** — unaffected.
- [x] **V. No fiction** — FR-002 and FR-007 force "unknown", "not in range" and "0%" apart
      rather than collapsing them into a plausible-looking number.
- [x] **VI. Driveable and observable over adb** — FR-020 and FR-021; SC-007 requires every
      behaviour to be reproducible with no accessory present.

## Notes

All three questions were resolved by the project owner on 2026-08-06 and are recorded, with
their consequences, in the spec's Decisions section:

1. **D1 (FR-018)** — the heart rate **is** shown, lock screen included, with a setting to
   hide the value. The disclosure is not subject to that setting (FR-018a), and the
   existing "no reading in any diagnostic path" rule is untouched and still binds `dump`,
   `hr status` and the frame log.
2. **D2 (FR-001)** — the surface is **permanent** while monitoring runs. Event-driven
   promotion was rejected; an answer that is only sometimes present is not glanceable.
3. **D3 (FR-014)** — phones below the supporting platform version get **nothing new**. A
   deliberate scope cut landing on most installs. It does not extend to silence: FR-014a
   still requires them to be told why, which is the only part of the feature they receive.

One item is intentionally deferred rather than failed: the spec names no platform version.
The supporting version is a planning input, not a user requirement, and pinning it here
would put an implementation detail in a stakeholder document.

Ready for `/speckit-plan`.
