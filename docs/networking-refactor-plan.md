# VSS Networking 核心重构方案

> 范围：`dev.xantha.vss.networking` 及其依赖的 `common` / `config` 纯逻辑。
> 目标：在**不破坏同步语义**的前提下，把"架构先进但代码质量低"的核心算法，改造成分层清晰、可测试、可维护的结构。
> 约束共识：**全量分阶段推进**；**可接受协议演进**（允许 bump `PROTOCOL_VERSION` 做破坏性协议改进）。

---

## 0. 现状评估

### 0.1 规模

| 文件 | 行数 | 角色 |
| --- | ---: | --- |
| `client/LodRequestManager.java` | 1948 | 客户端请求调度中枢 |
| `server/VSSServerNetworking.java` | 1431 | 服务端静态神类 / 入口 |
| `client/FarPlayerClientRenderer.java` | 1391 | 远处玩家渲染 |
| `server/PersistentColumnLodStore.java` | 878 | 持久化 .vcl + region 索引 |
| `server/ChunkGenerationService.java` | 814 | 区块生成 + 后台打包 |
| `server/PlayerRequestState.java` | 760 | 单玩家请求/发送/预加载状态 |
| 其余 payload / cache / command | ~3200 | — |
| **合计** | **~12500** | — |

测试覆盖：**0**（`src/test` 此前为空）。

### 0.2 架构判断

算法设想本身是**先进且自洽**的，不应推倒重来：

- 客户端以玩家为中心的切比雪夫环形扫描 + 分档限速；
- 服务端五级查询链：内存缓存 → 持久化 `.vcl` → 已加载区块快照 → 区块 NBT → 区块生成；
- Region 索引 LRU、presence 摘要去重、每玩家带宽令牌桶、生命周期 epoch 防护。

问题**不在算法，在工程组织**。以下六类坏味道是重构的真正对象。

### 0.3 六类核心坏味道

1. **静态神类（God Object）** — `VSSServerNetworking` 全静态：`PLAYER_STATES`、两个磁盘线程池、15 个 `AtomicLong`、三个缓存单例。无法单测、无法多实例、生命周期靠 `serverStopping` 布尔量 + `serverLifecycleEpoch` 手动兜底。
2. **超长方法 + 超多参数** — `handleBatchRequest` ~120 行；`finishDiskRead` **14 个参数**；`submitStorageRead` 9 个参数。参数爆炸是"缺一个对象"的信号。
3. **复制粘贴的并发防护** — `if (serverStopping || lifecycleEpoch != serverLifecycleEpoch.get())` 在服务端出现近 10 次；`pendingDiskReads` 的增减在每个磁盘任务头尾手写，极易漏改导致计数泄漏。
4. **客户端状态机失控** — `LodRequestManager` 约 40 个实例字段管理 scan / presence / retry / deferred / generation 五套并行状态，靠十几个 fastutil map/set 手工维持一致性，没有状态边界。
5. **魔法数字遍地** — 两个大文件顶部各有二三十个 `private static final` 常量，调优逻辑无从追溯。
6. **零测试** — 多线程 + 磁盘 IO + 主线程调度交织，却没有任何回归网。**这是重构的最大障碍，也是第一步必须解决的。**

---

## 1. 重构总策略

### 1.1 顺序原则：先立网，再动刀

**不允许在没有测试的情况下改动并发结构。** 顺序严格如下：

```
补测试（纯逻辑） → 抽纯逻辑（去 Minecraft 依赖） → 拆神类（引入实例服务）
        → 收敛并发样板 → 拆客户端状态机 → 消除魔法数字
```

每一步都可独立提交、独立回滚，且每步结束时全量测试必须为绿。

### 1.2 可测试性判据

一段逻辑能否单测，取决于它是否依赖 Minecraft 运行时。据此把代码分三层：

| 层 | 依赖 | 可测性 | 例子 |
| --- | --- | --- | --- |
| **纯逻辑层** | 仅 JDK + fastutil | 直接单测 | 令牌桶、切比雪夫排序、offset 环、region 窗口、presence 编解码 |
| **协调层** | 纯逻辑 + 少量 MC 接口 | 用接口 + 假实现测 | 请求分派决策、查询链选择 |
| **集成层** | 深度绑定 MC / Forge | 靠 gametest 或人工 | 线程池、区块 ticket、网络收发 |

重构的核心动作，就是**把尽可能多的代码从集成层挤到纯逻辑层**。

### 1.3 时钟注入模式（已验证）

最典型的手法：凡是直接调 `System.nanoTime()` / `currentTimeMillis()` 的逻辑，都把时钟抽象成可注入接口。

