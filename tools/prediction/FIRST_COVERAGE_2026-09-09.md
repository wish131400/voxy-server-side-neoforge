# 预测粗模首次显示延迟修复

2026-09-09 游戏日志中，第一块预测 tile 为 lod=10，`samples=4356`，
`buildMs=7820.2`，生成 172,308 个顶点。日志保留在
`build/prediction-first-coverage/game-before.log`。

这说明首批粗模仍使用完整的 66×66 采样网格，而 Rust 的稀疏点当前会计算
全高度密度、液体和表层邻居。整个网格完成后才发布；子级还必须等它的父级
已有覆盖才能进入队列。另一个浪费是边缘批次补成 8×8：输出虽然只有
4,356 个点，旧 Rust 调用实际产生 5,124 个点，丢弃其中 768 个。

## 实现

- Rust 后端的首次 LOD >= 2 使用 8×8 地表格子及边缘，共 10×10=100 个
  真实采样点；仍使用原有地形、材质和颜色采样器。
- 每块 tile 的世界范围保持一致，仅临时采样间距较大。远景先获得可渲染
  覆盖，近处子级可继续执行，父级无需先补满 64×64 网格。
- 当前规划的最终叶节点随后升级到完整网格；构建期间保留之前的 mesh，
  完成后原子替换。LOD 0/1 继续使用完整网格，植被只在完整地表上执行。
- 预览不写入长期 terrain cache。已有完整缓存直接恢复完整网格，跳过
  预览和采样。脏列更新继续拒绝过时的构建结果，并包含临时网格的边缘范围。
- Rust Java 适配器支持 8×2、2×8、2×2 的边缘批次，不再计算无用点。
  JNI ABI 与 Rust DLL 保持本轮开始时的优化版本。
- 首块 tile 的计时输出受 `debugLogging` 控制。

## 验证结果

`verifyNativeWorldgenIntegration` 使用真实 Rust 采样器和生产 tile manager，
取 seed=0、plains 测试文档，单 worker，65536 方块范围的同一 tile：

| 阶段 | 采样点数 | 本次耗时 |
| --- | ---: | ---: |
| 首次可渲染粗模（包括颜色、mesh 和 GPU payload 准备） | 100 | 162.09 ms |
| 随后补齐完整网格 | 4356 | 4802.34 ms |

该计时不包含 worldgen profile 解码、真实 GPU 上传或显示帧，也不是与
游戏日志 7820 ms 的同环境 A/B。不同生物群系、CPU 竞争、缓存及模组可能
改变耗时；结论是首批显示已不再等待整个完整网格。

新增测试验证：首批在 100 点后发布；最终采样阻塞期间保留预览；升级前后
世界坐标和覆盖范围一致；子级无需等待父级完整网格；预览不污染缓存；重进
直接使用完整缓存。矩形批次在原生稠密和稀疏路径上与逐点结果一致。

最终共 518 项 Java/GPU 测试通过，0 失败、0 错误、0 跳过；生产 JNI 的
1064 个网格/材质/颜色检查和 7 组混合植被任务通过。
`PredictionVanillaMask.class` 与本轮之前一致。

运行：

```text
gradle --offline -I tools/rust/reference.gradle -I tools/prediction/gpu-tests.gradle verifyNativeWorldgenIntegration build
```

日志：`build/prediction-first-coverage/final-verification.log`。
包验证：`build/prediction-first-coverage/package-verification.json`。
产物：`build/libs/vss-0.3-neoforge-1.21.1.jar`，3,406,577 字节。
SHA-256：`9BE1544B142D88CF63DE0C5931E5C7E615713A7CBAA4F8B61AC39A3EEC023B15`。
只更新工作区产物，未替换游戏目录中的模组。
