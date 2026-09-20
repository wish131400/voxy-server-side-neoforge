# Worldgen profile login packet fix

## Symptom and cause

VSS 0.3.1 on NeoForge could send an entire worldgen snapshot through a single
custom payload. A reported 2,930,380-byte packet exceeded Minecraft's 21-bit
frame length limit and disconnected clients immediately after profile creation.
The snapshot contains generator/registry metadata, not saved region contents;
the reported 904 MB world save is not the quantity being sent.

Registry and generator fields already support compression. Compression alone
cannot guarantee a packet-size ceiling, especially for already-compressed data.

## Implementation

- Remote profiles use `WorldgenProfileTransfer`, which finishes encoding before
  sending and emits fragments containing at most 512 KiB of snapshot bytes.
  Each fragment includes a transfer ID, total byte length and byte offset.
- NeoForge registers `vss:worldgen_profile_fragment`; all remote worldgen sends
  in `VSSNetworking.sendToPlayer` use it. The integrated host retains direct
  in-process delivery. Other clients connected to an integrated server use
  fragmentation normally.
- Forge already had this wire format. It now shares the bounded sender and
  strengthened reassembly lifecycle without changing its wire protocol.
- The client holds one ordered transfer, installs only a complete decoded
  profile, and rejects mismatched IDs/lengths, gaps, duplicate non-initial
  fragments and trailing bytes. A new offset-zero fragment replaces an
  incomplete transfer. Minecraft/TCP supplies ordered, reliable delivery.
- Login, logout and session reconfiguration clear the assembly. Idle transfers
  expire after 120 seconds without progress. Reassembly exceptions discard the
  transfer rather than propagating through the gameplay handler.
- The total encoded snapshot limit is 144 MiB, covering the existing 8 MiB
  registry and 64 × 2 MiB generator limits plus metadata. The serializer's
  buffer is also capped; an encoding failure sends no partial snapshot.
- Profile format version 3, existing compression and prediction precision are
  unchanged. This fixes transport size; it does not accelerate profile creation
  or reduce the total worldgen data transferred.

## Compatibility and installation

NeoForge network protocol is now **47** (previously 46). Update the server and
every VSS client together. The loader's protocol check prevents old clients
from receiving an unregistered fragment payload. Forge keeps protocol 46
because its fragment format and message registration are unchanged.

The normal artifact names remain:

- `lib/vss-0.3.1-neoforge-1.21.1.jar`
- `lib/vss-0.3.1-forge-1.20.1.jar`

Enable `enablePredictionSync` after installing the matching fixed builds.
No world deletion or regeneration is required.

## Validation (2026-09-20)

Both repositories passed 20 selected test cases:

```powershell
.\gradlew.bat test --tests '*WorldgenProfile*Test' --tests '*PlayerSessionManagerTest' --offline --console=plain
.\gradlew.bat build -x test --offline --console=plain
```

The production sender was tested with random (poorly compressible) registry
bytes up to the 8 MiB registry limit. A snapshot of exactly **2,930,380 bytes**
produces **6 fragments** and round-trips byte-for-byte. Tests also cover exact
fragment boundaries, incomplete/replaced transfers, malformed lengths, idle
expiry, failed decoding and encoding failure before any send.

An embedded Netty test uses Minecraft's actual `Varint21LengthFieldPrepender`
and, separately, `CompressionEncoder(256)`. The unsplit 2,930,380-byte snapshot
fails framing; every fragment passes, with and without network compression.
This is a codec/transport regression test, not a real player login test.

The local NeoForge dependency is 21.1.234. The reporter's Docker server,
NeoForge 21.1.233 and complete modpack have not been exercised here. Deployment
verification should keep prediction sync enabled and confirm successful login,
prediction startup, reconnect and dimension changes on that installation.
