# Section 归属查询 API 1（2026-10-22）

本接口对应 PYF 的 `VSS-section-ownership-API.md` 请求。Forge 1.20.1 与 NeoForge 1.21.1 提供同名契约，可查询 VSS 当前保留的列结果、section 归属和缓存失效版本。不改变网络协议、过滤行为、Voxy 摄入或客户端绘制流程。

## 入口和调用示例

```java
import dev.xantha.vss.api.column.ColumnCoverage;
import dev.xantha.vss.api.column.ColumnCoverageApi;
import net.minecraft.world.level.ChunkPos;

var api = ColumnCoverageApi.get();
if (api.isEmpty()) {
    // 接口尚未绑定、服务端已停止，或在远程多人客户端查询。
    // 缺少接口不代表允许覆盖已有的 VSS / Voxy 数据。
    return;
}

ChunkPos pos = new ChunkPos(chunkX, chunkZ);
ColumnCoverage coverage = api.get();
ColumnCoverage.Snapshot view = coverage.snapshot(dimension, pos);
ColumnCoverage.State state = view.section(sectionY); // sectionY = floorDiv(blockY, 16)
boolean authoritative = view.authoritative();
long revision = view.revision();

// 原提案的三个独立方法同样可用：
state = coverage.section(dimension, pos, sectionY);
authoritative = coverage.authoritative(dimension, pos);
revision = coverage.revision(dimension, pos);
```

`snapshot()` 一次读取同一条不可变记录，避免把不同发布批次的三个答案拼在一起。可以在服务端主线程和 worker 查询：查询做常数次哈希查找，不取列缓存的 LRU 写锁，不访问 `Level`、不加载 chunk、不解析 NBT、不重新执行过滤、不压缩、不读磁盘、不等待 Voxy。有效参数和 null 输入的查询均有保守返回，null 返回 `UNKNOWN`。查询未知坐标不会创建记录。

`ColumnCoverageApi.version()==1`。可用 `ColumnFilterApi.supports("column-coverage-query")` 探测编译能力，但应以 `ColumnCoverageApi.get()` 判断当前服务端是否有绑定。能力探测不冻结过滤注册；列过滤 API 仍为 2。JAR Manifest 增加 `VSS-Column-Coverage-API: 1`。

## 状态含义

| 状态 | 当前实现何时返回 | 其他模组如何处理 |
| --- | --- | --- |
| `VSS_OWNED` | 当前有效结果明确发送该 section，包括 `UPDATE` 和真实空气 `CLEAR`；完整未过滤列的全部 section 也属此状态 | 避让 VSS，不另写方块、方块实体或光照 |
| `VSS_UNTOUCHED` | 当前过滤结果没有发送这个 section | 不能假设为空，不能获得写权限；客户端可能仍保存以前的数据 |
| `UNKNOWN` | 尚无结果、生成/读取中、失效、淘汰、接口停止、内存列缓存关闭、VSS 禁用，或参数无效 | 保守避让，不能当成无人管理 |
| `UNMANAGED` | 为未来明确授权保留；本版不主动返回 | 仅未来明确提供无管理契约时使用，调用方仍要处理状态变化 |

`authoritative=true` 只来自**完整、未经过滤的真实来源列**，不依赖客户端预测、worldgen profile 是否可用。任何过滤结果，包括全 `UPDATE` 或全 `UNTOUCHED`，都返回 false；false 不能推导出可写，仍须查 section 状态。完整列省略的确定空气 section 也不能留给其他来源覆盖。

本版刻意不把“超出某个玩家的 LOD 范围”视为 `UNMANAGED`：接口没有玩家参数，其他玩家仍可能需要同一列。也不把“VSS 禁用”视为 `UNMANAGED`，因为禁用无法撤销客户端已收到的数据和在途任务。安全地授予写权限需要比只读查询更强的来源协调。

## 数据从哪里来

唯一所有者仍是既有 `ColumnLodCache`。实时生成、磁盘 NBT、持久缓存恢复和预加载在原有有效性检查后进入这个缓存时，同步发布小型不可变归属快照；缓存命中不重新跑过滤器。过滤结果已有的 section 清单足以区分本轮显式更新/清空与未触碰，无需重新计算 `ColumnDecision`。

归属索引跟随列缓存的条目和字节预算淘汰，没有独立的无限增长世界表。脏列失效、普通 LRU 淘汰、预加载淘汰和 clear 同时撤销查询记录。缓存关闭、拒收过大列、尚未恢复的磁盘数据、只完成客户端本地导入的 chunky 结果，都可能返回 `UNKNOWN`；这代表缺少服务端当前结果证据，不代表 VSS 永远不会再写。

## revision 与生命周期

- 正数是**当前 JVM 内的发布版本标记**，不是原始列时间戳，不是 Minecraft tick，不是磁盘 schema，也不是协议版本。
- 每次接受并发布列结果都会得到更大的标记；包括相同来源时间戳下的数据/光照变化、重新生成和从磁盘重新发布。重新 put 相同内容也允许保守增加版本。
- 纯查询、内存缓存命中及预加载条目晋升不会增加版本。旧来源时间戳被既有缓存拒绝时，也不改变当前版本。
- 失效/淘汰后变成 `revision=0` 和 `UNKNOWN`；再次发布分配更大的正数，不复用先前的正数。重启服务端但不退出 JVM 也不重用正数。
- 过滤策略改变仍须增加 provider 注册版本并重启；新运行时清除旧视图，恢复数据时分配新标记。不要把 revision 跨 JVM 持久化。
- API 只在服务端启动期间绑定，停止后 `get()` 为空。已经保存的旧接口对象在停止及下一次启动后都返回 `UNKNOWN`，不能穿透到新存档。

调用方缓存必须包含 `(dimension, chunk)`，每次使用前校验**正数且相同**的 revision。零不是可缓存的稳定数据版本。更方便的做法是缓存不可变 `Snapshot`，操作前重新读取并比较版本。

## 不提供的保证

这是**服务端当前提交结果的归属信息**，不声称“客户端 Voxy 的最后一次写入来自 VSS”“Voxy 已经落盘”或“GPU 已可绘制”。服务端不知道每位客户端之后是否被原版区块、PYF 或其他生产者再次写入；没有接入客户端写入回执，也没有改变来源优先级。

读 revision → 写其他来源 → 再读 revision 只能发现部分并发变化，不能撤销已经送出的写入，不能形成原子互斥。`VSS_OWNED`、`VSS_UNTOUCHED`、`UNKNOWN` 都不授予写权限；若 PYF 的目标是自动填入 VSS 未发送的空缺，需要另行实现明确的来源仲裁/写入租约，不能用本接口的 `UNTOUCHED` 或零版本代替。

本版没有添加可选 listener。调用方在真正使用结果前查询即可；避免引入工作线程回调、重入和异常处理的新生命周期。`ColumnCoverageApi.bind/unbind` 只供 VSS 运行时绑定，扩展模组不应调用它们。
