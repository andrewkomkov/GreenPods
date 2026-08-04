# Contract: what GreenPods writes to Health Connect

This is the feature's only outward-facing interface. Another app reads these records
without knowing GreenPods exists, so the contract is what that app sees.

Library: `androidx.health.connect:connect-client:1.1.0` (`minSdk` 26 — the same floor
GreenPods already has, so nothing is excluded by adding it).

## Permissions

Declared in the manifest:

```xml
<uses-permission android:name="android.permission.health.WRITE_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
```

`READ_HEART_RATE` exists for one reason: FR-029's count-back is only meaningful if it can
be filtered to GreenPods' own records. It is not used to consume anyone else's data, and
reading heart rate written by other apps is explicitly out of scope.

Plus the provider query, without which availability cannot be determined:

```xml
<queries><package android:name="com.google.android.apps.healthdata" /></queries>
```

## The rationale screen (FR-022)

Users grant and revoke these permissions from the system's health settings, not from
inside GreenPods. An activity must therefore answer the system's request to explain the
app's use of health data:

```xml
<activity android:name=".health.HealthRationaleActivity" android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>

<activity-alias android:name="ViewPermissionUsageActivity"
    android:targetActivity=".health.HealthRationaleActivity"
    android:exported="true"
    android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
    <intent-filter>
        <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
        <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
    </intent-filter>
</activity-alias>
```

What it says: GreenPods writes heart rate measured by the user's earbuds, writes nothing
else, and reads only its own records to check its own work.

## Availability

`HealthConnectClient.getSdkStatus(context)`:

| Status | What the user is shown |
|---|---|
| `SDK_AVAILABLE` | The integration is offered |
| `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` | "Health Connect needs an update" — actionable |
| `SDK_UNAVAILABLE` | "This device does not have Health Connect" — the reading still works (FR-020) |

Availability is checked before use, never assumed. The reading on screen never depends on
any of this.

## The records

One `HeartRateRecord` per wall-clock-aligned 60-second window:

| Property | Value |
|---|---|
| `startTime` | First trusted sample in the window |
| `endTime` | Last trusted sample, plus one second |
| `samples` | `(Instant, bpm)` per trusted reading — the reading's own timestamp, not the write time |
| `metadata` | `Metadata.autoRecorded(device, clientRecordId, clientRecordVersion)` |
| `device` | `Device(TYPE_HEAD_MOUNTED, manufacturer = "Apple", model = <PodModel.displayName>)` |
| `clientRecordId` | `greenpods:hr:<windowStartEpochSeconds>` |
| `clientRecordVersion` | Number of samples in the batch |

Consequences, which are the reasons for the scheme:

- **A window is idempotent.** Re-writing the same window updates its record. An
  interruption mid-window cannot produce two records for the same seconds (FR-019).
- **A fuller batch wins.** The version is the sample count, so a partial window already
  written is superseded, never duplicated (SC-009).
- **The measuring device is named.** A user reading their history in another app sees
  AirPods Pro 3, not just "GreenPods" (FR-021).

## What is never written

- A reading whose confidence is below the gate, under any setting (FR-007, SC-004).
- A reading outside 25..250 BPM.
- Anything at all while `heartRateHealthConnectEnabled` is false or the permission is not
  held (FR-017).
- Any record type other than `HeartRateRecord`. No sessions, no calories, no derived
  metrics — the out-of-scope list is enforced here as well as in the UI.

## Revocation and deletion

- Permission is checked before each flush. Losing it stops writing immediately and
  silently; there is no re-prompt (FR-018).
- Deleting from inside GreenPods removes GreenPods' own records, and the UI says plainly
  that data already in the health store is managed there (FR-025).