```java
@FunctionalInterface
public interface NanoClock { long nanoTime(); }
// 生产：new BandwidthLimiter(System::nanoTime)
// 测试：new BandwidthLimiter(fakeClock)  // 手动推进时间，断言完全确定
```

---

## 2. Phase 1 — 建立安全网（进行中，已交付首个成果）

### 2.1 已完成 ✅

**抽取 `BandwidthLimiter`（带宽令牌桶）**

- 新增 `common/BandwidthLimiter.java`：把 `PlayerRequestState` 中 `refill` / `canSend` / `recordSend` / `primeSendCredit` / `effectiveLimit` / `sendBurstCap` 抽成独立纯逻辑类，时钟可注入。
- 新增 `src/test/java/.../BandwidthLimiterTest.java`：**9 个测试全部通过**，覆盖冷启动无信用、按时间比例补充、burst cap = limit/4 截断、发送扣减不为负、最小刷新间隔、desiredBandwidth 封顶、0/负值视为无限、primed credit 128 KiB 硬上限等行为契约。
- `build.gradle`：新增 test sourceSet + JUnit 5.10.2 依赖 + `useJUnitPlatform()`。本机 `./gradlew test` 即可运行。

> 这些断言是从原 `PlayerRequestState` 语义反推出的"行为契约"。后续把 `PlayerRequestState` 改为委托 `BandwidthLimiter` 时，任何一条被打破都会立刻暴露。

### 2.2 待办：继续为其它纯逻辑补测试

在**不改动**原实现的前提下，先给下列逻辑补齐特征测试（characterization tests），锁住当前行为：

- `PositionUtil`：pack/unpack 往返、切比雪夫距离、越界判定（快速、必做）。
- `LodRequestManager` 的 `generateOffsetsForDistance` / `appendChebyshevRingStatic`：offset 环的顺序与去重（当前是 static 方法，可直接测）。
- `PlayerRequestState` 的 region 窗口滑动：`resetPreloadRegions` / `updatePreloadRegions` 的进入/退出矩形计算、`preloadRegionComparator` 排序。
- `PlayerRequestState` 的发送队列排序：`sendOrderComparator`（距离优先 + 序号次之）。
- `RegionPresenceC2SPayload` 编解码往返（协议数据，回归价值高）。

**退出标准**：上述纯逻辑均有测试，`./gradlew test` 全绿。

---

## 3. Phase 2 — 抽纯逻辑，瘦身状态类

### 3.1 `PlayerRequestState` 拆分

当前 760 行、职责糅杂。拆成协作的小类（均可单测）：

| 抽出的类 | 职责 | 来源 |
| --- | --- | --- |
| `BandwidthLimiter` ✅ | 令牌桶限速 | 已完成 |
| `SendQueue` | 优先/普通双队列、字节记账、距离重排、trim | `enqueue` / `prepareSendOrder` / `reorder*` / `poll*` |
| `PreloadRegionWindow` | region 窗口滑动、进入/退出矩形、covered 过期 | `resetPreloadRegions` / `updatePreloadRegions` / `expireCoveredPreloadRegions` |
| `PreloadColumnFrontier` | 预加载列的环形 frontier 推进 | `pollFrontierPreloadColumn` / `pollBestPreloadColumnWithinRing` |
| `ClientKnownIndex` | 每维度客户端已知列时间戳 | `clientKnownColumns` 相关方法 |

`PlayerRequestState` 退化为**组合这些组件的门面**，只保留请求 id ↔ 位置映射等薄状态。

### 3.2 消除魔法数字

把散落的常量收进带文档的配置常量类（或直接进 `VSSServerConfig` 的静态区），例如 `PRELOAD_COLUMNS_PER_REGION`、`SCAN_BOOST_TICKS`、`FAST_MOVE_KEEP_RADIUS_CHUNKS` 等，注明单位与调优含义。

**退出标准**：`PlayerRequestState` < 250 行；每个抽出类有单测；行为契约测试仍全绿。

---

## 4. Phase 3 — 拆解服务端神类

### 4.1 引入实例化服务

把 `VSSServerNetworking` 的静态状态收敛为一个 `VssServerRuntime` 实例（在 `ServerStartingEvent` 创建，`ServerStoppingEvent` 销毁）。静态入口只做转发。

```
VssServerRuntime
 ├── PlayerRegistry            // 取代 static PLAYER_STATES
 ├── DiskExecutors             // 封装读/写线程池 + pending 计数
 ├── ColumnQueryPipeline       // 五级查询链（见 4.3）
 ├── PreloadScheduler          // scanPreloadRegions / flushPreloadColumns
 ├── DiagnosticsCounters       // 15 个 AtomicLong 收拢于此
 └── LifecycleGuard            // 封装 serverStopping + epoch（见 4.2）
```

