> Correction after a fresh-world reproduction: the original explanation below was incomplete. The offline Java oracle did not execute Tectonic's `NoisesMixin`, which changes positional noise seed names. Agreement with that oracle did not establish agreement with the running game. The confirmed cause, fix, and live-server comparison are documented in [TECTONIC_NOISE_SEEDS_2026-09-09.md](TECTONIC_NOISE_SEEDS_2026-09-09.md). Changed mod history remains a separate limitation for existing chunks, but does not explain the fresh-world bug.

# Height mismatch and preview scheduling

The reported world has had its terrain mods changed or updated (confirmed by the user). Its enabled pack is now Tectonic 3.0.26 with default terrain settings and seed 1011965752357175303. Read-only inspection of existing region heightmaps gives an ocean floor of 137 at (71,217), 187 at (-17,35), and 128 at (256,256). Current Tectonic base terrain gives 107, 64, and 112 respectively in both Minecraft Java and Rust. Heightmaps include later surface/feature changes, so these are not block-for-block generation assertions, but the differences and changed generator history explain why old mountains cannot be reconstructed using only the current generator.

The new `TerrainParityTest` decodes Tectonic's released base pack and applicable mod overlay with default config constants/noise settings, applies the production density snapshot/reference compaction, and compares Rust against Minecraft's independent Java density and base-column implementations. It checks five coordinates and five density heights per coordinate for vanilla and Tectonic. It uses fixed plains for the column test; it does not claim complete modded biome/structure/feature parity or recreate the old world generator. No height offsets or renderer masks were changed.

The supplied 13:47 dev JAR used 8-cell first coverage and rejected non-Minecraft density types in its capability gate. Its Tectonic execution path therefore cannot be described as an identical Rust-only baseline. The later build increased initial grids to 16 cells and used 512-block work bands; the live log showed 141 preview tiles, zero full tiles/vegetation, 658847 accumulated worker milliseconds in sampling and only 201 in meshing, with zero disk hits. Accumulated worker time is not elapsed time or an FPS measurement.

Changes:

- Keep 16-cell initial previews at LOD 0–1; use 8-cell first coverage for larger ancestors. Including margins, distant first grids drop from 324 to 100 samples. Subsequent refinement still doubles resolution toward the existing target, including telescope targets.
- Use 64-block work bands so nearby full terrain and vegetation advance before previews farther away. A 128-block experiment still delayed the first vegetation in the single-slot regression. The regression must now reach vegetation within 35 cycles rather than the previous 150-cycle allowance.
- Keep capture priorities, authoritative captured samples, target detail bands, native terrain output, water rendering fixes, and the existing five bundled native targets.

Measured using the same packaged native backend, seed, Tectonic document and 4096-block tile footprint, with a fresh world handle for each grid:

| First grid | Tectonic | Vanilla |
|---|---:|---:|
| 16 cells / 324 samples | 2892.078 ms | 342.295 ms |
| 8 cells / 100 samples | 846.780 ms | 97.403 ms |

These are single offline tile measurements, not end-to-end dev/new loading comparisons or a total loading-time guarantee. A native min/max shortcut experiment preserved the sampled checksum but showed no stable speed improvement and was removed.

Validation: 570 tests passed, zero failures/errors/skips, including optional released-mod and GPU tests. All five packaged native binaries passed architecture/dependency/JNI-export audit. Windows JNI was executed; Linux/macOS were inspected only. Reports: `build/dev-comparison/final-full.log`, `build/reports/height-speed-native-audit.json`. No game JAR, save, or configuration was replaced. Height differences in regions generated with the old mod set remain a limitation until authoritative terrain is available; this build does not claim to restore their old generation rules.
