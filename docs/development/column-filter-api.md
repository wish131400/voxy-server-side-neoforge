# 服务端整列过滤接口（API 2，协议 53）

[文档目录](../README.md)

本接口供 PYF 等模组决定 VSS 应交付哪些真实 section。VSS 实现整列回调、字节重建、缓存隔离、网络传输和客户端摄入保护；外部模组只返回决策，不负责压缩、CRC 或 section 清单。没有注册过滤器时保留原来的完整列路径。

入口为 `dev.xantha.vss.api.column.ColumnFilters.register(String id, int version, ColumnFilter filter)`。此包可以在专服加载，不引用客户端、Voxy 或网络实现。`VSSApi` 仍是客户端接口，服务端不要用它注册过滤器。

API 2 新增每个 X/Z 位置的最高非空气高度、公开版本／能力查询和不冻结注册的状态查询。API 版本、网络协议和 provider 注册版本各自独立；API 2 不改变既有过滤缓存身份。PYF 接入、可编译示例与来源协调限制见 [每列高度与后续兼容接口](column-surface-api.md)。

## 注册与示例

在模组初始化阶段注册，早于首次列处理或登录握手。正常的 `FMLCommonSetupEvent` 可用于注册，但任何调用者都不得提前调用 `snapshot()` 探测能力。首次 `ColumnFilters.snapshot()` 冻结注册；之后注册会抛异常。`isRegistrationFrozen()` 只读取状态，不冻结；观察结果不是预约，真正注册仍在锁内检查。冻结在进程生命周期内不重置，reload／重进世界不能重新注册。注册 ID 使用 `modid:name`，版本必须为正整数。重复 ID 被拒绝。

```java
import dev.xantha.vss.api.column.ColumnDecision;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.api.column.SectionDecision;
import java.util.HashMap;

// 仅示范一个固定的、全玩家共享的高度带；实际带宽由接入模组决定。
// 方块 y=48..111 对应 section Y=3..6，范围按整 section 对齐。
ColumnFilters.register("pyf:vss_column_band", 1, column -> {
    var selected = new HashMap<Integer, SectionDecision>();
    for (int sy = column.minSectionY(); sy < column.maxSectionY(); sy++) {
        if (sy >= 3 && sy <= 6) {
            selected.put(sy, new SectionDecision(
                    SectionDecision.Action.UPDATE,
                    SectionDecision.LightMode.SKY_ONLY));
        }
    }
    return new ColumnDecision(SectionDecision.UNTOUCHED, selected);
});
```

`ColumnDecision` 的第一个参数是未列出 section 的默认决策，第二个参数是按 section Y 覆盖的例外表。需要明确清除某段旧内容时用 `SectionDecision.CLEAR`，不要用 `UNTOUCHED` 表示空气。要保留原始数据和两类光照，可用 `SectionDecision.UPDATE`；全部保留可返回 `ColumnDecision.updateAll()`。

## 输入与整列三态

`ColumnInput` 提供维度、区块 X/Z、`[minSectionY, maxSectionY)` 和该范围内连续的 section 快照。区块 X/Z 与 section Y 都以 16 方块为单位，负坐标使用向下取整。`section(sy)` 可以读取：

- `onlyAir()`：真实源 section 是否为空气。完整源中被普通序列化器省略的已确认空气也出现在输入中。
- `maxBlockY()`：该 section 内最高非空气方块的**绝对方块 Y 坐标**，不是 section Y，也不是地表高度；树叶、植物、流体均计入非空气。空 section 返回 `Integer.MIN_VALUE`。VSS 对非空源 section 填入实际值；通过旧四参数构造器手动创建的非空输入，其高度未知，也返回该哨兵值，须结合 `onlyAir()` 区分。
- `hasBlockLight()`、`hasSkyLight()`：是否携带对应光照数组。
- `blockLight()`、`skyLight()`：2048 字节数组或 `null`，返回防御性副本。

输入不暴露 `ServerLevel`、玩家、活区块或可修改的方块容器。光照数组存在只说明有数据，不额外证明 Minecraft 光照引擎已经完成。