好处：生命周期由对象创建/销毁天然管理，`serverStopping` 布尔量和手动 `clear()` 大幅减少；可在测试中构造 runtime 注入假 executor。

### 4.2 收敛并发防护样板

把重复的 `serverStopping || epoch 不匹配` 判断封进 `LifecycleGuard`，把 pending 计数的增减封进 `DiskExecutors.submit(...)`（自动 try/finally 递减，杜绝泄漏）：

```java
diskExecutors.submitRead(guard, () -> { /* 只写业务逻辑 */ });
// submitRead 内部负责：pending++、epoch 校验、finally pending--、RejectedExecution 处理
```

这一步直接消灭坏味道 #3。

### 4.3 提炼查询链

`handleBatchRequest` 中"缓存 → 时间戳比较 → 已加载区块 → 磁盘读 → 生成"的巨型 for 循环，提炼为 `ColumnQueryPipeline`，每级是一个策略步骤，返回 `ColumnResolution`（命中/需异步/最新/拒绝）。`finishDiskRead` 的 14 个参数收进一个 `DiskReadContext` 值对象。

**退出标准**：`VSSServerNetworking` 只剩事件注册 + 向 runtime 转发；`handleBatchRequest` 主体 < 40 行；查询链各步骤可用假 runtime 单测。

---

## 5. Phase 4 — 拆解客户端状态机

`LodRequestManager` 的五套并行状态各自独立成组件：

| 组件 | 职责 |
| --- | --- |
| `ScanCursor` | 环形扫描位置、boost、软 frontier 半径 |
| `InFlightTracker` | 请求 id ↔ 位置、超时 deadline 队列、重试退避 |
| `PresenceReporter` | region presence 摘要构建、增量重发、去重 |
| `DirtyRefreshBudget` / `GenerationBudget` | 两个速率预算 |
| `DeferredColumns` | 延迟候选队列 |

`LodRequestManager` 退化为在 `tick()` 中编排这些组件。字段数从约 40 降到个位数。

**退出标准**：`LodRequestManager` < 400 行；扫描顺序、退避、presence 去重均有单测。

---

## 6. 协议演进（获授权）

借"可接受协议演进"的授权，顺手清理协议层历史包袱（须 bump `PROTOCOL_VERSION`，客户端服务端同步发版）：

- 统一 payload 的编解码约定（当前各 payload 手写 `encode`/`decode`，可用共享的读写辅助 + 边界常量校验）。
- 明确各 payload 的大小上限校验位置（目前 `VSSConstants` 里的 `MAX_*` 分散使用）。
- 为 presence / batch 等高频包补编解码往返测试，作为协议回归基线。

> 注意：`.vcl` 持久化缓存格式与网络协议是两套版本，协议 bump 不应破坏已有磁盘缓存的读取（README 已说明历史 zstd 缓存的兼容要求）。

---

## 7. 风险与验证

### 7.1 沙箱环境限制（透明说明）

本次评估在隔离沙箱中进行，**无法运行 ForgeGradle 全量构建**（需联网反编译 MC + JDK17 + 3G 堆 + reobf 流程）。因此：

- ✅ **能验证**：纯逻辑层的编译与单测（已用独立 JDK17 + JUnit 实跑，`BandwidthLimiter` 9 测试全绿）。
- ⚠️ **需你在本机验证**：`./gradlew build` 完整编译、`./gradlew test` 全套测试、以及客户端-服务端联机行为。

每个 phase 落地后，请在本机跑一次 `./gradlew test` 和一次实际联机冒烟。

### 7.2 逐步替换而非重写

改造 `PlayerRequestState` 等类时，采用**委托而非删除**：先让老方法转调新组件，测试确认行为一致后，再逐步把调用方迁移到新组件。避免大爆炸式替换。

### 7.3 每步的回归门槛

- 纯逻辑测试全绿（CI 可跑）。
- 关键路径行为契约测试不被打破。
- 本机联机冒烟：进服冷启动补 LOD、移动时环形补齐、方块变更后脏列刷新、切维度重置——四个场景手动确认。

---

## 8. 建议推进节奏

| Phase | 内容 | 相对工作量 | 风险 |
| --- | --- | ---: | --- |
| 1 | 安全网：纯逻辑测试（首个已交付） | 中 | 低 |
| 2 | 抽纯逻辑，瘦身 `PlayerRequestState` + 去魔法数字 | 中 | 低 |
| 3 | 拆服务端神类 + 收敛并发样板 | 大 | 中 |
| 4 | 拆客户端状态机 | 大 | 中 |
| 协议 | 协议层清理（穿插于 3/4） | 小-中 | 中（需同步发版） |

建议**严格按序**推进，1→2 风险低、收益立现，可先做完建立信心；3、4 是重头戏，务必在 1、2 的测试网到位后再动。
