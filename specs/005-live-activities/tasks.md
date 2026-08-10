---

description: "Task list for Live Activities"
---

# Tasks: Live Activities

**Input**: Design documents from `/specs/005-live-activities/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/live-surface.md](./contracts/live-surface.md)

**Tests**: Included, and not optional here. The project constitution requires every pure
component to carry unit tests, and requires transport-dependent behaviour to be tested in
both states — transport live and transport gated. Those tasks are marked as such.

**Audited against the code on 2026-08-06.** Eight tasks were implemented and left unticked —
T021, T022, T026, T027, T028, T029, T030, T033 — and are now ticked with the evidence
attached. The audit ran in both directions, which is the only way it is worth running: T016
was ticked and is **not** fully implemented (see its note). A checkbox is a claim about the
code, so where the two disagreed the code was taken as the truth and the claim was corrected,
in both directions.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: Which user story the task serves (US1–US4)

## Path Conventions

Existing module layout, unchanged. Package root is
`io/github/andrewkomkov/greenpods`. Dependency direction stays one-way:
`app` → `feature/*` → `core/data` → `core/bluetooth` → `core/model`.

---

## Phase 1: Setup

**Purpose**: The two things that must exist before any of it compiles or runs.

- [x] T001 Add `LIVE_ACTIVITY_MIN_SDK = 36` beside `L2CAP_MIN_SDK` in `build-logic/src/main/kotlin/GreenPodsConfig.kt`, with a comment saying which API introduced promoted ongoing notifications
- [x] T002 Add `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />` to `app/src/main/AndroidManifest.xml`, with a comment noting it is non-runtime and adds no prompt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The gate type and the settings every user story branches on. Nothing below can
be built without these.

- [x] T003 [P] Create `LiveActivityAvailability` sealed interface in `core/model/src/main/kotlin/io/github/andrewkomkov/greenpods/core/model/LiveActivityAvailability.kt` with states `Available`, `PlatformTooOld(apiLevel)`, `PromotionRefused`, `NotificationsDenied`, `NotPromotable(reason)` and an `isAvailable` property — no Android imports, mirroring `AapAvailability`
- [x] T004 [P] Add `liveActivityEnabled` (default true) and `liveActivityShowHeartRate` (default true) to `GreenPodsSettings` and to `SettingsRepository` preference keys in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/settings/SettingsRepository.kt`
- [x] T005 Extend the `set` command key list in `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/GreenPodsDebugReceiver.kt` to accept `liveActivityEnabled` and `liveActivityShowHeartRate`
- [x] T006 [P] Unit-test `LiveActivityAvailability` — every non-`Available` state reports `isAvailable == false`, and the four unavailable states are distinguishable rather than collapsing to one. **Lives in `LiveActivityGateTest.kt`, not its own file**: the states are only meaningful as the gate's answers, and testing them apart from the ordering that produces them would check the enum rather than the behaviour

**Checkpoint**: The gate type exists and settings are drivable from adb.

---

## Phase 3: User Story 4 — Understand why this phone cannot do it (Priority: P3, built first)

**Goal**: On any phone, the user can find out whether the surface works and why not.

**Why first despite P3**: Every later phase branches on availability, and this is the phase
that produces it. It is also the only part of the feature phones below API 36 receive (D3),
so shipping it alone still delivers something to most installs.

**Independent test**: On a device below API 36, the settings entry is present, disabled and
reasoned, and the existing ongoing notification is unchanged.

- [x] T007 [US4] Create `LiveActivityGate` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/live/LiveActivityGate.kt` — resolves `LiveActivityAvailability` from the platform API level, `NotificationManager.canPostPromotedNotifications()`, and notification permission; keep the Android calls behind a small injectable interface so the resolution logic stays testable
- [x] T008 [P] [US4] Add user-facing reason strings for all four unavailable states to `app/src/main/res/values/strings.xml`, each naming what the user can do about it (or that there is nothing)
- [x] T009 [US4] Unit-test `LiveActivityGate` in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/live/LiveActivityGateTest.kt` — one case per state, including that an API-36 device with promotion refused yields `PromotionRefused` and **not** `PlatformTooOld`
- [x] T010 [US4] Add the live-activity entry to `feature/settings`, shown **locked with its reason** when unavailable rather than hidden (FR-014a, Principle II), reusing the existing locked-affordance component. **Done — `SettingsScreen.kt` renders it via `LockedCard`; the four gate reasons are carried as `@StringRes` ids and resolved at draw time, so a locale change cannot leave a stale sentence. Mapping pinned in `app/src/test/.../GreenPodsViewModelsTest.kt`**
- [x] T011 [US4] Add ~~`Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`~~ **`Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`** as the action on the `PromotionRefused` reason so the user has a route back. **The constant this task named does not exist in any SDK** — checked against `platforms/android-36/data/api-versions.xml`, which has `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS since="36"` and no `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` at all. The wrong name reached `research.md:103` and from there this task; the discrepancy is recorded in a KDoc at `SettingsScreen.kt:412` so the next reader of the spec does not go looking for it

**Checkpoint**: User Story 4 is complete and independently shippable.

---

## Phase 4: User Story 1 — See battery without unlocking (Priority: P1) 🎯 MVP

**Goal**: Battery, case and wear readable on the lock screen without unlocking.

**Why this is the MVP**: it needs no writes at all. Battery and wear come from the
advertisement transport, which is always available, so this slice works on phones where the
AAP channel never opens.

**Independent test**: Inject a synthetic advertisement, lock the phone, read both bud levels
and the case level without unlocking; stop injecting and watch the surface say out-of-range
rather than showing stale levels.

- [x] T012 [P] [US1] Create `LiveActivitySummary` data class in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/live/LiveActivitySummary.kt` with `accessoryName`, `presence`, `leftPercent`/`rightPercent`/`casePercent` as nullable, `charging`, `wear`, `noiseControl`, `sensing` — per [data-model.md](./data-model.md)
- [x] T013 [US1] Create `LiveActivityPolicy` in `core/data/src/main/kotlin/io/github/andrewkomkov/greenpods/core/data/live/LiveActivityPolicy.kt` — pure; given availability, settings and pod state, returns a summary or a reason not to post. Encodes: monitoring off ⇒ no surface; a dismissed surface stays down; availability checked before content is built; re-post only when rendered content changes
- [x] T014 [US1] Unit-test the summary — **null battery is not 0%**, out-of-range suppresses levels rather than carrying them forward, charging is represented distinctly, and the accessory is always named. **Lives in `LiveActivityPolicyTest.kt`**: the summary is produced by the policy and has no constructor worth testing on its own
- [x] T015 [US1] Unit-test the policy in `core/data/src/test/kotlin/io/github/andrewkomkov/greenpods/core/data/live/LiveActivityPolicyTest.kt` — including that a dismissed surface is not reposted on the next state tick, and that identical rendered content produces no re-post
- [x] T016 [US1] Create `LiveActivityNotification` in `app/src/main/kotlin/io/github/andrewkomkov/greenpods/service/LiveActivityNotification.kt` — renders a `LiveActivitySummary` with `NotificationCompat`, sets `setRequestPromotedOngoing(true)`, `BigTextStyle`, `deleteIntent`; **never** sets a custom `RemoteViews`, `setColorized(true)` or `setGroupSummary(true)`, each of which disqualifies promotion (R-1)
  - ⚠️ **Ticked, but the `deleteIntent` was never written** (audited 2026-08-06). It appears nowhere in `app/src/main`, and `LiveActivityPolicy.onDismissed()` is consequently called by no production code — so the dismissal rule its unit tests cover cannot fire on a device, and a swiped-away surface is reposted on the next state tick. Being carried by the T023/T025/T031 action-plumbing work, which builds the `PendingIntent`s this needs anyway.
- [x] T017 [US1] Rewrite `buildOngoingNotification`/`updateOngoingNotification` in `app/src/main/kotlin/io/github/andrewkomkov/greenpods/service/PodMonitorService.kt` to use `NotificationCompat` and render through `LiveActivityNotification`, keeping the existing text exactly when the gate is unavailable (FR-014 — those phones get nothing new)
- [x] T018 [US1] Call `Notification.hasPromotableCharacteristics()` after building and map a `false` to `LiveActivityAvailability.NotPromotable(reason)` — R-4 leaves the permitted style genuinely unsettled between two Android docs, so the device is the arbiter and its answer is surfaced, not swallowed
- [x] T019 [US1] Add the `live` command to `app/src/debug/kotlin/io/github/andrewkomkov/greenpods/debug/GreenPodsDebugReceiver.kt` per [contracts/live-surface.md](./contracts/live-surface.md) — availability, reason, posted, dismissed, accessory, presence, battery, charging, wear
- [x] T020 [P] [US1] Document `gp --es cmd live` in `docs/adb.md` with runnable examples, in the same change as T019 (FR-021, Principle VI)

**Checkpoint**: MVP complete. Verify against [quickstart.md](./quickstart.md) sections 1, 2 and 5.

---

## Phase 5: User Story 2 — Change noise control from the lock screen (Priority: P2)

**Goal**: One-tap listening-mode change from the surface, obeying the transport gate.

**Independent test**: With the channel open, switch modes from the surface and confirm the
accessory echoes it. With the channel unavailable, the control is locked with its reason and
cannot be actuated.

- [x] T021 [US2] Extend `LiveActivitySummary.noiseControl` handling in `LiveActivityPolicy` to emit `Offered(mode)` only when the feature is in `usableFeatures`, and `Locked(reason)` from `gatedFeatures` — never from `PodModel.features` (FR-011, Principle I). **Landed with Phase B — `LiveActivityPolicy.kt:141-146`**
- [x] T022 [US2] Unit-test the gated and ungated cases in `LiveActivityPolicyTest.kt` — the constitution requires transport-dependent behaviour to be tested in **both** states. **Done — `LiveActivityPolicyTest.kt:134-149`**
- [x] T023 [US2] Add a notification action that cycles the listening mode, routed through the existing `AapControlGateway` so the existing echo-confirmation path is reused rather than duplicated. **Done — `LiveActivityActions.cycleNoiseControl` through the existing `AapControlGateway`; the pod is re-read at press time and the gate re-checked, because a `PendingIntent` outlives the state that built it**
- [x] T024 [US2] Ensure the surface renders the mode the **accessory reports**, never the requested one — a write is not a change until confirmed (Principle I). Add a policy test for a sent-but-unechoed write leaving the surface unchanged. **Done — holds by construction (the policy reads `pod.noiseControlMode`, written only from the accessory echo) and is now pinned: the new test drives the same reported state twice with the write in between, so the unechoed leg leaves the surface unchanged and the echoed leg moves it**
- [x] T025 [US2] Add a locked-state action that opens the app at the explanation rather than silently doing nothing (FR-012). **Done — opens the app, which renders the gate sentence on the status screen. Landing on that exact spot would need a deep link through `MainActivity`; recorded as a follow-up, not claimed**
- [x] T026 [US2] Extend the `live` command output with `noiseControl=OFFERED mode=…` / `noiseControl=LOCKED reason=…` and document it in `docs/adb.md`. **Done — `GreenPodsDebugReceiver.kt:394-396`, `docs/adb.md:122`**

**Checkpoint**: Verify against [quickstart.md](./quickstart.md) section 3.

---

## Phase 6: User Story 3 — Know that the heart-rate sensor is running (Priority: P2)

**Goal**: Disclosure of active sensing outside the app, a working stop control, and the
reading itself under the user's control.

**Independent test**: Start a session, lock the phone, confirm the disclosure and the value;
hide the value and confirm the disclosure survives; stop from the surface and confirm reports
cease in the earbuds.

- [x] T027 [US3] Add `SensingState` — `Idle`, `Disclosed`, `DisclosedWithRate(bpm)` — to `LiveActivitySummary`, with the setting selecting between the two disclosed forms and **unable to reach `Idle`** (FR-018a). **Done — `LiveActivitySummary.kt:39-49`, `LiveActivityPolicy.kt:156-165`**
- [x] T028 [US3] Unit-test in `LiveActivitySummaryTest.kt` that `liveActivityShowHeartRate=false` yields `Disclosed` and never `Idle` — a user may decline to display their heart rate, not to be told the sensor is running. **Done, in a different file than this task named: `LiveActivityPolicyTest.kt:157-181`. There is no `LiveActivitySummaryTest.kt` — T014 deliberately put the summary tests in the policy's file, and this task was written before that decision**
- [x] T029 [US3] Unit-test that with no active session the summary mentions heart rate not at all (FR-017). **Done — `LiveActivityPolicyTest.kt:152-154`**
- [x] T030 [US3] Render the disclosure and, when permitted, the rate in `LiveActivityNotification`. **Done — `LiveActivityNotification.kt:40-52`**
- [x] T031 [US3] Add a stop action that stops sensing **in the accessory** via the existing heart-rate path, not merely in the app (FR-016). **Done — clears `heartRateEnabled`, which `HeartRateController.reconcile` turns into `stopHeartRate` and an interval-zero feature report. Verified on device: press left `"heartRateEnabled":false`. The sensor actually falling silent still needs earbuds**
- [x] T032 [US3] Add the heart-rate-visibility entry to `feature/settings`, with copy stating that hiding the value does not hide the disclosure. **Done — with copy stating that hiding the value does not hide the disclosure**
- [x] T033 [US3] Extend the `live` command with `sensing=… valueShown=…` and **no reading** — FR-023 still binds every diagnostic path, and this is the most diagnostic one there is (R-7). Document the asymmetry in `docs/adb.md` so it does not read as an oversight. **Done — `GreenPodsDebugReceiver.kt:400-406`, asymmetry documented at `docs/adb.md:139-142`**

**Checkpoint**: Verify against [quickstart.md](./quickstart.md) section 4.

---

## Phase 7: Polish & Cross-Cutting

- [x] T034 Verify on hardware that a foreground-service notification is actually promoted — the single assumption the whole feature rests on (research.md open items). **Done 2026-08-06: `PROMOTED_ONGOING` set by the system, `BigTextStyle` qualifies.** Recorded in `docs/protocol-research.md`
- [x] T035 [P] Confirm the rapid-wear-change edge case produces no flicker, driven by repeated `inject` calls. **Done 2026-08-06, Pixel 8 / Android 17, tested in both directions** — ten identical injections left the notification's `when` at `1786016037652` unchanged; one changed injection moved it to `1786016048994`. One-sided would have been worthless: a surface that never reposts also passes "no flicker". First attempt was a **false pass** — the surface was dismissed at the time, so nothing would have reposted regardless; the service was restarted for a fresh policy before the real run
- [x] T036 [P] Confirm notifications-denied behaviour: service still runs, surface absent, nothing senses silently (quickstart section 6). **Done 2026-08-06** — with `POST_NOTIFICATIONS` revoked, `live` reports `availability=NOTIFICATIONS_DENIED reason="notification permission not granted" posted=false withheld=Unavailable(NotificationsDenied)`, and `dumpsys activity services` still shows `PodMonitorService` running. Permission restored afterwards
- [x] T037 Run `./gradlew spotlessCheck lintDebug testDebugUnitTest` and fix anything it finds
- [x] T038 Walk the whole of [quickstart.md](./quickstart.md) on a device and record which steps were actually executed — a green suite is not a substitute, and two instrumented tests in this project already pass only when no AirPods are nearby
  - **Done 2026-08-06, Pixel 8 / Android 17, no accessory present.** Executed: the surface posting with injected advertisements (battery 70/60/90, `wear IN_EAR`, `presence=IN_RANGE`); the gate reporting `noiseControl=LOCKED reason="Not checked yet."` rather than absent; the dismissal rule end to end (`withheld=Dismissed` survived a fresh injection, and the promoted flag left the notification while `ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE` remained — the plain notification stayed, which is what a dismissal is supposed to buy); the stop action reaching `heartRateEnabled=false`.
  - **Executed on hardware later the same day**, AirPods Pro at `74:3F:8E:C5:CD:8E`, AAP channel open. §3: the mode moved `TRANSPARENCY → ADAPTIVE` and the surface followed only the accessory's echo. §4: sensing reached `MEASURING` with 43 trusted of 47 reports — the confidence gate discarding the settling ones — the surface read `sensing=DISCLOSED valueShown=true` while printing no number, and the stop action drove `state=OFF enabled=false interval=0ms lastStop=disabled` with reports ceasing and the disclosure leaving the surface. The interval-zero write was confirmed **in the frame log**, not inferred: `tx … 17 00 … 08 13 10 02 1A 05 01 00 00 00 00` — service `0x13`, interval 0.
  - **A defect was found in the verification itself, not in the feature.** The first hardware run appeared to show a stale disclosure surviving a stop. It was the new adb driver: the onward broadcast lacked `FLAG_RECEIVER_FOREGROUND`, so Android deferred it while the app was backgrounded and the press silently never arrived — the exact trap `docs/adb.md` warns about for this command surface. It worked when the app happened to be foreground and failed otherwise, which is the worst shape a bug can take: the first run confirms the feature and the second contradicts it. A real notification action never had this problem, since its `PendingIntent` is delivered under the allowlist the system grants the notification; the flag makes the adb path equal to the button rather than privileged over it. Re-verified with the app deliberately backgrounded.
  - **Added to make this walkable at all**: `gp --es cmd live --es action cycle|stop|dismiss`. The quickstart previously said `# tap the action on the device` in two places, which is the admission that those steps were manual — and Principle VI does not exempt behaviour for sitting behind a notification.

---

## Dependencies

```
Phase 1 (Setup)
   └─> Phase 2 (Foundational: gate type, settings)
          └─> Phase 3 (US4: availability + settings entry)   ← ships alone
                 └─> Phase 4 (US1: the surface)               ← MVP
                        ├─> Phase 5 (US2: noise control)
                        └─> Phase 6 (US3: sensing + rate)
                               └─> Phase 7 (Polish)
```

Phases 5 and 6 are independent of one another and may be built in either order or in
parallel. Both depend on Phase 4 because both add content to a surface that must exist.

## Parallel opportunities

- **Phase 2**: T003, T004 and T006 touch different modules and files.
- **Phase 3**: T008 (strings) runs alongside T007 (gate logic).
- **Phase 4**: T012 (data class) and T020 (docs) are independent of the rendering work.
- **Phase 7**: T035 and T036 are separate device scenarios.

## Implementation strategy

**Ship Phase 3 first, alone.** It is the only part most installs will ever receive, and it
is what makes the feature's absence legible rather than confusing.

**Then Phase 4 as the MVP.** It needs no writes, so it works on every phone that can see the
earbuds — including those where the AAP channel never opens. That is deliberate: the most
valuable slice is the one least hostage to the transport gate.

**Phases 5 and 6 are additive.** Each degrades to the previous checkpoint if its transport
or its hardware is unavailable, which is the same discipline the rest of the app follows.