API 2 另外提供 `hasTopSolidY()`、`topSolidY()` 和 `topSolidY(localX, localZ)`。数组为 256 个绝对方块 Y，索引 `(z << 4) | x`；名称为兼容 PYF 请求保留，实际计算最高非空气，包含树叶、植物和流体。空水平列为 `Integer.MIN_VALUE`。VSS 的完整源输入始终具备这张表，未知的旧构造输入通过 `hasTopSolidY()==false` 区分；数组访问仍返回 256 个哨兵值。数组在传入与读出时复制，单点访问不分配数组，X/Z 必须位于 `[0, 15]`。

| 决策 | 交付内容 | 对 Voxy 旧数据的影响 |
| --- | --- | --- |
| `UPDATE` | 完整真实源 section；真实源为空气时也显式发送空气 | 替换该 section，不做逐 voxel 合并 |
| `CLEAR` | VSS 构造的完整空气 section | 明确清除该 section 的旧方块 |
| `UNTOUCHED` | 不发送该 section | 本次 VSS 更新不写该 section，也不因其缺席而清除它 |

粒度为整段 16×16×16。v1 不提供 section 内逐方块掩码。全 `UNTOUCHED` 是带策略身份、section 数为 0 的成功空操作，仍完成本次请求。

## 最高非空气高度扩展（2026-10-21）

`ColumnInput.Section` 的五参数构造器为 `(int sectionY, boolean onlyAir, int maxBlockY, byte[] blockLight, byte[] skyLight)`。保留旧四参数构造器 `(int, boolean, byte[], byte[])` 和所有旧访问方法；旧构造器的高度默认为 `Integer.MIN_VALUE`，光照仍防御性复制。旧调用方的构造和访问可继续链接，但 record 分量数、反射结构及自动生成的比较／字符串输出发生变化，不保证依赖这些细节的代码无须调整。调用 `maxBlockY()` 的新扩展须使用包含该字段的 VSS 构建。

高度在 `ColumnFilterPipeline` 已反序列化的脱离世界的 section 内计算；API 2 在各 X/Z 从 local Y=15 向下寻找最高非空气块，一次遍历同时计算 section 最大值和每列高度，不访问活世界或 NBT Heightmaps。实时快照与磁盘 NBT 使用同一入口；未注册过滤器时直接绕过，缓存命中复用已过滤字节，不重复解析或扫描。空 section 跳过，非空 section 最坏需要 4096 次方块查询，实际额外耗时尚须按场景测量；这不是与旧版单个 section 最高点查询等成本的承诺。

以下仅示范“整列最高非空气点以下 32 方块”的近似高度带，**不是保证保留地表的地下剥离算法**。高建筑、树冠和起伏地形可能使较低地面落在带外；需要精确保留地表时，接入方须另行设计规则。

```java
// 外部模组自行注册；VSS 不会默认启用此策略。
ColumnFilters.register("example:highest_band", 2, column -> {
    final int depth = 32;
    int top = Integer.MIN_VALUE;
    for (var section : column.sections()) {
        if (!section.onlyAir() && section.maxBlockY() == Integer.MIN_VALUE) {
            return ColumnDecision.updateAll(); // 非空但高度未知，保守保留。
        }
        top = Math.max(top, section.maxBlockY());
    }
    if (top == Integer.MIN_VALUE) {
        return ColumnDecision.updateAll(); // 已确认全空气，显式更新旧内容。
    }
    long cutoff = (long) top - depth; // 不对 MIN_VALUE 做 int 减法。
    var selected = new HashMap<Integer, SectionDecision>();
    for (var section : column.sections()) {
        if (section.onlyAir() || section.maxBlockY() >= cutoff) {
            selected.put(section.sectionY(), SectionDecision.UPDATE);
        }
    }
    return new ColumnDecision(SectionDecision.UNTOUCHED, selected);
});
```

