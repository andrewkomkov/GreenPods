# Research: Live Activities

**Feature**: `specs/005-live-activities` | **Date**: 2026-08-06

Everything below is either read off the platform documentation or read out of this
repository. Where the sources disagree, that is recorded rather than resolved by picking
the more convenient one.

## R-1: What the platform actually provides

**Decision**: Use Android's *promoted ongoing notification* ("Live Updates"), via
`NotificationCompat.Builder.setRequestPromotedOngoing(true)`.

**Rationale**: It is the only mechanism that puts a persistent, app-owned summary on the
lock screen and in the status bar as a chip. It is a promotion of a notification we already
post, which is exactly the shape the spec asked for — an upgrade of the monitoring
notification rather than a second surface.

**Requirements a notification must meet to be promoted**, all of them:

| Requirement | Where we stand |
|---|---|
| `setRequestPromotedOngoing(true)` | To add |
| Ongoing (`setOngoing(true)`) | Already true — it is a foreground-service notification |
| `contentTitle` set | Already true |
| No `customContentView` / `RemoteViews` | Already true, and must stay true |
| Not a group summary | Already true |
| **Not** colorized | Already true, and must stay true |
| Channel importance above `IMPORTANCE_MIN` | Already `IMPORTANCE_LOW` — satisfies it |
| `POST_PROMOTED_NOTIFICATIONS` in the manifest | To add. Non-runtime; no new prompt |

**Alternatives considered**: a custom `RemoteViews` layout — rejected, and not merely on
taste: custom views **disqualify** a notification from promotion. A separate second
notification — rejected by the spec's own assumption; two ongoing notifications for one
service is a regression.

**Sources**:
- <https://developer.android.com/develop/ui/views/notifications/live-update>
- <https://developer.android.com/develop/ui/compose/notifications/live-update>

## R-2: Which API level, and what the rest get

**Decision**: Gate on **API 36** (Android 16). Below it, change nothing at all.

**Rationale**: `setRequestPromotedOngoing` and the promotion machinery arrive in API 36.
This project's floor is `minSdk` 26 and `compileSdk`/`targetSdk` are 37, so the API
compiles unconditionally and is gated at runtime — the same pattern `AapTransport` already
uses for L2CAP at API 29 (`GreenPodsConfig.L2CAP_MIN_SDK`), and the plan adds
`LIVE_ACTIVITY_MIN_SDK = 36` beside it.

D3 decided those phones get nothing new. They keep today's notification untouched.

**Alternatives considered**: back-porting a richer ordinary notification for older phones —
rejected by D3. Recorded because it is the obvious future request and the decision was
deliberate, not an oversight.

## R-3: AndroidX support is already in place

**Decision**: Use `NotificationCompat`, not the platform `Notification.Builder` the service
uses today.

**Rationale**: `NotificationCompat.Builder.setRequestPromotedOngoing` exists from
`androidx.core` 1.17; this project is already on **1.19.0**, so nothing needs upgrading.
Using the compat builder means the call site has no version branch — the method is a no-op
below API 36 — which keeps the runtime gate in one place instead of smeared through the
builder.

This is a small refactor of an existing class rather than new machinery.

## R-4: Which style to use — **unresolved, deliberately**

**Finding**: the two Android documentation pages consulted **do not agree** on the set of
permitted styles.

- The Live Update guide lists: standard style, `BigTextStyle`, `CallStyle`, `ProgressStyle`,
  `MetricStyle`.
- The Compose Live Update page and secondary sources list only `BigTextStyle`, `CallStyle`,
  `ProgressStyle`.

**Decision**: use **`BigTextStyle`**, which every source agrees is permitted, and verify on
the device with `Notification.hasPromotableCharacteristics()` before relying on it.

**Rationale**: `ProgressStyle` is tempting — battery looks like a progress bar — but it
models *progress along a journey towards completion*, which a battery level is not; a
draining battery running a progress bar backwards would misrepresent it. `BigTextStyle`
carries the summary as text, which is what the spec's P1 story actually needs.

**This must not be settled from the documentation.** `hasPromotableCharacteristics()` is on
the platform precisely so an app can ask rather than assume, and the plan makes checking it
a task, not an afterthought. If the check says no, that is a fact about this device and is
surfaced as a gate reason (Principle II), not swallowed.

## R-5: The user can refuse promotion, and that is not a failure

**Decision**: Treat `NotificationManager.canPostPromotedNotifications()` as a third gate
state, distinct from "this Android is too old" and "notifications are denied".

**Rationale**: The user can demote a live update to an ordinary notification, or turn
promoted notifications off for the app in system settings. That is a legitimate choice, not
an error, and it maps onto exactly the distinction Principle II exists for — the user must
be able to tell "my phone can't", "I turned this off", and "the app is broken" apart.

`Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` is the route back, and the gate reason
carries it.

**Also**: users can dismiss a live update. The platform guidance is explicit that an app
must not repost a dismissed one. A `deleteIntent` records the dismissal so the surface stays
down until there is a genuine reason to post again.

## R-6: Where the logic goes

**Decision**: The mapping from `PodState` to what the surface says is a **pure function** in
`core/data`, unit-tested off-device. `PodMonitorService` only renders it.

**Rationale**: Principle III and the Quality Gates require pure components to carry unit
tests and Android-touching classes to be thin. The interesting behaviour here is entirely
decision-making — which of battery, wear, sensing and gate reason to show, how to say "not
in range" without saying "0%", and what the gate permits — and none of it needs a
`Notification` to be tested.

This mirrors `FrameLogPolicy`, added the day before for the same reason: a rule that can
only be exercised through a framework object is a rule nobody tests.

## R-7: Heart rate on the surface, after D1

**Decision**: The reading is rendered from the same `PodState.heartRate` the UI already
uses, and only while a session is active. A setting hides the value; the disclosure is not
subject to it.

**Constraint carried forward, unchanged**: FR-023 from `003-heart-rate` — no heart rate in
any diagnostic path. That still binds `dump`, `hr status`, the diagnostics log and the frame
log. The new adb command added by this feature (R-8) is a diagnostic path and therefore
**prints no reading**, only whether the value would be shown.

That asymmetry is the whole point and needs stating plainly, because it looks inconsistent
until you see what each surface is for: the lock screen shows a user their own heart rate;
`adb dump` produces a document people paste into bug reports.

## R-8: Driving it from adb

**Decision**: `gp --es cmd live` prints availability, the gate reason, whether the surface
is currently posted, and every field the surface would show **except a heart-rate value**.

**Rationale**: Principle VI — a feature that cannot be driven from adb is not finished — and
SC-007 requires every behaviour to be reproducible with no accessory present. The existing
`inject` command already drives battery and wear through the real pipeline, so the surface
becomes testable on a device with the earbuds in a drawer.

## Open items carried into implementation

| Item | Resolution route |
|---|---|
| Whether `BigTextStyle` is actually promotable on the target device (R-4) | `hasPromotableCharacteristics()` on hardware; fall back to `ProgressStyle` only if it says no, and record which |
| Whether a foreground-service notification is promoted in practice | Documentation says it can be if it qualifies; verify on hardware — this is the single assumption the whole feature rests on |
| Wear OS bridging | Out of scope; noted because the platform does it automatically and it may surprise |
