# Voxy Server Side NeoForge

NeoForge 1.21.1 server-side LOD sync mod for Voxy. The server sends existing, cached, or server-generated LOD columns to clients with Voxy installed, so distant terrain can fill from the player outward instead of relying on each client to generate it locally.

## Status

- Minecraft: `1.21.1`
- Loader: NeoForge `21.1.x`
- Current mod version: `0.2.5-neoforge-1.21.1`
- VSS protocol: `30`
- Client renderer target: Voxy with Sodium-compatible rendering

Client and server VSS protocol versions must match. If they do not match, the player can still join the server, but VSS LOD sync is disabled for that session.

## Install

Server:

- NeoForge `21.1.x`
- This mod
- Recommended: ZstdNet or another bundled provider of `com.github.luben.zstd.Zstd`

Client:

- NeoForge `21.1.x`
- This mod
- Voxy
- Sodium-compatible renderer stack
- Recommended: ZstdNet

Zstd capability is negotiated by VSS during handshake. This is mostly a clear error path for missing or mismatched packs, because normal modpack usage should install the same zstd support on both sides.

## Sync Model

The client requests columns in a Chebyshev ring frontier around the player:

```text
ring = max(abs(dx), abs(dz))
```

That keeps scan order stable, cheap, and visually centered. Already requested or in-flight columns are deduplicated, and low-priority old work can be dropped when it is no longer relevant to the player's current position.

Server-side lookup order:

```text
memory cache -> persistent .vcl cache -> loaded chunk snapshot -> optional chunk NBT -> optional chunk generation
```

Changed columns inside already visited areas are refreshed with priority. Complete-column validation prevents partial LOD data from permanently overwriting missing client sections.

If `enableChunkGeneration=false`, VSS still serves columns that already exist in memory cache, persistent `.vcl` cache, loaded chunk snapshots, or optional chunk NBT. It just stops creating missing world chunks for new LOD.

## Persistence And Compression

Complete LOD columns are persisted per world:

```text
<world>/data/vss-column-cache/<dimension>/<region>/<chunkX>_<chunkZ>.vcl
```

For example:

```text
saves/New World/data/vss-column-cache/minecraft_overworld/0_0/12_8.vcl
```

Each `.vcl` file has a small VSS header, followed by a zstd frame body. The same encoded zstd body is reused for:

- disk persistence
- server memory column cache
- normal network payloads

This avoids keeping raw section bytes in the hot cache and avoids recompressing hot LOD columns before sending. The client still decompresses received columns before handing them to Voxy.

## Default Config

Current defaults are tuned for low bandwidth and moderate memory use:

| Item | Default |
| --- | ---: |
| Server LOD distance | `128` chunks |
| Per-player bandwidth | `500 Kbps` |
| Per-player send queue | `256` columns / `4 MiB` |
| Existing LOD request rate | `16` columns/s |
| Existing LOD request concurrency | `16` |
| Missing LOD generation rate | `8` columns/s |
| Per-player generation concurrency | `4` |
| Global generation concurrency | `32` |
| Generation starts per tick | `2` |
| Generation completions per tick | `4` |
| Disk reader threads | `4` |
| Disk read timeout | `1500 ms` |
| Memory column cache | `4096` columns / `32 MiB` |
| Persistent column cache | `512 MiB` / `250000` columns |
| Persistent/network column compression | zstd when available |
| Dirty refresh interval | `10` ticks |
| Far player sync | enabled, `10` ticks |

Old MiB/s-style bandwidth config is migrated to Kbps on load. Display text uses Kbps below `1000`, and Mbps at or above `1000`.

## Config Files

Server:

```text
config/vss-server-config.json
```

Client:

```text
config/vss-client-config.json
```

Important server options:

