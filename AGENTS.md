# 打包约定

- 用户要求：后续每次打包都同时提供 NeoForge 1.21.1 和 Forge 1.20.1 两个版本。配套 Forge 项目位于 `../voxyserverside`。
- JAR 使用 Gradle 生成的标准文件名，例如 `vss-0.3.6-neoforge-1.21.1.jar` 和 `vss-0.3.6-forge-1.20.1.jar`。不要追加 `perf`、`fix`、日期或其他自定义后缀。
- 两版本合包使用标准版本名，例如 `vss-0.3.6.zip`；构建标识放在清单、说明或目录中。
- 每次交付打包必须更新本项目 `lib` 根目录的标准 NeoForge JAR、配套 Forge 项目 `lib` 根目录的标准 Forge JAR，并将相同的标准版本 ZIP 同步到两个项目的 `lib` 根目录。构建子目录只用于留档，不能代替根目录更新。
- 发布前核对两个主项目生产源码与通过验证的打包快照一致，并检查最终 JAR 的构建标识、关键修复及校验值。可使用 `tools/verification/publish_release.py` 完成检查、备份和根目录发布。

# 文档约定

- MCP 用于内部调试与性能审查，不在面向用户的 README、Markdown 使用文档或发布说明中介绍，也不提供用户接入配置和 AI 调试教程。
