# Rust worldgen 性能审查 — 2026-09-09

本次优化对象是当前 ABI 2 原版流程 Rust 后端，比较的是本轮修改前与修改后。
历史二进制及更早的 Rust 日志不作为这次对照程序。

## 测量方法

- Windows x86_64，Cargo release：opt-level=3、thin LTO、codegen-units=1。
- 输入为同一份生产导出的 26,684 状态快照，保存在
  `build/rust-performance-review/production-document.json`。
- 快照 SHA-256：`06F6A11B39320D468B570DB3A8A5B800F09640A5EF2537F25F32A0B8BDD6890D`。
- seed=0，zoom_seed=0；相同坐标、生成高度、表面材质/颜色和邻域范围。
- 每轮启动两个独立进程，交替 before/after 顺序，串行测量，共五轮。
- 记录每轮完整输出，并对三个完整地表区块、64 个稀疏点和 80×384×80
  邻域结果计算校验值；所有五轮优化前后的五组校验值一致。
- 没有减少采样高度、植被邻域、精细度或改变材质规则。
- 这些数据是离线生成耗时，不是游戏帧时间或 FPS。

## 热点与实际修改

临时阶段计时记录在 `build/rust-profile-stderr-2.log`：冷 `surfaceProxy`
总计约 3537 ms，列查询/生成 3531.313 ms，体积填充 5.603 ms；单次内存
分配调用约 0.016 ms（该数值不包含之后触碰内存页的成本）。基础地形阶段
占列生成的大部分，地表处理其次。最终源码已删除临时计时和 stderr 输出。

1. `density.rs`：原先每个方块对每个插值节点查询八次角点 HashMap，即使
   相邻方块仍在同一插值单元。现在每个插值节点分配一个紧凑槽位，保存当前
   单元的八个角点；跨单元时才访问原角点缓存。保留 Block/Cell 各自的浮点
   插值顺序，Raw/Single 继续走原路径。存储量按插值节点数增长，且为任务私有。
2. `blocks.rs`：原先扫描地表、判断空气/液体、读取 motion blocking 和植被
   标签时，反复由状态查名称、再用名称查方块定义。现在使用状态 ID 对应的
   定义索引，并在状态注册时预计算 air/fluid 标志；waterlogged 属性继续优先
   决定液体标志。新增元数据每状态 8 字节，使用 Arc 共享，修改时复制，避免
   每个 volume 复制整套注册表。

JNI 计算过程中没有持有全局 world/volume 句柄表锁；每个任务使用独立 volume。
本轮保留 feature 提交、Java 兼容 feature 的读写顺序和取消语义。`surfaceProxy`
仍然生成 5×5 区块上下文，不省略邻居来换取速度。

## 五轮中位数

| 测量项 | 修改前 | 修改后 | 耗时变化 |
| --- | ---: | ---: | ---: |
| 读取文档及初始化 World | 150.84 ms | 145.88 ms | −3.3% |
| 单区块基础地形+地表 (0,0) | 145.18 ms | 46.17 ms | −68.2% |
| 单区块基础地形+地表 (13,-17) | 139.06 ms | 58.52 ms | −57.9% |
| 单区块基础地形+地表 (1234,5678) | 131.33 ms | 54.65 ms | −58.4% |
| 64 个首次稀疏采样点 | 150.06 ms | 100.83 ms | −32.8% |
| 5×5 首次 surfaceProxy | 3392.70 ms | 1299.83 ms | −61.7%，约 2.61 倍吞吐 |
| 从已缓存列重建 surfaceProxy | 5.43 ms | 6.18 ms | +0.75 ms，未改善 |
| 64 个缓存点，每批 | 0.0017 ms | 0.0019 ms | +0.0002 ms，未改善 |

缓存命中路径没有测出收益，不能把它算作已优化。当前剩余的首次邻域生成仍有
约 1.3 秒单线程成本；端到端等待时间还包含调度、结构/模组 Java 兼容路径、
网格构建及上传。这个微基准不能证明游戏跑步掉帧全部解决。

完整逐轮数据、输入及二进制指纹：
`build/rust-performance-review/paired/results.json`。
分阶段实验：`baseline-profile.log`、`cell-profile.log`、`palette-profile.log`，
均位于 `build/rust-performance-review`；这些实验只用于定位，不替代最终交替对照。

## 验证与复现

Rust 全量测试通过：原版噪声/密度、21,120 个基础柱方块、3,604,480 个地表
方块、400 个稀疏/完整地表对照、171 组 placed features、261 组 configured
vegetation，以及结构地形调整和事务回滚。新增 3,120 个复用/全新插值缓存对照，
覆盖正负坐标、单元边界、模式切换、多个节点和不同单元尺寸；同时验证含水状态、
非法属性和独立 palette 克隆的 ID/标志一致性。

最终 Java/JNI/GPU 验证通过：516 项测试，0 失败、0 错误、0 跳过；32,768 个
JNI 噪声值、1,515 个 JNI 密度值、21,120 个 JNI 基础方块、29 组 JNI 植被案例
通过，生产桥接的 560 个网格/材质/颜色采样及 7 组混合 Java/Rust 植被任务通过。
GPU 回归设备为 NVIDIA GeForce RTX 4070 Ti SUPER。

产物为 `build/libs/vss-0.3-neoforge-1.21.1.jar`，3,405,833 字节。
JAR SHA-256：`4ECCD049D69658A27F781257D083220F545099C1FAF73AFDA5D8678433029F44`。
JAR 中的 Windows x86_64 DLL 与测试后的 release DLL 一致，SHA-256：
`292C3155BE9A009AD496B8FDE6FFA1CCF862505DB3C112BDFDA2BA9E54C5BE6F`。
`PredictionVanillaMask.class` 与修改前 JAR 的内容完全一致。构建只更新工作区
产物，没有替换游戏目录中的模组。包校验结果保存在
`build/rust-performance-review/package-verification.json`。

```powershell
cargo test --locked --release --manifest-path tools/rust/vss-native-core/Cargo.toml -- --nocapture
cargo build --locked --release --manifest-path tools/rust/vss-native-core/Cargo.toml --lib --example worldgen_bench
tools/rust/compare-worldgen.ps1 -Before build/rust-performance-review/before/worldgen_bench.exe -After build/rust-performance-review/after/worldgen_bench.exe -Document build/rust-performance-review/production-document.json -OutputDirectory build/rust-performance-review/paired -Runs 5
gradle --offline -I tools/rust/reference.gradle -I tools/prediction/gpu-tests.gradle verifyNativeNoise verifyNativeWorldgen verifyNativeWorldgenIntegration build
```

对照脚本需要保留修改前后编译出的 example 程序；两者均保存在本次 build 目录中。
重新编译优化后源码不会自动重建修改前程序。报告和日志不进入运行中的游戏日志。
