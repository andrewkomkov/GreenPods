# Contract: HID-over-AAP, opcode `0x17`

The wire contract between GreenPods and the accessory. Every byte sequence here was
captured from an AirPods Pro 3 on 2026-08-04 — see [research.md](../research.md) for the
capture context. Test fixtures are taken from these, not from this document.

## Framing

```
04 00 04 00 | 17 00 | 00 00 10 00 | <length u16 LE> | <protobuf body>
   header      opcode    prefix        body length
```

`length` counts the protobuf body only. A read that returns fewer than `12 + length` bytes
is a partial frame and must be buffered, not decoded. A read that returns more contains a
following frame.

## Host → accessory: start and stop

Field 8, carrying a service id, an operation, and a feature report holding the 32-bit
report interval in microseconds.

```
08 <seq>                   field 1: sequence, arbitrary; echoed back incremented
42 0B                      field 8, length 11
   08 <service id>           the id discovered from the descriptors — never a constant
   10 02                     operation: set feature report
   1A 05                     field 3, length 5
      01                       report id (from the descriptor's feature report)
      40 42 0F 00              interval µs, LE
```

| Intent | Interval bytes | Value |
|---|---|---|
| Start at 1 Hz | `40 42 0F 00` | 1 000 000 µs |
| Stop | `00 00 00 00` | 0 — this is the whole off switch |

Complete 1 Hz start frame for service `0x13`:

```
04 00 04 00 17 00 00 00 10 00 0F 00 08 78 42 0B 08 13 10 02 1A 05 01 40 42 0F 00
```

**A start that is accepted is not a start.** The sensor is running when input reports
arrive, and not before.

## Accessory → host: service descriptors (field 5)

```
2A <len>                   field 5, repeated — one per service
   08 <service id>           inner field 1
   12 <len> <blob>           inner field 2: Apple property dictionary
```

The blob is `D3 <count> …` followed by entries of
`<u16 keylen> 00 00 09 <ASCII key> <typed value>`. Keys used by this feature:

| Key | Use |
|---|---|
| `AccessoryService` | Service name — `HeartRateService` identifies the one we want |
| `HIDServiceAccessEntitlement` | `com.apple.hid.heartrate-access`, the fallback identifier |
| `ReportDescriptor` | The HID report descriptor, parsed for the layout |

Observed on AirPods Pro 3: `0x10` devmotion, `0x11` SPL0, `0x12` HostLibHID, `0x13`
HeartRateService. **These ids are observations, not constants.** A parser that treats them
as constants fails this contract.

## Accessory → host: services ready (field 12)

```
62 02 08 <service id>      repeated, one per ready service
```

## Accessory → host: input reports (field 7)

```
3A <len>
   08 <service id>
   1A <len> <report bytes>
```

Heart-rate report, report id 1, 18 bytes — offsets derived from the descriptor, listed
here to make the fixtures readable:

| Offset | Size | Field | Usage |
|---|---|---|---|
| 0 | 1 | Report id (`0x01`) | — |
| 1 | 1 | Heart rate, BPM | `0x0020:0x04B8` |
| 2 | 1 | Confidence | `0xFF15:0x0120` |
| 3 | 2 | Sequence, LE | `0xFF15:0x0121` |
| 5 | 1 | Status enum | usages `0x0104..0x0105` |
| 6 | 8 | Timestamp, ns, LE | `0xFF15:0x0004` |
| 14 | 4 | Vendor tail | `0xFF0A:0x12` |

Report id 2 (600 bytes, vendor usage `0xFF0A:0x13`) is declared by the same service and is
**not decoded**. It is reported as unhandled by shape — service id, report id, length —
and never by content.

## Decoder obligations

1. Dispatch `0x17` on the protobuf field present, never on packet length.
2. Take every service id from the descriptors.
3. Reject a report whose length disagrees with the layout the descriptor declared.
4. Discard a BPM outside 25..250 as implausible, and report it as unhandled.
5. Emit no report body into any log, at any level.
