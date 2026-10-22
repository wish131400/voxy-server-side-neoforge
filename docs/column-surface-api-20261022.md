# PYF 后续兼容接口：每列高度与能力发现（2026-10-22）

本轮扩展既有 `api.column`，不新增第二套筛选或摄入状态机。Forge 1.20.1 与 NeoForge 1.21.1 使用相同接口，公开 API 版本为 **2**，网络协议继续为 **53**，模组版本继续为 0.3.5。开发包构建标识为 `column-surface-api2-20261022-p53`，不是已发布的 release 版本。

## 每列高度

```java
boolean available = column.hasTopSolidY();
int[] heights = column.topSolidY();       // 防御性副本，固定 256 项
int top = column.topSolidY(localX, localZ); // 不分配数组，X/Z 为 0..15
```

`heights[(localZ << 4) | localX]` 是对应水平位置的最高**非空气**绝对方块 Y。保留 `topSolidY` 以匹配请求，但其含义不是实心地面：树叶、植物、流体都计入。

| 状态 | `hasTopSolidY()` | 数组值 |
| --- | --- | --- |
| VSS 完整源列，位置有方块 | `true` | 该位置真实最高非空气 Y |
| VSS 完整源列，该位置全空气 | `true` | `Integer.MIN_VALUE` |
| 通过旧构造器创建／新构造器传 null | `false` | 访问器返回 256 个 `Integer.MIN_VALUE` |

先判断可用性，再解释哨兵值。数组按整列完整性提供，不混用“部分未知”和“已知空气”。新构造器检查长度及高度范围，在传入时复制；返回数组也复制。禁止在逐 section 循环里反复调用数组访问器，可在循环外取一次，或用单点访问器。旧六参数构造器与所有旧访问器保留。

## 计算来源与成本

列过滤流水线从已经解码的脱离世界的 `LevelChunkSection` 读取方块。每个水平位置从 local Y=15 向下查到首个非空气块，一次遍历同时累计 `Section.maxBlockY` 和整列 256 个最大值；来源 section 顺序不影响结果。空 section 直接跳过；每个非空 section 最多查询 4096 个方块。

实时区块、独立快照、ChunkAccess 和磁盘 NBT 共用此入口。不读取活世界或 `Heightmaps.WORLD_SURFACE`，所以未初始化／缺失／过期的高度图不会污染元数据。解析异常继续拒绝本列，不编造地表值。

未注册过滤器或源数据不完整时仍直接绕过；缓存命中复用已过滤字节，不重复扫描或回调。只在过滤器输入中增加元数据，不改变网络 payload、磁盘格式或策略缓存身份。相比旧版只查一个 section 最高点，非空 section 通常需要更多查询；本轮没有真实服 TPS 或带宽对照，不能宣称零开销。

## 按水平位置选择高度带的示例

下面是外部模组自己的可选策略，VSS 不默认启用。它修正“山地全列最高点裁掉邻近低地”的问题，同时显式发送已确认空气以清除旧内容。

```java
import dev.xantha.vss.api.column.ColumnDecision;
import dev.xantha.vss.api.column.ColumnFilterApi;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.api.column.SectionDecision;
import java.util.HashMap;

if (!ColumnFilterApi.supports("column-top-solid-y")) {
    // 此处由接入方回退原来的全量发送，或不注册该可选适配器。
    return;
}
// 版本仅作示例。由 maxBlockY 规则改为每列规则时，必须升级注册版本并重启。
ColumnFilters.register("player_y_allocator:surface_band", 32051, column -> {
    if (!column.hasTopSolidY()) return ColumnDecision.updateAll();
    final int depth = 32;
    int[] heights = column.topSolidY();
    var updates = new HashMap<Integer, SectionDecision>();
    for (var section : column.sections()) {
        boolean keep = section.onlyAir(); // 真空气也更新，避免保留已拆除的方块。
        long bottom = (long) section.sectionY() * 16;
        long top = bottom + 15;
        for (int surface : heights) {
            if (surface == Integer.MIN_VALUE) continue; // 已知该 X/Z 为空。
            if (surface >= bottom && (long) surface - depth <= top) {
                keep = true;
                break;
            }
        }
        if (keep) updates.put(section.sectionY(), SectionDecision.UPDATE);
    }
    return new ColumnDecision(SectionDecision.UNTOUCHED, updates);
});
```

