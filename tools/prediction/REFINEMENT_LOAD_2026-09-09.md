# 精细化阶段减负（2026-09-09）

本次解决粗模出现后，完整地形网格计算成本过高、长时间没有新的细化结果的问题。
修改基于前一轮首批 100 点预览版本；前一轮的首批加载优化不计入本次收益。

## 实现

1. `RustTerrainSampler.sampleGrid` 的完整区块采样阈值由 `<8` 改成 `<4`。
   间隔 4 格时，每区块需要 16 个目标点，原路径却生成 256 根完整地形柱。
   现在调用原生稀疏批次。间隔 1～2 格仍保留完整区块路径与缓存，供近处
   地表细节、植被邻域复用，避免把已经省下的地形工作转移给植被阶段。
2. JNI `surfacePoints` 改用 Rust 的分组批次：同一区块内的点共用密度插值角点、
   含水层位置和预备高度缓存，按原输入顺序返回结果。每批仍最多 64 点，
   保留重复坐标、取消检查、边界校验和一次性结果写入。ABI 与持久缓存身份不变。
3. 普通坡度邻居只采样至首个非空气方块，因为地表坡度只读取其高度。
   目标柱仍完整采样；先前邻居若涉及恶地或冰山几何，仍生成完整柱。
   会改变前序高度且不能安全局部求值的地表规则，继续回退完整区块。
4. 已显示的 8×8 预览按 16×16 → 32×32 → 64×64 逐档更新。
   每档结束后回到现有近处优先队列，下档完成前保留上一档。原生点缓存复用
   对齐坐标；仅最终完整网格可写入地形磁盘缓存。脏列先按当前精度重建，
   随后的普通升级再提高精度。已缓存的完整网格仍直接加载。

本次没有修改遮罩、植被 feature 顺序、最终采样精度、线程数量或内存预算。
没有替换游戏目录的模组或清除存档缓存。计时在离线工具内，运行时未增加刷屏日志。

## 冷缓存对照

保存旧、新 release 可执行文件，对同一生产快照、种子 0、66×66 个点运行三轮
交替顺序测试。下表为三轮中位数。时间包括 Rust 地表采样，不包含渲染或 FPS；
所有批次的高度、液体、材质、颜色记录的完整校验值一致。

| 条件 | 修改前 | 修改后 | 耗时变化 |
| --- | ---: | ---: | ---: |
| 间隔 4 格，完整网格 | 12,451.51 ms | 3,501.37 ms | -71.9%，约 3.56 倍吞吐 |
| 上述每轮最长 64 点批次的中位数 | 346.21 ms | 58.56 ms | -83.1% |
| 间隔 1024 格，完整网格 | 4,807.40 ms | 4,595.46 ms | -4.4% |
| 上述每轮最长批次的中位数 | 80.18 ms | 81.39 ms | 无改善 |

极远处的点各自位于不同区块，同区块共享的收益有限，不宣称所有距离都获得
3.56 倍提升。批次耗时也不是任务抢占时限或游戏帧时间。

一次额外路径探测中，1 格完整/稀疏分别为 1,442.82 / 2,318.74 ms；
2 格完整/稀疏分别为 3,765.09 / 2,589.19 ms。2 格虽然孤立的稀疏地形计算
更快，但近处植被仍需完整区块邻域，因此本轮保留 1～2 格完整缓存路径。

## 实际生产桥接与发布

`NativeWorldgenIntegration` 使用打包的 DLL、真实 `RustTerrainSampler` 和
`PredictionTileManager`，只允许一个 builder，对跨度 65,536 方块的同一个
冷缓存 tile 测量，包含颜色、网格与 GPU 上传数据准备；不包含 profile 解码、
实际 GPU 上传、显示或植被生成。

| 发布结果 | 本档耗时 |
| --- | ---: |
| 首批 8×8（100 个含边缘采样点） | 153.17 ms |
| 16×16 | 264.69 ms |
| 32×32 | 956.85 ms |
| 64×64 | 3,423.85 ms |

三个细化阶段总计 4,646.32 ms。相比此前必须等待整张 64×64 网格才更新，
现在约 265 ms 即可得到第一档细化；最终阶段仍可能持续数秒。
阶段累计计时：采样约 4701 ms，网格约 63 ms，打包约 20 ms（包括初始预览）。
这确认该例的等待主要在采样，不能据此判定用户所有跑动掉帧均已消失。

## 验证

- Rust 全量 release 测试通过，包括 3,604,480 个原版地表方块比较、
  21,120 个基础柱方块、植被及事务回滚回归。
- 400 组稀疏/完整柱对照增加独立冷缓存的分组采样比较；覆盖普通地表、
  恶地、冰山、混合生物群系、多个种子和区块边缘。
- 新增跨区块乱序、重复坐标、越界、超量和已取消缓存批次回归。
- Java/JNI 集成：1,064 个网格/材质/颜色采样、7 组混合 Java/Rust 植被任务；
  另有 1,515 个 JNI 密度值、21,120 个基础方块、29 组植被及非法句柄校验。
- 分档测试确认旧网格保留、完整精度收敛、中间结果不污染磁盘缓存、
  脏列保持当前精度重建以及父预览允许子节点启动。
- 最终 519 项 Java/GPU 测试，0 失败、0 错误、0 跳过。
  GPU 回归设备：NVIDIA GeForce RTX 4070 Ti SUPER。

## 产物与复现

`build/libs/vss-0.3-neoforge-1.21.1.jar`，3,407,433 字节。

- JAR SHA-256：`9B3A14463AB38763216234FFA0BFE9B89A91F4E9ED7A347904F2B69E596E7E94`
- 已测试且内嵌的 Windows x86_64 DLL：`6A9DB692AA46D9202FEA42EA94C19C92F1625EC3277DA38179D6A6CC80E8F453`
- `PredictionVanillaMask.class` 与本轮前 JAR 完全一致：`9B647893F2F1BC55BBAE0F40DFD0A37AE6351B71AC6009C1F0CA9978F5E83B5B`

备份、原始对照结果、测试输出与包校验在 `build/refinement-load-2026-09-09/`。
前后 exe 已分别保存在该目录的 `before/` 和 `after/`，重新编译不会重建旧版本。

```powershell
cargo test --locked --release --manifest-path tools/rust/vss-native-core/Cargo.toml -- --nocapture
cargo build --locked --release --manifest-path tools/rust/vss-native-core/Cargo.toml --lib --example refinement_bench
tools/rust/compare-refinement.ps1 -Before build/refinement-load-2026-09-09/before/refinement_bench.exe -After build/refinement-load-2026-09-09/after/refinement_bench.exe -Document build/rust-performance-review/production-document.json -OutputDirectory build/refinement-load-2026-09-09/paired -Runs 3
gradle --offline -I tools/rust/reference.gradle -I tools/prediction/gpu-tests.gradle verifyNativeWorldgen verifyNativeWorldgenIntegration build
```

运行基准时不要同时编译或跑测试。更新 DLL 后应先同步到资源目录再执行 Gradle，
并核对 JAR 内嵌 DLL 与已测试的 release 文件哈希一致。
