# Native packaging and refinement invalidation

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar` (3,843,390 bytes).
SHA-256: `8aa48f8ea8212ae762275c93706f0271fbe5687e1aec1a57282ffceb8ec99190`.

## Reproduced defect

`capturedTerrainChanged` invalidated every resident/pending ancestor in the
captured chunk's quadtree column. A coarse tile reads isolated grid samples,
so most exact chunks within its large bounds do not change any input it reads.
Nevertheless these captures advanced its publication epoch and discarded its
in-flight refinement. Repeated captures can keep coarse parents rebuilding and
prevent children (including telescope targets) from acquiring usable parents.

The new regression blocks an actual refinement build, submits twenty captures
between sample lines, and releases it. Before the fix it remained at 8 cells per
axis instead of publishing 16; after the fix it publishes 16. A subsequent capture
on a sampled column still rebuilds the current resolution before advancing.

Invalidation now tests the final grid, which contains all progressive grid points,
including negative coordinates and preview borders. Surface-eligible/decorated
tiles retain their wider feature/structure invalidation. Disk cache invalidation
is unchanged. Rendering, masks, worldgen calculations, final resolution and the
64-chunk telescope surface radius are unchanged.

Existing command diagnostics now include `terrainFull`, `terrainPreview`,
`captureCancelledBuilds` and `ignoredCaptureInvalidations`; no per-tile log spam
was added. Ordinary diagnostic logging remains governed by `debugLogging`.

## Cross-platform artifacts

All targets use the same `tools/rust/vss-native-core` source and locked dependencies.
Windows x64 uses MSVC; Linux x64/ARM64 uses Zig with glibc 2.28; macOS Intel/ARM64
uses rust-lld and Zig libSystem link stubs, targeting macOS 11.0.
The macOS link exports only JNI symbols and omits private Rust symbol exports.

| Platform | Library bytes | ZIP-compressed bytes |
| --- | ---: | ---: |
| Windows x64 | 1,106,432 | 488,281 |
| Linux x64 | 1,218,200 | 583,073 |
| Linux ARM64 | 1,045,200 | 538,619 |
| macOS Intel | 1,091,816 | 538,889 |
| macOS Apple Silicon | 1,000,576 | 499,553 |

Native payloads occupy 2,648,415 compressed bytes (about 69% of the JAR).
The previous Windows-only JAR was 1,676,199 bytes. Four new architecture-specific
libraries account for almost all of the increase; only the host library is loaded.
Old palette, experimental occlusion, unregistered Mixin and retired backend files
were already deleted in the preceding cleanup and remain absent from this JAR.

## Validation and limits

- 507 Java/GPU tests, zero failures/errors/skips, including real tick-based nearby
  and new telescope-target progression at a 65,536-block prediction horizon.
- 22 Rust release tests passed, including vanilla noise/density/surface/feature oracles.
- JNI noise: 32,768 exact doubles; JNI ABI 2: 1,515 density values, 21,120 base blocks,
  29 vegetation cases, invalid inputs, close races and ownership checks passed.
- Production bridge: 1,064 grid/material/tint samples and seven mixed vegetation
  jobs; bundled Windows library, cancellation and staged refinement passed.
- Native live baseline: actual Rust/JNI reached nearby LOD 0 by the 10-second
  diagnostic and continued building for 60 seconds; no failure. This uses a
  fixed plains profile and excludes game rendering and live chunk capture traffic.
- All five binaries match the JAR byte for byte; architecture, exactly 29 JNI
  exports, dynamic dependencies and minimum OS versions verified. Apple Silicon
  ad-hoc signature SHA-256 code-page hashes verified.
- Package audit confirms retired classes/libraries absent, no exact duplicate
  source files or large normalized duplicate fragments, and unchanged mask bytecode.

This fixes a demonstrated invalidation starvation path. The screenshot alone
does not prove it explains every in-game stall. No claim is made that the new
artifact has been played in the user's world or tested on Linux/macOS hardware.
macOS native worldgen availability does not make Voxy's OpenGL 4.6 renderer compatible.

Evidence: `build/cross-refinement-2026-09-09/` contains `before.zip`,
`capture-before.log`, `capture-after.log`, `native-live-before.log`,
`final-verification.log`, `rust-tests.log`, `native-audit.json`, and `package-audit.json`.
The user's game mod directory was not modified. At inspection it contained both
`vss-0.2.15-neoforge-1.21.1.jar` and an older `vss-0.3-neoforge-1.21.1.jar`.
