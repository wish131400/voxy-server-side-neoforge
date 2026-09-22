# Prediction region storage

Implemented on 2026-09-21 for both loaders. The VPD record schema and zlib codec
are unchanged. New records are stored in `.vpr` region containers; existing `.vpd`
files remain readable. This is a local implementation update, not a new version
number or a GitHub release.

## Layout and publication

The world/dimension/algorithm identity directories remain unchanged. Each type
and detail level has 32x32-coordinate regions, including floor-divided negative
coordinates. A region contains a 16-byte format header, 1,024 fixed 64-byte index
slots, and independently compressed records. Each slot contains its identity,
offset, length, first 36 uncompressed header bytes, status and CRC32. Terrain
precision probes can read the cached index without opening/decompressing each
record. At most 32 region handles/indexes per cache root remain open.

Builders still stream encoding into private temporary files. The existing serial
commit worker appends the completed payload, forces data, writes the checksummed
index slot and forces it before acknowledging success. The queue retains paths,
not decoded tiles. Readers copy compressed bytes under the storage lock, release
the lock and decode outside it. Record allocations are limited to 32 MiB.

An interrupted append leaves an unreferenced tail. A torn or out-of-range index
slot becomes a cache miss; it cannot fall back to an older VPD record. Truncated
region headers fail closed; a subsequent generated write atomically replaces the
damaged region with a tombstoned index before publishing fresh data. Cache data
can be regenerated, so losing a damaged entry is preferable to stale terrain.
Atomic initialization/replacement is required: unsupported atomic rename leaves
the existing file intact and reports a cache write failure.

## Migration and invalidation

Region entries take precedence over legacy files. A successful fully validated
legacy read schedules a bounded background transfer (at most 32 queued keys),
copying its existing compressed bytes. Closed sessions and intervening dirty
notifications cancel stale transfers. No full-directory migration runs on join.
The legacy file is removed only after region publication succeeds. Unvisited
legacy entries remain available, and first reads still pay the old file cost.

Dirty invalidation immediately cancels leases and clears precision hints, then
persists a tombstone before deleting a legacy file. Tombstones survive restart
and compaction. Missing keys without either form of storage do not create empty
regions. New predictions can replace tombstones normally. Existing sampling,
cache identity, colors and decoration compatibility rules remain unchanged.

## Space and lifecycle

Replacements append, producing reclaimable old payloads. A region is considered
for compaction after at least 4 MiB of waste and when waste is at least its live
payload size. One region is processed on an idle commit-queue turn, copying at
most 8 MiB of live payload plus its index. Larger live regions are deferred;
this cap deliberately avoids an unbounded copy under the storage lock. This is
not a strict wall-time guarantee on a slow disk, nor a total disk quota.

Compaction preserves live entries and tombstones, forces a sibling temporary
file, closes the old handle and atomically replaces the region. File handles are
released asynchronously when the last cache owner closes, keeping close work
off the render thread. The existing disk worker serializes sessions' commits.

The fixed index uses 65,552 bytes per touched region, so sparse regions cost more
than densely occupied regions. Crash-abandoned temporary staging files are not
globally scanned or cleaned by this change. Multiple independent game processes
sharing one world/cache directory are not supported by this locking design.

## Validation and measured scope

Tests cover negative coordinates, type/detail separation, reopen, legacy
migration, queued migration versus invalidation, tombstones, corrupt/truncated
payloads and indexes, uncommitted tails, concurrent readers/writers, handle
eviction, compaction and progressive fine-cache restore without generation.

Full offline builds passed on both loaders: NeoForge 903 passed / 31 conditional
skips; Forge 909 passed / 29 conditional skips; zero failures or errors. The
opt-in production benchmark passed separately (it skips in a normal build).
Artifacts retain the existing `lib/vss-0.3.2-<loader>-<minecraft>.jar` names;
packaged storage/cache classes were checked against final compiled/reobfuscated
outputs. No running game instance was replaced and no GitHub push was performed.

An opt-in test runs the production region class against copies of the same 8,192
records used in the storage experiment (4,096 fine terrain and 4,096 decoration).
Java 21, warm OS cache, two warmups, seven shuffled measured rounds. Reader setup
and close are included. Every imported compressed record is verified identical.

| Operation | Legacy files | Production regions |
| --- | ---: | ---: |
| Read/inflate all records, median | 1295.31 ms | 734.11 ms |
| Probe 8,192 existing headers, median | 496.61 ms | 8.43 ms |
| Logical file bytes | 19,697,015 | 20,942,503 |
| Data files | 8,192 | 19 |

Read/inflate is 43.3% shorter (1.76x throughput); logical bytes increase 6.3%.
This differs from the minimal prototype because production has per-region fixed
indexes and safety behavior. The verified one-time import took 16.22 seconds,
including source reads, staging, forced writes and byte comparisons. It is not a
pure write benchmark. A Forge build overlapped part of this run; per-round times
are retained rather than treating these numbers as universal speed guarantees.
No Minecraft runtime, object decoding, meshing or GPU upload is measured here.

To repeat with copied fixtures in a fresh destination:

```powershell
$env:VSS_REGION_BENCH_SOURCE = 'F:/vss-storage-benchmark-20260921/files'
$env:VSS_REGION_BENCH_OUTPUT = '<new-directory-outside-source>'
.\gradlew.bat test --offline --tests '*PredictionRegionStorageBenchmark'
```

The benchmark skips unless both variables are set and refuses an existing output
directory. Local evidence: `build/region-production-benchmark.log` and
`F:/vss-region-production-20260921/benchmark.txt`.
