# Tectonic prediction snapshot failure

Follow-up: the user's subsequent live test exposed expanded generator snapshots
exceeding the 8 MiB raw limit. Shared-reference compaction and the replacement
artifact are documented in `IRIS_0215_2026-09-09.md`. The artifact/hash below
describe the earlier iteration and are superseded.

## Evidence

The user's 2026-09-09 11:12:11 log reports:

```
VSS could not encode the worldgen profile ...; predictive LOD disabled
java.lang.UnsupportedOperationException: Calling .codec() on HolderHolder
  at DensityFunctions$HolderHolder.codec
  ...
  at WorldgenCodecSnapshot.encodeRegistry
  at WorldgenCodecSnapshot.encodeRegistries
```

Installed versions: Tectonic 3.0.26, Lithostitched 1.8.0+beta6,
Minecraft 1.21.1 / NeoForge 21.1.249, with C2ME and Voxy.
The log identifies VSS 0.3 as the active version. This failure occurs before
prediction data reaches the renderer. The same log also contains a Tectonic
`fabric:overlays` resource-condition error, but world startup completed; this
patch addresses the explicit VSS snapshot exception, not that separate error.

## Change

For Tectonic/Lithostitched, snapshot density registries and generator noise
routers through their applied `mapAll` graph, unwrapping direct density holders.
Lithostitched's actual MergedDensityFunction maps its effective `full` function,
whereas its source codec serializes the original function for modifier replay.
The client does not replay server worldgen modifiers, so transmitting that
original alone is insufficient even if encoding succeeds.

The vanilla-only snapshot path is unchanged. Optional-mod prediction still uses
the Java backend. No Rust ABI, binary, mask or rendering changes are included.
Serialization failures now identify the failing registry and entry.

## Verification

- Reproduced the original exception with nested direct holders before applying
  normalization, then verified density values after encoding and decoding.
- Used the installed Lithostitched JAR's actual MergedDensityFunction.
- Used the installed Tectonic JAR's actual ConfigConstant, ConfigClamp and
  ConfigNoise with default (non-experimental) scaling.
- Compared 64 seeded samples of vanilla final and initial density after full
  settings round-trip; asserted serialized settings remain below 2 MB.
- Full suite: 530 tests, 529 passed, one opt-in GPU test skipped, zero failures.
- Packaged all five existing native libraries. No user mods/save edits.

Actual in-game rendering with the complete Tectonic/C2ME/Connector combination
has not been verified. Experimental alternate scaling, modded surface-rule
codecs and arbitrary other worldgen mods are not covered by these tests.
This is a fix for the observed initialization failure, not a declaration of
complete Tectonic worldgen parity.

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,862,833 bytes.
SHA256: `13e0d000c12ebaf6dbc28d75bb16eab67ca1c8cfebb0912f2e8fd1546a668763`.
