# Tectonic 3.0.28 snapshot compatibility test

Target: Minecraft 1.21.1 / NeoForge, VSS 0.3.7, Tectonic
`3.0.28-neoforge-21.1`. Base VSS commit:
`415fdf0698bafe34a24a132ac6375ef5c6e82965`.

The reported server log fails in `DensityFunctionSnapshot.legacyTectonic` with
`NoSuchMethodException` for the six-argument ConfigNoise constructor, before a
worldgen profile can reach the client. Authoritative column streaming remains
active, so `session=true`, `columns>0`, `profile=false` is expected in this case.

The actual Modrinth 3.0.28 release has seven constructor arguments, including
`smootherScaling`. Its enabled path evaluates `(coordinate + shift) * scale`
and returns ConfigNoise from mapAll without invoking Visitor.apply. The disabled
path evaluates `coordinate * scale + shift` and maps to vanilla density nodes.

This patch reads the optional smootherScaling accessor and captures both layouts.
For smoother scaling it multiplies both horizontal shift functions by scale
before constructing vanilla shiftedNoise. Initialized noise holders, multiplier
and offset are retained. Floating-point distribution can change the final bits
of coordinates; the numeric regression uses a 1e-10 absolute density tolerance.

Root functions and direct Tectonic children of vanilla density records are also
normalized, because the smoother mapAll path can skip the visitor. A ClassValue
caches record accessor metadata; it stores no world/configuration state. Original
record fields are retained, and a record is rebuilt only if its direct Tectonic
child changes. The fifteen NoiseRouter roots are each processed explicitly.

Scope: Minecraft 1.21.1 / NeoForge. Forge 1.20.1 and its different
Tectonic releases are outside this compatibility patch's verified scope; no
Forge binary is represented as tested or included. No network protocol, cache
schema, native ABI or world-generation configuration changes are introduced.
The VSS build identifier is `tectonic-3.0.28-compat-2`; the standard file name and
0.3.7 protocol are retained. This local build is not an upstream release.

Reproduce with JDK 21:

```sh
./gradlew verifyModules test jar \
  --tests '*DensityFunctionSnapshotTest' \
  --tests '*ClientWorldgenStaticRegistriesTest' \
  --tests '*LithostitchedBiomeSourceSnapshotTest' \
  --tests '*ClientWorldgenRegistriesTest' \
  --tests '*TerrainGeneratorSnapshotTest' \
  -PvssTectonicJar=/absolute/path/to/tectonic-3.0.28-neoforge-21.1.jar \
  -PvssLithostitchedJar=/absolute/path/to/lithostitched-1.8.1-neoforge-21.1.jar \
  -PvssBuildId=tectonic-3.0.28-compat-2
```

The added release-JAR regression samples both smoother modes, four scales
(including zero and negative), and four graph shapes (root, holder, arithmetic,
and nested clamp/mapping). It compares original initialized noise to captured
and codec-restored graphs. Original snapshot/registry tests remain enabled.
Detailed results and skipped fixtures are recorded below.

These checks establish snapshot conversion behavior, not successful prediction
through the complete user's modpack. In-game acceptance: the logged constructor
exception disappears, `profile` becomes true, and prediction `built`/`tiles`
advance. Further generator/registry incompatibilities may surface afterwards.

## Follow-up: nested biome presets

The next user log confirms that profile export and receipt now succeed. Java
biome context initialization then fails because the client does not have
`minecraft:worldgen/multi_noise_biome_source_parameter_list`. Lithostitched
1.8.1 encodes its injector source with a `delegate` field. The previous VSS
encoder expanded only a top-level multi-noise preset, leaving the wrapped
`minecraft:overworld` preset dependent on that absent server-only registry.

The follow-up recursively expands typed `minecraft:multi_noise` sources using
the actual server parameter lists, through object and array children. It keeps
wrapper fields and ignores unrelated mod `preset` fields. Missing server
presets still fail explicitly; no substitute biome map or initialization bypass
is introduced. The change affects VSS-owned snapshot JSON only, not live biome
sources, world generation or ordinary rendering. The protocol and native
resources are unchanged. Existing snapshot sizes and cache content identities
continue to follow the encoded data; re-entering the world builds a new profile.

Regression coverage uses actual runtime vanilla registries, the actual network
registry layer, and the Lithostitched 1.8.1 release codec. It reproduces the
missing-registry exception before normalization, then checks client decoding,
wrapper preservation and 1,024 climate samples per restored source. Both
vanilla overworld and nether presets are also covered. The release wrapper codec
itself returns its delegate on decode; these checks do not establish parity for
all hidden injector/TerraBlender runtime routing in the user's modpack.

## Verification results

| Suite | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Five targeted snapshot/registry classes above | 19 | 0 | 0 |
| `architectureTest` | 12 | 0 | 0 |
| `columnFilterTest` | 124 | 0 | 0 |
| `moduleRegressionTest` | 310 | 0 | 3 |
| Total | 465 | 0 | 3 |

The three skipped tests are the optional `PredictionFarDecorationNativeTest`
fixtures; no native-test library was supplied. The broad historical suite,
full modpack game launch, native terrain parity and GPU rendering were not run.
The two original failures are reproduced by the released classes/codecs in CPU
regressions; the nested codec test asserts the missing-registry failure before
normalization. A subsequent in-game log confirmed the constructor failure was
resolved, but in-game acceptance of the nested preset correction is pending.

Release fixture SHA-256 values:

- Tectonic 3.0.28: `cf188c70e7dd94f6e3a81cef9fe99f1151f13217809f7576aaedb0c3e6547ae1`.
- Lithostitched 1.8.1: `e0da669fa5facc61a09b9b7bae21b322993de1fcd1eb05787adffb7309598cca`.

The local build used a temporary Gradle init script to load Mockito's existing
Premain-Class with `-javaagent`, because dynamic attachment was unavailable in
the test environment. The init script isolated the five selected test classes
and left the module gates' existing class selections intact. No test assertion
was disabled and no environment-specific paths or JVM options enter the mod JAR.
Java compilation, `jar`, archive integrity and manifest checks passed. Production
class bytes matched the compilation output; bundled native resources remained
byte-for-byte unchanged. The Forge 1.20.1 companion was not modified or verified:
this contribution targets the reported NeoForge 1.21.1 release combination.
