# GreenPods

AirPods, on Android, without root.

Battery, ear detection, noise control, head tracking and heart rate — as much as
Android's Bluetooth stack will actually allow, honestly labelled where it will not.
Kotlin, Jetpack Compose, Material 3 Expressive.

[![CI](https://github.com/andrewkomkov/GreenPods/actions/workflows/ci.yml/badge.svg)](https://github.com/andrewkomkov/GreenPods/actions/workflows/ci.yml)

> **Status: working.** The protocol layer is unit-tested against real packet captures,
> and the app — device list, transport diagnostics, gated controls, ear-detection
> auto-pause, background monitoring, updates — runs and is verified on device. Feature
> work runs through [spec-kit](https://github.com/github/spec-kit); the current spec is
> [`specs/001-greenpods-core-app`](specs/001-greenpods-core-app/spec.md).

## What actually works, and what doesn't

Apple never published these protocols, and Android does not cooperate equally with
all of them. GreenPods is built around that reality rather than pretending otherwise.

| Feature | How it's obtained | Unrooted? |
|---|---|---|
| Battery (per bud + case), charging | BLE advertisement | ✅ Always |
| In-ear / in-case detection | BLE advertisement | ✅ Always |
| **Auto-pause / resume on bud removal** | BLE advertisement + media keys | ✅ Always |
| Case lid-open events | BLE advertisement | ✅ Always |
| Low-battery warnings, background monitoring | BLE advertisement | ✅ Always |
| Heart rate — **Powerbeats Pro 2** | Standard BLE Heart Rate Profile | ✅ Always |
| Noise control (ANC / Transparency / Adaptive) | AAP over L2CAP | ✅ Recent Android |
| Conversational Awareness, adaptive audio | AAP over L2CAP | ✅ Recent Android |
| Stem gestures, rename, ear-detection toggle | AAP over L2CAP | ✅ Recent Android |
| Head tracking + head gestures | AAP over L2CAP | ✅ Recent Android |
| Heart rate — **AirPods Pro 3** | AAP, frame format not yet decoded | ❌ Not yet |
| Find My, device switching, Siri, audio sharing | Apple account services | ❌ Never |

**Why "recent Android":** the Apple Accessory Protocol runs over an L2CAP channel on
PSM `0x1001`, and the accessory only accepts an authenticated, encrypted channel
carrying Apple's service UUID. No public Android API builds one, and the hidden
constructor that does is on the non-SDK blocklist — so reaching it takes a scoped
runtime exemption for `android.bluetooth`, not root. Verified on a stock, unrooted
Pixel 8 running Android 17. GreenPods still probes at runtime rather than assuming,
because an Android release can move that constructor again. Everything in the
"always" rows is unaffected either way.

The app shows locked features rather than hiding them, so you can tell the
difference between "your phone won't allow this" and "the app doesn't do it". Tap a
locked chip and it tells you exactly which check failed.

Auto-pause is worth calling out: it is the AirPods behaviour people miss most on
Android, and it is one of the "always" rows — wear state travels in the advertisement,
so it works on a phone that can never open the Apple protocol channel.

### A useful workaround

Some settings live in the buds' own firmware and survive a host switch. Pair your
AirPods to an iPhone, iPad or Mac, set the stem long-press action, the listening
modes it cycles through, and Conversational Awareness — then use them on Android.
GreenPods will read the state back.

## Install

Grab the APK from [Releases](https://github.com/andrewkomkov/GreenPods/releases/latest).
The app checks for updates itself and points you at new versions.

Requires Android 8.0 (API 26) or newer, and Bluetooth LE.

## Build

Needs JDK 21 and the Android SDK.

```bash
git clone https://github.com/andrewkomkov/GreenPods.git
cd GreenPods
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew spotlessApply       # format
```

## Contributing

Conventional Commits are required — releases are generated from them.

The most valuable contributions right now are packet captures. If you have a Mac
and any AirPods model, `docs/protocol-research.md` explains how to capture AAP
traffic with PacketLogger and what is still unknown. The AirPods Pro 3 heart-rate
frame format is the biggest open question.

## Credits

The protocol work rests on prior reverse-engineering by others:

- [LibrePods](https://github.com/librepods-org/librepods) — AAP packet definitions,
  opcodes and control commands
- [CAPod](https://github.com/d4rken-org/capod) — device model registry and BLE
  advertisement decoding

## Licence

MIT. Not affiliated with, endorsed by, or connected to Apple Inc. AirPods and Beats
are trademarks of Apple Inc.
