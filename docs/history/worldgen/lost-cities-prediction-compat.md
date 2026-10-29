# Lost Cities prediction preview

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

VSS cannot replay Lost Cities' `lostcities:lostcity` feature inside its bounded
prediction world. That feature runs on a `WorldGenRegion` and changes actual
chunks during decoration. Instead, when both mods are installed, VSS requests
bounded 8x8-chunk planning summaries from the server and draws lightweight
building silhouettes in fine prediction tiles (sample spacing at most 16 blocks).
The server limits requests to 4096 blocks from the player and runs one planning
worker. Real chunks and Voxy data replace the preview as they arrive.

The preview is deliberately approximate. It uses the planned footprint, ground
height, and floor count, but does not reproduce building templates, materials,
damage, streets, highways, spheres, or city terrain flattening. Some buildings
can be buried by predicted terrain; structure exclusions that depend on the
generation region may also differ until real chunks exist. The preview is
controlled by VSS's prediction structures setting.

The API adapter was checked against Lost Cities 8.4.4 for NeoForge 1.21.1.
Client and server need the matching VSS protocol build. Actual-world visual
validation with Lost Cities remains outstanding.
