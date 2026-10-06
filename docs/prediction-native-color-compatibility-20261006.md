**2026-10-06：预测原生地形后端的 Java 颜色兼容修复与验证**

已修复 `tools/rust/vss-native-core/src/biome.rs` 中会拒绝整个原生地形文档的颜色校验。Biome codec 导出的是 Java 有符号 `int`，并不是只含 RGB 的非负整数。现在草、叶、水三个字段接受完整的 Java int32 值，按 Java 消费者的逐通道移位/掩码语义保留低 24 位；字段缺失规则、类型校验和 int32 范围校验继续生效。

| 现场 biome / 字段 | Java 值 | 32 位表示 | 原生 RGB |
| --- | ---: | --- | --- |
| zombie_extreme:scorched_earth / grass_color | -13158105 | FF373927 | 373927 |
| zombie_extreme:scorched_earth / foliage_color | -13158105 | FF373927 | 373927 |
| zombie_extreme:scorched_earth / water_color | -12100265 | FF475D57 | 475D57 |

这里的输入范围是 `-2147483648..2147483647`。不接受整数范围外的 unsigned ARGB、浮点数、字符串、显式 null 或集合。资源色图的 unsigned RGBA 序列有独立的输入协议，本次没有改变。

Rust release 验证已完成 17 项测试：新增 `biome_colors` 5 项、既有 `surface` 2 项、既有 `worldgen` 10 项。新增用例覆盖现场值、有符号边界、正数 alpha、草色 modifier 的一致性、三个字段的错误类型/越界，以及可选草/叶与必填水色的原契约。命令如下：

```powershell
cargo test --locked --release `
  --target-dir C:/Users/Administrator/.codex/tmp/vss-loading-20261006/native-target `
  --test biome_colors --test surface --test worldgen
```

Windows x86_64 DLL 已用独立 Cargo target 构建，并同步至 Forge 与 NeoForge 两仓库的 `src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll`。两份大小均为 **1,544,704 字节**，SHA256 均为：

```text
03447C3F2AD8EF552A1449BB81C1D69C1E04DE8E682FE9D91360AFAA607B97D5
```

ABI 仍是 6。其他平台的预编译库本轮没有重建；需要使用更新后的 Rust 源码重建后，才能在那些平台获得本项修复。未替换正在使用的实例 JAR，未启动、关闭或附加客户端。

新增 `tools/rust/java/dev/xantha/vss/client/prediction/NativeSnapshotImport.java`，可通过真正的生产 JNI 接口导入完整原生文档，并与只做 RGB 归一化的同一文档比较状态表、feature schedule、6 个 surface record 和 6 个 tint。它已通过 `javac --release 17` 编译。另提供 Rust CLI `tools/rust/vss-native-core/examples/snapshot_import.rs`，用于严格导入真实完整文档；这个 CLI 不会补入测试 palette、色图或 biome source。

实际 DLL 回归使用了**混合测试文档**：现场捕获的全部 137 个 biome、configured feature、placed feature 注册表，加上既有 vanilla 1.21.1 地形/状态/色图夹具。测试将 biome source 固定到 `zombie_extreme:scorched_earth`，使采样直接使用这三个实际 ARGB 颜色。

| 同一混合测试文档 | 结果 |
| --- | --- |
| 原打包 DLL | `invalid biome color grass_color`，确实重现整体拒绝 |
| 修复 DLL | JNI 导入成功；ABI 6；137 biome；3 个 ARGB 字段归一化 |
| 修复 DLL 的 RGB 对照 | 6 个 surface record、6 个 tint 与 RGB 文档逐项相同；状态表与 feature schedule 相同 |

该次 JNI 导入记录为约 94 ms，总验证约 156 ms。这只是离线回归的时间，不能作为整合包加载耗时或优化幅度。其状态表只有夹具中的 172 个状态，不能替代整合包实际 canonical palette。

为推进真实快照验证，还只读解析了 DeceasedCraft_Beta 存档的 `level.dat`。实际 overworld generator 的 noise settings 是完整内联数据；biome source 有 7,450 条气候记录与 49 个基础 biome，surface rule 含 `terrablender:merged` 以及 BOP、zombie_extreme 材质。将 NBT 中由 BooleanCodec 保存的 0/1 flag 恢复为 JSON boolean，再与现场注册表组成部分原生文档后，严格 Rust 导入已通过 137 个 biome 的颜色解析与实际 Terrain 构造，随后因 `missing block definitions` 停止。存档内容没有修改。

**完整现场导入尚未完成。** 当前离线资料仍缺完整运行时 `block_definitions` / canonical `input_states`，以及生产 profile 另外捕获的 `vss_terrablender` 位置路由。NBT 中的基础气候列表不能代替这些运行时路由。检查当前选中的磁盘资源包及模组包，未发现 grass/foliage colormap 的覆盖文件，但也没有将 vanilla 色图当作运行时已解析的资源快照使用。

因此本轮可以确认：原来的三个颜色值不再导致 Windows 原生快照拒绝，实际 DLL 保持 RGB 一致，且已捕获的实际 density/biome 数据在当前已验证阶段没有新的兼容拒绝。仍不能声称现场已记录 `rust=true`、完整快照已导入，或加载/帧率已获得某个幅度的提升；surface rule、全部 palette 和最终 TerraBlender 路由仍需真实完整文档验证。

所有离线证据保存在 `C:/Users/Administrator/.codex/tmp/vss-loading-fixes-20261006/native-validation/`：

- `native-hashes.json`、保留的旧 `baseline-vss_native_core.dll`。
- `fixture-with-actual-137-biomes.json`、明确标注来源与限制的 `jni-probe-provenance.json`。
- `jni-baseline.log`、`jni-fixed.log`。
- `actual-worldgen-settings.json`、`actual-generator-nbt-raw.json`。
- `actual-partial-document.json`、`actual-partial-document-provenance.json`、`actual-partial-import.log`。

本项源码、回归用例、导入工具、Windows DLL 和本说明已在两个仓库同步；没有覆盖本轮其他优化或者既有 mountainTest 的文件、构建目录和证据。
