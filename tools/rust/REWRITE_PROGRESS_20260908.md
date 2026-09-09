# 原版算法迁移与村庄地表修复（2026-09-08）

> 本文记录当时的开发阶段与历史采集结果；当前生产后端、平台支持和构建方法以 [README.md](README.md) 为准。

数据足以开始实现并验证随机和噪声，尚不足以验收完整地形生成。此次已经编写并验证新 Rust 模块；没有根据有限样本拟合地形，也没有把历史粗采样当成原版真值。当时生产游戏继续使用已有地形后端，新库的开发接口只宣告噪声能力。

## 实际存档数据

源目录为 `F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/saves/` 下：

- `新的/.vss/prediction/rust-reference`：原 4 份。
- `新的世界 (3)/.vss/prediction/rust-reference`：新增 3 份。

复制到 `build/reports/rust-reference/combined-20260908/` 后分析。没有写入原存档或清理用户缓存。

| 对照项 | 合并覆盖 |
| --- | --- |
| 完整 capture | 7，全部文件 SHA256 和二进制/JSON 柱解码通过 |
| 种子 / 维度 | 2 / 主世界 |
| 柱记录 / 不同 XZ | 448 / 427 |
| 密度 / 噪声采样记录 | 1,344 / 224（每个噪声记录包含 60 个注册噪声输出） |
| 全高度基础柱 | 112 |
| 随机 / 植被区块记录 | 28 / 28（25 个不同植被区块） |
| 采样生物群系 | 31 |
| 实际植物输出 | 橡树、云杉、桦树、短草 |
| 实际结构 / 花 / 竹子 | 未采到；所有 manifest 的 structureChunks 都为 0 |

注册表存在 `create:config_filter`、`create:layered_ore`。完整注册表定义存在，不等于每一种 feature 的最终输出已被采到。植被结果来自 VSS 有界预测地面上的原版 feature 调用，不是完整真实 chunk 装饰真值；每份记录仍有 4–5 个 feature 被跳过。

旧 ABI 10 库用独立句柄正反序重放，56 个 grid 全部一致。其间距 1 的 112 个高度与原版 OCEAN_FLOOR_WG 全部一致。间距 16 / 256 / 4096 的平均绝对高度差分别为 3.75 / 4.2142857 / 11.2767857 格，最大 56 / 38 / 52 格。相同坐标 grid 与单列完整 32 字节的一致数分别是 112 / 47 / 33 / 30（每组共 112）。这些是可复现的粗采样语义差异，不能据此声称所有 bug 都由旧库引起。

## 村庄问题的实际修复

原流程只保留 `posY >= sample.surfaceY` 的结构方块，绘制又把结构底部裁到未修改的地表。道路和农田替换的是 `surfaceY - 1` 的方块，还低于整格顶面 1/16；结构清除的空气和灌溉水也未传递。结果是房屋、作物可见，但草地盖住农田和道路。

- 保留连续连接到地表的结构/feature 改写，包括地面方块、空气切除和水。与地表不连通的地下写入继续过滤。
- 非树木结构和地面修改保留一格 footprint。树木可继续按 LOD 合并。
- 只在受影响网格及邻格细分原地形，降低被替换处的旧地表；保持 GPU 原有 cell 归属，不生成洞穴下表面。
- 灌溉水进入水绘制通道，使用原版液体高度与现有水色；原水面遇到结构写入时移除重复覆盖。
- CPU → quad → GPU 全流程检查部分 cell 的覆盖和 15/16 高度；禁止把局部地面重新贪心合并成整格。明确传递网格间距，避免用第一个局部小面错误推断整个 tile 的间距。
- 为有界地表放置提供天空可见性和局部发光查询，避免农作物存活检查强行访问不存在的 light engine。这是地表预测查询，不是完整区块光传播实现。
- 地表缓存 schema 升为 2，旧结构缓存会未命中并按需重建；地形柱 schema 保持 1。版本仍为 `0.3-neoforge-1.21.1`。

测试包括原版 `plains_small_farm_1.nbt` 的实际模板放置、道路/农田/灌溉水/挖空/埋藏碎片、1/2/4/8 格精度、GPU 打包覆盖和缓存升级。没有自动安装到运行中的游戏，最终画面还需在用户同一村庄验证。此次没有改 PredictionVanillaMask。

最终 `build`（含 GPU 测试）516 项测试通过，0 失败/错误/跳过；JAR 内 PredictionVanillaMask 字节码与回退基线相同。产物为 `build/libs/vss-0.3-neoforge-1.21.1.jar`，2026-09-08 23:50:04，2,892,416 字节，SHA256 `5b7a31ae4ed7ac1fe9c5efd017f8ad6b740a5fba7f36212cc5fffa56901e39e1`。机器可读验证记录在 `build/reports/rust-reference/village-rewrite-validation.json`。

## 新 Rust 实现

`vss-native-core/src/random.rs` 实现 Legacy 48 位 LCG、Xoroshiro128++、WorldgenRandom、装饰/feature/结构种子、位置哈希与字符串派生。特别保留 Java 的有符号溢出、nextLong 低半字符号扩展、WorldgenRandom 包装 xoroshiro 时的两次取数语义。

`src/noise.rs` 实现 Improved、2D/3D Simplex、Perlin、NormalNoise，支持现代位置派生 octave、legacy 跳 octave、稀疏振幅、坐标 wrap 和 Y 缩放。保持原版浮点求值顺序，不启用 fast-math/FMA。

`src/jni_noise.rs` 与 `java/.../NativeNoiseReference.java` 是开发阶段 JNI ABI：独立名称、ABI 1、能力位 1（仅噪声），每批最多 65,536 点，每个输入是小端 XYZ 三个 double，输出一个小端 double。构造最多 64 个 octave，最多 4,096 个在用句柄。拒绝非法算法/参数/句柄/容量、重叠缓冲区和只读输出；先完整校验再写结果。关闭移除 ID，Arc 租约保护已开始的调用。没有逐点 JNI 或每点临时数组。

验证结果：

- 直接调用映射后的 Minecraft 1.21.1 Java 类生成 308 行夹具，7 个种子，97,440 个整数/浮点比较逐位一致；debug 与 release 都通过。
- 合并存档的 Java 噪声值和装饰随机序列共 14,364 个比较逐位一致。
- release DLL 经 JNI 对照 32,768 个 double 逐位一致；非法输入、切片、缓冲区重叠、关闭后调用和并发关闭检查通过。
- 测试输出分别为 `build/rust-release-validation.log`、`build/rust-captured-parity.log`、`build/rust-jni-noise-validation.log`。Windows x64 构建验证；其他平台尚未实测。

## 接续迁移与验收缺口

下一批依赖是 BlendedNoise、Gaussian/导数等剩余内核，以及 density router、spline、cache/interpolated/blend、aquifer。先对已有 1,344 条密度和 112 根全高度基础柱，再接通精确柱输出。之后迁移 surface rules、生物群系查询与草叶水颜色，最后是 feature 调度、放置修饰器、树干树冠/草花竹子和结构地形适配。

仍需完整真实 chunk 的结构与地表改写、跨区块植被、花/竹子/更多树型、下界末地、含水层/熔岩、颜色混合与模组地形样本。原版代码和数据提供算法来源，这些样本负责验收而非反推算法。当前 JAR 的村庄修复已经接入生产流程；新 Rust 噪声仅在独立开发接口验证，不宣称本轮降低了游戏 CPU 或已经替换整个库。
