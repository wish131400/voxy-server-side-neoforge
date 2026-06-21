# Voxy Server Side Forge

Forge 1.20.1 port of Voxy Server Side. It synchronizes server-side chunk-column LOD data to Voxy clients so players can fill distant terrain from the server instead of relying only on local exploration.

## Features

- Server-to-client Voxy LOD column synchronization.
- Per-player bandwidth cap and send queue limits.
- Optional missing-LOD chunk generation with global and per-player throttles.
- Background LOD packing through the shared `VSS-LOD-Packer` worker pool.
- Dirty column refresh for block changes, chunk dirty flags, and explosions.
- Bounded dirty-version cache so old client LODs can be refreshed later without building an unbounded send queue.
- Optional far-player rendering and nametags outside vanilla entity tracking distance.
- Voxy/Embeddium options integration and Chinese localization.
- Compatibility hooks for FTB Chunks force-load pressure and several known mod interaction issues.

## Requirements

- Minecraft `1.20.1`
- Forge `47.x`
- Java 17
- Voxy on clients
- Embeddium/Sodium-compatible option API is used only as a compile-time optional integration.

## Building

```bash
./gradlew build
```

On Windows:

```cmd
gradlew.bat build
```

The re-obfuscated jar is produced in `build/libs/`.

## Installation

Install the built jar on the Forge server and on clients that should receive server LODs. Clients also need Voxy installed. Players without the matching VSS protocol simply will not use the sync path.

## Configuration

The server config is generated at:

```text
config/vss-server-config.json
```

Important defaults:

| Option | Default | Meaning |
| --- | ---: | --- |
| `lodDistanceChunks` | `256` | Server-side maximum LOD sync distance in chunks. |
| `bytesPerSecondLimitPerPlayer` | `3145728` | Per-player server send cap, 3 MiB/s by default. |
| `sendQueueBytesLimitPerPlayer` | `33554432` | Per-player queued payload memory cap. |
| `enableChunkGeneration` | `true` | Allows VSS to generate missing chunks for LOD data. |
| `generationConcurrencyLimitPerPlayer` | `8` | Missing chunk generation concurrency per player. |
| `generationConcurrencyLimitGlobal` | `128` | Missing chunk generation concurrency for the whole server. |
| `generationPackingThreads` | `2` | Worker threads used for LOD packing. |
| `generationPackingQueueLimit` | `64` | Pending background packing jobs. |
| `dirtyBroadcastIntervalSeconds` | `2` | How often dirty columns are broadcast to online clients. |
| `dirtyVersionCacheEnabled` | `true` | Keeps a bounded record of changed LOD columns. |
| `dirtyVersionCacheMaxEntries` | `200000` | Maximum remembered dirty columns. |
| `dirtyVersionCacheRetentionSeconds` | `86400` | Dirty version retention time, 24 hours by default. |
| `farPlayerSyncEnabled` | `true` | Enables distant player sync/rendering. |

The dirty-version cache is not a send queue. It stores one timestamp per changed chunk column, deduplicated and capped, so players returning later can request fresh LOD data without the server stockpiling payloads for absent players.

## Commands

All commands require permission level 2.

```text
/vss stats
/vss bandwidth get
/vss bandwidth set <bytes_per_second>
/vss bandwidth set_mib <mib_per_second>
/vss queue get
/vss queue set_count <columns>
/vss queue set_mib <mib>
/vss distance get
/vss distance set <chunks>
/vss farplayers get
/vss farplayers enable
/vss farplayers disable
/vss farplayers set_interval <ticks>
/vss generation get
/vss generation stats
/vss generation enable
/vss generation disable
/vss generation set_player_concurrency <limit>
/vss generation set_global_concurrency <limit>
/vss generation set_starts_per_tick <limit>
/vss generation set_completions_per_tick <limit>
/vss generation set_packing_threads <threads>
/vss generation set_packing_queue <limit>
/vss generation set_timeout <seconds>
```

Chinese aliases are also registered, for example `/vss 状态`, `/vss 距离 设置 <区块>`, `/vss 生成 状态`, and `/vss 远处玩家 开启`.

## Notes

This project intentionally tracks only the Forge port sources under `dev.xantha.vss` plus the resources required to build the mod. Local decompiled sources, runtime logs, temporary crash-analysis folders, and built jars are ignored.

## License

GPL-3.0. See `LICENSE`.
