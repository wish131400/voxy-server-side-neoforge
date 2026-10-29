# Meridian-inspired VSS implementation validation, 2026-10-16

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

The default Gradle entry point tests **live production source**. The earlier
`meridian-algorithms-20261016` experiment keeps its original frozen input and
results; it is a reference, not the implementation under test.

Production decisions:

- Preserve all four formal source edges during surface stabilization; finished
  geometry identity version is now 14, with sample caches left intact.
- Reuse LOD and real-boundary seams for exactly equal replacement inputs while
  publishing current tile references and retaining coverage/mask atomicity.
- Record terrain/surface/restore costs in the existing memory reservation system;
  retain the 128 MiB floor and only raise admission for larger measured outputs.
- Preserve exact water sorting but increment submission revision only when order
  or membership/ranges actually change.

Retired test-only candidates:

- Attribute compression passed historical exact image tests but made the full
  shaders slower. The encoder and all candidate harnesses have been removed.
- Dynamic demand changed far detail and lacked measured transition costs. Its
  copied planner/controller harness has been removed.
- Replaced historical harnesses were removed from `meridian-algorithms-20261016`;
  its captured inputs, frozen seam baseline, and measurements remain.
- The active box-occlusion comparison is now `raster-boxes-tests.gradle`, which
  uses live production shaders, meshlets, record encoding, and real forest inputs.

Run from the NeoForge root with JDK 21 (Forge uses the same entry point, JDK 17):

```powershell
./gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-21' -I tools/prediction/meridian-implementation-tests.gradle meridianImplementationTest meridianImplementationGpuTest meridianSeamBenchmarkTest jar --console=plain --max-workers=2
```

For the identical seam replay on the **old frozen production**, run separately:

```powershell
./gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-21' -I tools/prediction/meridian-implementation-tests.gradle -PvssMeridianBaseline meridianSeamBenchmarkTest --console=plain --max-workers=2
```

The baseline flag selects a different build directory and explicitly forbids a
release JAR. Do not replace `src/main/java` or the live source set to obtain an A/B.
Run GPU workloads sequentially, not concurrently on the two platforms.

After both platforms finish:

```powershell
& 'C:/Users/Administrator/AppData/Local/Programs/Python/Python314/python.exe' tools/prediction/meridian-implementation-20261016/collect_results.py
```

After removal of two candidate tests, the collector verifies 214 CPU + 10 GPU + 1 current seam test per platform, plus
one baseline seam test, then archives the two platforms' results into both repos. Attribute/demand CSVs
are historical evidence only, explicitly marked as such in the new summary.
`test-sources.txt` is platform-specific because Forge needs additional bootstrap
helpers; it is not the count of tests executed.

Artifacts:

- `results/neoforge`, `results/forge`: CSV, JUnit XML, build logs, production patch,
  JAR metadata, and bytecode inspection of mesh cache version.
- `summary.json`: medians, P95, range, counts, decisions, JAR hashes.
- `baseline/`, `baseline-sha256.json`: original 10 production files and the shared
  admission test fixture. Forge Renderer/MeshCodec are recovered from the verified
  original frozen source; its test fixture is shared apart from line endings.
- `validation-manifests.json`: source, resource, helper, evidence, and report hashes.
- `lib/meridian-implementation-20261016/` in each repo: independently built loader
  JAR; no test or experimental classes are shipped.

`cpu_ns` means host elapsed time, not thread CPU time. Water CSV rows span **64
frames**, so divide by 64 before quoting per-frame values. Allocations are
cumulative Java bytes, not peak memory. Demand transition accounting is in tile
keys, not GPU bytes. GPU queries are not complete Minecraft frame timings.

No client files are replaced and no game FPS/JFR sampling is performed by these
tools. See `docs/meridian-018-implementation-validation-20261016.md` for measured
benefits and the remaining in-game validation limits.