| Key | Meaning |
| --- | --- |
| `enabled` | Master switch for VSS server sync. |
| `lodDistanceChunks` | Maximum server-side LOD sync radius. |
| `bytesPerSecondLimitPerPlayer` | Effective per-player byte limit. Commands and UI expose this as Kbps/Mbps. |
| `sendQueueLimitPerPlayer` / `sendQueueBytesLimitPerPlayer` | Per-player queued payload limits. |
| `diskReaderThreads` | Parallel workers for persistent cache and optional chunk NBT reads. |
| `syncOnLoadRateLimitPerPlayer` / `syncOnLoadConcurrencyLimitPerPlayer` | Rate and concurrency for already existing LOD. |
| `enableChunkGeneration` | Whether missing LOD may trigger server chunk/world generation. |
| `generationRateLimitPerPlayer` / `generationConcurrencyLimitPerPlayer` / `generationConcurrencyLimitGlobal` | Missing-column generation throttles. |
| `generationStartsPerTickLimit` / `generationCompletionsPerTickLimit` | Main-thread generation pacing. |
| `enableChunkNbtColumnSync` | Whether VSS may pack LOD from existing chunk NBT. |
| `enableColumnCache` / `columnCacheMaxBytes` | Hot in-memory encoded column cache. |
| `enablePersistentColumnCache` / `persistentColumnCacheMaxMiB` | Per-world `.vcl` cache. |
| `enablePersistentColumnCompression` / `enableNetworkColumnCompression` | Compression toggles for persisted and network LOD. |
| `dirtyBroadcastIntervalTicks` | How often changed columns are announced for refresh. |
| `farPlayerSyncEnabled` / `farPlayerSyncIntervalTicks` | Far player position/vehicle sync. |

Important client options:

| Key | Meaning |
| --- | --- |
| `receiveServerLods` | Whether the client receives VSS LOD. |
| `lodDistanceChunks` | Client request distance. `0` means automatic: min(server, Voxy, client hard cap). |
| `desiredBandwidthKbps` | Client-requested bandwidth cap. `0` means use the server limit. |
| `offThreadSectionProcessing` | Process received LOD off the render thread before Voxy ingest. |

## Commands

```text
/vss stats
/vss bandwidth get
/vss bandwidth set_kbps <kbps>
/vss bandwidth set_mbps <mbps>
/vss bandwidth set_bytes <bytes_per_second>
/vss bandwidth set_mib <MiB_per_second>
/vss distance get
/vss distance set <chunks>
/vss queue get
/vss queue set_count <columns>
/vss queue set_mib <MiB>
/vss storage get
/vss storage set_readers <threads>
/vss dirty get
/vss dirty set_interval <ticks>
/vss generation get
/vss generation enable
/vss generation disable
/vss generation stats
/vss far_players get
/vss far_players enable
/vss far_players disable
```

Chinese aliases are also registered. The Chinese bandwidth setter now uses Kbps by default, matching player-facing network settings.

## Integrated Server And LAN

Singleplayer and LAN hosts use the same server-side VSS logic as a dedicated server. The local host routes VSS requests directly to the integrated server player, while LAN visitors use normal networking. This keeps the generation/cache path aligned between dedicated servers, singleplayer, and LAN.

The local host still pays for both halves in one JVM: Minecraft client, integrated server, VSS server logic, Voxy render data, OpenGL/LWJGL native memory, and modpack caches.

## NeoForge Notes

- The 1.21.1 port is aligned with the Forge 1.20.1 0.2.5 behavior for request ordering, bandwidth commands, zstd column reuse, persistent cache, config refresh, and integrated-host routing.
- Client UI integration targets the current Voxy/Sodium-style options path and keeps server-side VSS controls available from the Voxy options screen when supported.
- The port includes guards for client-only classes so dedicated servers do not load `net.minecraft.client.Minecraft`.

## Troubleshooting

Existing cached LOD loads too slowly:

- Increase `/vss bandwidth set_kbps`.
- Increase `syncOnLoadRateLimitPerPlayer`.
- Increase `syncOnLoadConcurrencyLimitPerPlayer`.
- Increase `diskReaderThreads` if persistent cache reads are the bottleneck.

Missing LOD generates too slowly:

- Confirm `enableChunkGeneration=true`.
- Increase `generationRateLimitPerPlayer`.
- Increase `generationConcurrencyLimitPerPlayer` or `generationStartsPerTickLimit`.
- Watch `/vss generation stats`.

Changing distance or generation settings seems delayed:

- Config commands refresh the active VSS session and request state.
- Persistent-cache cold scans are disk-bound; increase `diskReaderThreads` gradually if the disk can keep up.
- The client does not need to restart, but Voxy may still need time to rebuild render data.

Task Manager memory is much higher than Java heap:

- Java heap tools show only heap.
- Task Manager also includes committed heap headroom, metaspace, code cache, GC structures, Voxy/OpenGL/LWJGL native buffers, mapped files, and driver allocations.
- Start with `-XX:NativeMemoryTracking=summary` and inspect with `jcmd <pid> VM.native_memory summary` when native memory needs to be separated.

## Notes

- VSS `.vcl` cache is per world and per dimension.
- A `512 MiB` persistent cache currently stores roughly a `160` chunk radius in average tested terrain, but modded terrain can vary.
- VSS serves already persisted LOD to other players once one player has generated or cached it.

## License

MIT. See `LICENSE`.
