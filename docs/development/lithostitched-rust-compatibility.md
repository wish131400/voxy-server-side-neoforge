# Lithostitched Rust prediction compatibility

Target: VSS 0.3.7, Minecraft 1.21.1 NeoForge, Lithostitched 1.8.1,
Tectonic 3.0.28, Alex's Caves unofficial port 2.0.10.
Build ID: `tectonic-lithostitched-rust-compat-3`.
The earlier Tectonic smoother-scaling and nested multi-noise preset fixes remain included.
This batch is local work; no additional commit, push or PR update was performed.
Forge 1.20.1 is outside the requested and verified scope of this build.

## Behavior

The released Lithostitched injector codec writes its delegate and reconstructs
its additional function as identity. Project precisely that two-field contract
for native admission and input construction. Preserve the original generator,
profile and cache identity. Unknown fields, malformed delegates, more than 16
wrappers and unknown underlying codecs still reject native admission.
This does not prove support for arbitrary server-only injection functions.

The captured surface rules also contain `alexscaves:ac_biome`. Whitelisting only
the injector leaves this second blocker, so add a narrow JNI callback to the
installed Alex's Caves implementation. The Java adapter invokes its rarity,
biome and configuration APIs; it does not approximate the Voronoi/noise algorithm
or replace the condition with a biome-name check. Native world construction
verifies the API. Above Y=50, a zero seed and non-overworld dimensions preserve
the released condition's false result. Unsupported API initialization retains
the existing Java fallback. An evaluation exception stays pending in JNI and
aborts the guarded job rather than returning false and publishing wrong material.
No additional JNI entry point or ABI version is needed.

Full queues used strict distance ordering, withdrawing already admitted work
for tiny same-band distance improvements. Require a half-tile distance advantage
(using the smaller tile span) for those replacements. A better visibility band
still preempts immediately; completion admits waiting work, and deferred demand
is retained. Capacity, LOD resolution and render distance are unchanged.

## Verification and limits

- Java focused and released-codec regression, module gate, and planning consumers:
  see packaged `verification/results.json` for exact suite totals.
- Captured density replay: 416 positions, 15 noise-router outputs, 6240 values;
  zero mismatches at 1e-8 and maximum absolute error 0.0. This replay uses an
  isolated surface callback fixture which must never be called during density
  sampling. It proves graph admission and noise parity, not full mod gameplay.
- Controlled JNI surface fixture: real native surface evaluation verifies seed,
  signed coordinates, rarity offset, material, initialization/evaluation exception
  propagation, dimension guard and world-handle closure.
- Production Java adapter tests exercise reflection, missing/mismatched mod
  results, signed quart coordinates and exception propagation. Method signatures
  were checked against the actual released Alex's Caves 2.0.10 JAR.
- Rust library suite: 88 passed, 5 pre-existing ignored. One existing exhaustive
  exterior-batch test exceeded the validation time budget and was interrupted;
  it was excluded from the bounded rerun. It is not counted as passed.
- Same-band synthetic replay: 6561 demands, capacity 4096, 240 camera updates
  along a 128-block-radius path: 3965 old replacements versus 3520 new (11.22%
  fewer). With an 8-block-radius path: 332 versus 9 (97.29% fewer).
  Both are synchronous queue replays, not frame-rate or real-world throughput measurements.
- All five native platforms rebuilt. Linux x86_64 JNI executed. Windows x86_64
  uses a GNU-target DLL; all 47 JNI exports were checked, and its imports need only
  Windows/UCRT system libraries. The Linux-hosted build uses
  `python tools/rust/build-windows-gnu.py` with Rust target
  `x86_64-pc-windows-gnu`, cargo-zigbuild 0.23.4 and Zig 0.13.0.
  An explicit positional .def input preserves exports with that linker wrapper.
  Windows, macOS and ARM execution and the full installed Alex's Caves runtime
  remain unverified. No new GPU or complete-modpack gameplay test was run.

The uploaded performance capture showed continuing publications, not a frozen
worker: 168 publications in about 70 seconds. Queue size alone cannot establish
stagnation. Replacements there are deferred-demand reordering, not deletion of
world chunks. No world save, ordinary Voxy renderer or Picture Mode code changes.
Private captures, seeds and positions are not included in this source or package.

## Reproduce

With JDK 21 and the released mod JARs:

```sh
./gradlew -I tools/verification/lithostitched-rust.gradle lithostitchedRustTest \
  -PvssTectonicJar=/path/to/tectonic.jar \
  -PvssLithostitchedJar=/path/to/lithostitched.jar \
  -PvssTestNativeLibrary=/path/to/libvss_native_core.so
./gradlew verifyModules planningConsumerTest
./gradlew jar -PvssBuildId=tectonic-lithostitched-rust-compat-3
cargo test --manifest-path tools/rust/vss-native-core/Cargo.toml --locked --release --lib \
  -- --skip terrain::exterior_batch_probe::bounded_batch_preserves_full_depth_roofs_fluids_and_sheltered_caches
```

Queue replay: `python tools/prediction/lithostitched-rust/run_replay.py` with `JAVA_HOME`
pointing to JDK 21. The archived baseline is the pre-change queue from commit
`4656eb13a5cd005762fe8e6a0de2548ddb76b9be`; asynchronous preparation and
residency behavior are covered separately by the Java regressions.

The standalone tools under `tools/prediction/lithostitched-rust/` require Gson
and compiled production classes (including Minecraft dependencies for the
capability check). Compile their `SurfaceFixture.java` only in an isolated JVM
classpath with `RustWorldgenBackend`, `RustBiomeSourceProjection`, `VerifyCapture`
and `VerifyBridge`. It intentionally shadows the production adapter to test JNI
transport; it must not enter a mod JAR or normal test classpath. Run VerifyCapture
with a private capture directory and native library path; run VerifyBridge with
that library and `tools/rust/vss-native-core/tests/fixtures/worldgen`.
Native build provenance and individual binary hashes are in the JAR's
`META-INF/vss-native-build.json`.
