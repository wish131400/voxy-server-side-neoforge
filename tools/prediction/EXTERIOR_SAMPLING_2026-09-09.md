# Bounded exterior sampling

The sparse native surface path previously generated the target column down to the dimension floor, even though the published record contains only the outer solid boundary, fluid boundary, three material states and biome tints. This change stops base-column evaluation after retaining the exterior and the extra depth required by surface rules.

The rule walker evaluates the maximum downward stone-depth threshold using the actual column surface-depth and secondary-noise values. The retained range includes the three material sample depths and an additional guard. Threshold arithmetic overflow or padding above 64 blocks disables truncation. Surface rules still run against the retained original blocks; ores, aquifers and density are evaluated normally at those positions.

After the surface pass, the final material sample range must remain inside the proven range. If a rule removes solid blocks or converts them into enough liquid to move the output outside it, the complete column is recomputed. Eroded badlands and frozen-ocean geometry use complete columns from the outset. Graphs with order-sensitive vertical cache dependencies retain complete traversal. Existing sparse-neighbour fallback behavior is preserved.

This changes sparse surface sampling only. Complete surface regions and chunks, structure placement, and decoration are not simplified. The cache identity stays unchanged because outputs must match the existing complete calculation.

## Verification

- 30 Rust tests passed across the full suite and the additional Nether/End regression.
- Existing 400 sparse/full comparisons cover ten biome modes and two seeds, including multi-noise, snow, water, badlands and frozen oceans.
- Added custom ceiling-depth, surface-to-water, surface-to-air and order-sensitive density tests.
- Added Nether, End and void comparisons against complete chunks, including negative coordinates.
- Full packaged Java/JNI/mod/GPU test invocation: 561 tests, zero failures and zero skipped.
- All five libraries rebuilt: Windows x64, Linux x64/ARM64, macOS x64/ARM64. Package audit confirms 30 JNI exports per target. Execution tests ran on Windows; cross compilation is not runtime testing on Linux/macOS.

## Measurement and limits

Using the same six vanilla sparse points with fresh job scratch state, one warm-up plus five measured passes: complete-depth median 9.705 ms, exterior median 7.581 ms (about 22% less time). World construction is excluded. Both paths are compared for all ten output integers. This local microbenchmark is not a Tectonic whole-route or frame-rate measurement. Work above the surface and special-case full-column work remain.

Logs: exterior-rust-verified.log, exterior-dimensions-test.log, exterior-java-tests.log, exterior-native-package.log. Native audit: build/reports/exterior-native-package.json.

JAR: build/libs/vss-0.3-neoforge-1.21.1.jar, 4,138,075 bytes.
SHA-256: bb9d22e97ed0a5a877a07b6faa12d328e1b0028a658d3fffb07a66fb99524472.

The game instance and saves were not modified.