示例保留截线相等的 section，并显式更新已确认空气；低于截线的非空段为 `UNTOUCHED`，因此不会删除 Voxy 已存在的地下数据。仍受单列 64 个被更新 section 的传输上限约束。只添加高度元数据不改变协议 53、缓存文件格式或过滤缓存身份算法；外部模组改用新字段后，须升级自己的注册版本，避免复用旧规则结果。过滤输出继续保持 `completeColumn=false` 和原 `coverage.filterKey()` 语义。

## 光照选项

| `LightMode` | 方块光 | 天空光 |
| --- | --- | --- |
| `KEEP` | 保留源数据 | 保留源数据 |
| `SKY_ONLY` | 省略 | 保留源数据 |
| `NONE` | 省略 | 省略 |

VSS 不生成原本缺失的光照。`CLEAR` 常量默认 `NONE`，可显式构造 `CLEAR + SKY_ONLY` 等组合。`UNTOUCHED` 不传数据，因此不执行光照写入。省略光照后由 Voxy 的摄入实现处理缺省值，`NONE` 不是“强制全黑”的承诺。

## 线程、顺序与异常

生产流程为：

1. 在安全线程捕获活区块快照，或在磁盘任务读取已完成区块的 NBT。
2. 生成完整的真实源字节与脱离世界的整列输入。
3. 每个注册 provider 对这一整列调用一次，得到默认值和例外表。
4. VSS 重建 section 数量、正文、长度及清单，应用光照选项。
5. 压缩、计算压缩正文 CRC32C，写入内存／持久缓存，分片交付。

通常在列打包或 NBT 工作线程调用，多个列可并发。回调使用序列化调用者的线程；不得依赖一定处于服务端 tick 线程。必须线程安全、非阻塞，不自行读写 Minecraft 可变世界、等待主线程任务、生成地形或操作 GPU。返回的 map 被复制，输入数组也受防御性复制保护。

缓存命中直接复用已过滤结果，不再次调用 provider。不完整源不会被过滤器提升为完整结果，仍按原流程等待／补齐。回调返回 `null`、抛运行时异常或链接错误、指定维度外的 section 时拒绝本列，错误包含 provider ID；不会退回发送全量，也不会把异常当成 `CLEAR`。通常由既有失败／重试流程继续处理。初始化期的非法注册直接抛出异常。

多个 provider 先按 ID 排序再执行。决策保守合并：`UNTOUCHED` 优先，其次 `CLEAR`，最后 `UPDATE`；光照采用最严格的选项。这样一个 provider 不会重新写入另一 provider 已排除的 section。这里的 `CLEAR` 与 `UPDATE` 是显式决策，接入方须确认合并规则符合自己的用途。

## 缓存身份与适用范围

ID、整数版本和缓存身份代号 `vss-column-filter-api-1` 按固定顺序生成 SHA256，作为 `coverage.filterKey()`。该代号描述既有策略缓存算法，并非公开能力版本；API 2 扩展不改变它。改变过滤规则、静态配置或影响结果的外部数据，必须升级注册版本并重启服务端；ID/版本不变的情况下，缓存命中不会自动发现回调行为变化。

- 未过滤缓存仍在存档的 `data/vss-column-cache/`。
- 过滤缓存位于 `data/vss-column-cache-filtered/<filterKey>/`，不会把带外省略写成普通完整列缓存。
- 内存缓存及交付入口校验当前策略 key；客户端按登录握手中的 key 检查列与分片。
- 真实区块变更也失效其他策略命名空间中该坐标的过期文件，防止重启关闭过滤后复活旧全量缓存。
- NeoForge 保持已有文件格式 8／schema 5；Forge 保持已有文件格式 7／schema 4。未过滤旧完整缓存可继续使用。过滤结果仅从隔离命名空间读取，读取后仍为局部更新。

**v1 是按真实源列确定的、全玩家共享的策略。** 输入没有玩家位置。固定高度带、由该列源数据决定的规则可以使用；随玩家高度／距离变化的个性化带不能偷偷读取全局玩家状态后写入共享缓存。那需要另设按请求的 scope 和缓存身份，本版本未实现。也不能让同一 ID/版本的回调根据调用次数或时间改变决策。

