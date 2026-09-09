# Tectonic height mismatch: runtime noise seed aliases

The fresh world reproduces a real sampling error. The earlier attribution to changed terrain mods in an existing save was incomplete. Offline Java/Rust agreement did not validate the running modded generator.

## Confirmed cause

Tectonic `3.0.26-neoforge-21.1` contains `dev.worldgen.tectonic.mixin.NoisesMixin.tectonic$fixTectonicNoiseSeeds`. In `Noises.instantiate`, it changes the positional random name of `tectonic:parameter/<name>` to `minecraft:<name>`. The noise's octave parameters remain those of the original Tectonic registry entry. Other Tectonic noise names are unchanged.

The game and the client's reconstructed Java sampler execute that mixin; the standalone Rust implementation did not. Exporting the applied density JSON captures Tectonic's config values but does not capture this change to noise seeding. The old offline Java fixture also did not execute this mixin, so it agreed with the incorrect Rust terrain.

## Implementation

- `WorldgenCodecSnapshot` exports explicit `noise_seed_aliases` in the generator document when the Tectonic mod is installed. A standalone datapack using the same namespace receives no automatic rewrite.
- `RustWorldgenDocument` already preserves generator fields when creating the JNI document. No per-column Java callback is added.
- Rust `Graph::registered_noise` uses the alias only for the positional RNG name and keeps octave parameters and cache identity under the original registry entry. Aliases are applied once at graph construction, not while sampling terrain.
- `RustTerrainSampler.ALGORITHM` is now `vanilla-rust-abi2-r3`. This plus changed generator bytes separates corrected prediction caches from previous incorrect heights. No save or existing cache directory was deleted.
- Renderer masks, depth rules, and geometry offsets were not changed for this height fix.

## Independent live-server check

Read-only instrumentation queried the actual integrated server's `ServerChunkCache.randomState()` and `NoiseBasedChunkGenerator.getBaseColumn()` while the affected world was running. It did not transform classes, generate/store chunks, replace the game JAR, or modify game configuration. The generator seed was `4200473513645810264`.

At 13 coordinates, five Y values and four density roots produce 260 comparisons. Corrected native results match the running server within `1e-10`. All 13 solid base-column heights match exactly. Coordinates span the screenshot position and distant locations out to 16384 blocks. The baseline below uses the same native build/document with the alias metadata removed, so it isolates the missing rule rather than comparing unrelated JAR versions.

| X, Z | Running server base height | Without alias rule | Corrected native height |
| --- | ---: | ---: | ---: |
| -186, -50 | 99 | 103 | 99 |
| -128, 0 | 93 | 83 | 93 |
| 0, 0 | 63 | 57 | 63 |
| 71, 217 | 61 | 57 | 61 |
| 256, 256 | 89 | 68 | 89 |
| -512, -512 | 63 | 127 | 63 |
| 512, -512 | 10 | 167 | 10 |
| -1024, 0 | 96 | 67 | 96 |
| 0, 1024 | 35 | 44 | 35 |
| 2048, 2048 | 58 | 39 | 58 |
| -4096, 1024 | 65 | 64 | 65 |
| 8192, -8192 | -5 | 203 | -5 |
| -16384, 16384 | 12 | 112 | 12 |

These are base columns before structures, surface decoration, and player edits. They are not an assertion that every tree/building or the final displayed LOD seam has been visually verified. A full restart with the new JAR is still needed to verify the displayed result.

The captured inputs and results are in `build/new-world-snapshot/`. Run the optional live-reference regression with `-I tools/prediction/compat-tests.gradle -PvssLiveSnapshot=<absolute snapshot directory> --tests '*TerrainParityTest.liveSnapshotMatchesServerAndNative'`. `TectonicNativeTest.noiseSeedsMatchTheReleasedTectonicMixinAndKeepOriginalParameters` independently invokes the released mixin's remapping method and compares native noise against Minecraft `NormalNoise` for three seeds, five noise identities and four positions (60 comparisons). It deliberately gives original and alias entries different octave parameters to catch a parameter replacement error.

## Workspace handoff

The 36 differing files from the Codex worktree were synchronized to `C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1`, already on branch `main`. Existing files were backed up to `C:/Users/Administrator/Desktop/vss-main-backup-20260909-223942` with a manifest. Subsequent height fixes, tests and builds were made in that desktop checkout. Changes remain uncommitted; the original worktree and the desktop dev-baseline JAR remain available.

For a dedicated server, update both server and client: old server profiles do not contain the new seed alias metadata. For the local singleplayer world, restarting with the new client JAR also updates the integrated server exporter.

## Final validation and artifact

- Full Java, compatibility and GPU regression suite: **574 passed, 0 failures/errors/skips** (`build/height-alias-full.log`).
- Rust release tests: **30 passed, 0 failures** (`build/height-alias-rust-tests.log`).
- Cross builds: Windows x86_64; Linux x86_64/aarch64; macOS x86_64/aarch64. Packaged native audit verifies 30 JNI exports for every target (`build/reports/height-alias-native-audit.json`). Only Windows native execution was tested here; the other binaries were cross-built and statically audited.
- Migrating the tests exposed a missing temporary directory and a timing-dependent assertion that assumed the first scheduler tick could only publish root tiles. The test now verifies coarse parent coverage for any child published during that tick; production scheduling was not changed as part of the height fix.
- Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 4145623 bytes. SHA-256: `01771c014f352f151fe635de18f7446ce3f22f15b4b3bc73bdf5f3c27c9f491b`.
- The installed game JAR remains unchanged. Restart with this artifact to activate the new exporter/native backend and new prediction cache namespace.