这是**全玩家共享的源数据策略**，不能偷偷读取玩家 Y／距离再写入共享缓存。按玩家变化的垂直分配和 delta 仍由 PYF 管理。单列最多 64 个被更新 section、signed-byte section Y 的限制保持不变。

单张最高点高度图不能表达同一 X/Z 上多层可见表面。悬空平台、高架桥、树冠、下界顶层可能让其下方的地形落在带外；这些场景仍需接入方保守回退或限制策略适用范围。元数据可精确，不代表裁剪策略普遍安全。

## API 发现与注册窗口

`ColumnFilterApi.version()` 返回 `2`；`capabilities()` 返回不可修改的名称集合；`supports(String)` 对未知值或 null 返回 false。提供：

- `section-decisions`：UPDATE／CLEAR／UNTOUCHED。
- `section-light-modes`：KEEP／SKY_ONLY／NONE。
- `section-max-block-y`：每段最高非空气绝对 Y。
- `column-top-solid-y`：每个水平位置最高非空气绝对 Y。

只做探测不会加载客户端、Voxy、世界或缓存，也不会冻结注册。`ColumnFilters.isRegistrationFrozen()` 也只读状态；它不是注册预约，调用 `register` 时仍可能明确拒绝晚注册。

可选依赖应先通过加载器确认 VSS 存在，再在隔离的适配器入口探测。对不含发现类的旧版本，可用反射避免提前链接：

```java
boolean supported;
try {
    Class<?> api = Class.forName("dev.xantha.vss.api.column.ColumnFilterApi", false, getClass().getClassLoader());
    int version = (int) api.getMethod("version").invoke(null);
    supported = version >= 2 && (boolean) api.getMethod("supports", String.class)
            .invoke(null, "column-top-solid-y");
} catch (ReflectiveOperationException | LinkageError unavailable) {
    supported = false; // 关闭此可选适配器，保留原全量路径。
}
```

探测之后才加载直接引用新 API 的适配器类。不要仅比较编译期内联的 `VERSION`；不要为探测而调用 `snapshot()`。正常模组初始化／`FMLCommonSetupEvent` 可注册，必须早于任何首次列处理、缓存使用或登录身份快照。首次 snapshot 后冻结持续到进程退出，reload／重进世界不重置。

## 兼容与构建标记

保留原六参数 `ColumnInput`、四／五参数 `Section` 构造器和旧访问器。旧 `ColumnInput` 创建的高度图未知；新 record 分量会影响反射、equals/hashCode/toString，不能依赖这些细节跨版本序列化或生成缓存 key。API 版本、网络协议、provider 注册版本互不代替。

JAR Manifest：

```text
VSS-Protocol: 53
VSS-Build-Id: column-surface-api2-20261022-p53
VSS-Column-Filter-API: 2
```

协议 53 与 release 50／旧 dev 52 不兼容，两端需同协议；API 元数据扩展本身不再升协议。provider 改用新规则须升自己的注册版本；既有缓存身份算法仍使用 `vss-column-filter-api-1`，因此同规则不会仅因安装 API 2 而强制失效。API 2 不承诺所有未来字段形态不变；后续不兼容改变应升级 API 并明确迁移。

## 来源协调保持原契约

`coverage()`／`authoritative()` 只说明本次交付来源，不能证明永久所有权、整列完整、摄入完成或 GPU 可绘制。过滤更新仍为 `completeColumn=false`，不清理缺席高度、不登记完整列 presence，也不推进完整列就绪屏障。

本 API 不声称提供 `source-priority`。Voxy 同坐标摄入是替换；VSS 已写入后，PYF 的占位空气仍可能从其他路径覆盖。PYF 在缺少摄入协调时应保留现有全量回退。后续若叠加裁剪收益，需要在原版／PYF 到 Voxy 的入口区分“原世界真空气”和“仅为传输裁剪产生的占位空气”，并处理维度、会话、数据版本与失败撤销；只按距离或单个“已覆盖”布尔值跳过不足以安全交接。

Tectonic `ConfigNoise` 配置快照失败属于独立的预测兼容问题，本接口不修改它；真实列同步继续按既有异常边界运行。过滤专用带宽统计与真实模组组合长跑也不属于已完成的 API 验证。

本轮测试数量、旧二进制链接与最终产物校验见 [验证记录](column-surface-api-validation-20261022.md)。