本版过滤输出沿用网络清单上限：单列最多 64 个被更新的 section，section Y 在 `[-128, 127]` 内；越界会拒绝列，不能截断后声称完整。第三方不要假设任意超高维度都可以全量展开。

## coverage / authoritative 与同坐标覆盖顺序

所有经过过滤的结果都带非空 `coverage.filterKey()`，且 `completeColumn=false`。即使某次过滤返回全 `UPDATE`，也保留过滤来源，避免与普通完整列混淆。

客户端消费者通过 `VoxelColumnData.coverage()` 读取来源，通过 `authoritative()` 区分普通真实源和经过过滤的更新：

```java
if (data.coverage().isFiltered()) {
    // 仅处理 data.sections()；不能把清单外的高度清成空气。
    // 不能根据本次局部交付宣称整列已完整接管。
}
```

VSS 自己会做到：局部更新不执行 `clearMissingSections`，不登记完整列 presence，不据此撤销整列预测覆盖，也不推进完整列就绪屏障；对将要写入的坐标先撤销旧的完整列声明，摄入失败也不会留下这份旧保证。客户端的策略交付确认只在当前会话用于避免重复请求，不作为全量缓存上报服务端。断线、维度和旧版本结果仍经过既有会话／时间戳检查。

`authoritative()` 表示本条数据来自未过滤真实源，**不单独证明整列完整、Voxy 已完成摄入或 GPU 已可绘制**。整列补空由 `replaceMissingSections` 和原完整列契约决定。

Voxy 的 `rawIngest` 对同一 section 使用替换语义。VSS 先写入后，又经原版区块／PYF 路径写入同一 section 时，后处理的数据仍可能覆盖先前结果；独立队列还会影响实际处理顺序。本接口增加的是可供消费者读取的来源标记，未修改 Voxy 为“VSS 永久优先”。`UNTOUCHED` 只约束本次 VSS 交付，不能拦截其他入口提交的空气。

因此第三方仍须协调自己的 Voxy／原版摄入入口：带外不要再次按“完整空气 section”提交；如果需要同一个 section 内一部分归 PYF、一部分归 VSS，必须在摄入前合并或改造 Voxy，不能依靠这个整段接口实现。关闭本地摄入的现有开关也不是第三方来源白名单。

## 版本与验证

本版网络协议为 **53**。登录身份与列 payload 新增策略 key，与 release 50、旧开发版 52 不兼容。服务端、客户端须同时升级；VSS 主版本字符串相同不能代替协议检查。

构建命令可指定 `-PvssBuildId=column-filter-api1-20261020-p53`。JAR 清单写入 `VSS-Protocol`、`VSS-Build-Id`，并保留 `Implementation-Timestamp`。只做普通本地构建不指定 ID 时标记 `dev`，不会冒充这份交付包。

API 2 构建还写入 `VSS-Column-Filter-API: 2`。通过 `ColumnFilterApi.version()` 和 `supports(String)` 查询运行时能力，不要只依赖会被编译器内联的 `VERSION` 常量。API 2 保留原六参数 `ColumnInput` 构造器、原四／五参数 `Section` 构造器和旧访问器；新增数组会改变 `ColumnInput` 的 record 分量、反射结构、equals/hashCode/toString，禁止把这些细节当作稳定序列化格式或策略缓存 key。新构造器形态为 `(dimension, chunkX, chunkZ, minSectionY, maxSectionY, int[] topSolidY, List<Section> sections)`。

`columnFilterTest` 验证整列回调次数、三态／光照、不可变快照、异常拒绝、编码与清单、分片与混策略拒绝、空操作、缓存隔离／CRC／跨策略失效、握手及局部覆盖契约；按类隔离 JVM。已纳入 `verifyModules`，两平台都可执行：

```powershell
.\gradlew.bat verifyModules --no-daemon --console=plain
.\gradlew.bat jar -PvssBuildId=column-filter-api1-20261020-p53 --no-daemon --console=plain
```

CPU 回归和产物检查不代表 PYF 已完成注册或 Voxy 已实现来源仲裁；实际 PYF + Voxy + VSS 的游戏组合仍需第三方接入后联调。
