/**
 * <p><b>职责：</b>列内存缓存、持久化区域文件、区块节序列化与异步写入入口。
 * <p><b>依赖：</b>PersistentColumnWriter 注入 lifecycle/runtime；存储实现不通过服务端门面发现依赖。
 * <p><b>线程：</b>文件工作由磁盘 runtime 执行；从真实区块采集先遵守世界线程，再交付编码快照。
 * <p><b>生命周期：</b>停止/换世界关闭句柄并防止旧写入回流；ColumnLodCache 同步发布不可变归属视图，失效/淘汰同时撤销；不完整列、缓存身份、Windows 句柄和失败恢复按存储契约处理。
 */
package dev.xantha.vss.networking.server.storage;
