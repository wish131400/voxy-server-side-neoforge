# Prediction cache storage experiment

Date: 2026-09-21. This is a read-only experiment against existing game cache
input, not a production cache migration. No game JAR or cache file was changed.

## Dataset and method

- Source: the previously profiled overworld cache for world `新的世界 (20)`.
  Inventory contained 92,438 VPD files. Source files were only read.
- Selected the nearest 4,096 entries to coordinate origin from each of `0-0`
  (finest terrain) and `1-31` (surface decorations), ordered by Chebyshev distance,
  squared distance, then coordinates. This is a spatial subset, not a random
  sample of every world/dimension/LOD level.
- 8,192 records, 19,697,015 compressed bytes (18.78 MiB), 1,303,396,694
  decompressed bytes (1,243.02 MiB). Terrain accounts for 1,285,679,841 raw bytes;
  decorations account for 17,716,853. Terrain dominates decompression work.
- All containers preserve the exact original zlib-compressed record bytes.
  Every record was verified byte-for-byte and against its decompressed SHA-256.
- Output on the same F: NVMe drive, Predator SSD GM7000 2TB, under
  `F:/vss-storage-benchmark-20260921/`; not inside the game's cache.
- Python 3.14.2, SQLite 3.50.4, lmdb 2.3.0, lz4 4.4.5, zstandard 0.25.0.
  Java cross-check: Java 25.0.1. No Minecraft process was running at the check.
- Warm OS filesystem cache: one untimed Python warmup and seven measured rounds,
  alternating test order with fixed seeds. Java uses two warmups and seven
  measured rounds. Fresh reader/index setup and close are included in every
  measured operation. This is not a flushed-cache or first-boot test.
- Single worker. Container reads do not retain decompressed records. Codec tests
  are a separate in-memory experiment. No Java object reconstruction, meshing,
  admission queue, GPU upload or in-game FPS is measured here.
- Probe workload: all 8,192 real keys plus 2,048 absent keys. The returned data is
  the first 36 raw header bytes. Indexed containers store this metadata explicitly;
  files must open and partially decompress a record. Thus the probe comparison
  measures an indexed design versus current file organization, not just extensions.
- Region format: one uncompressed binary index, 19 spatial payload files,
  independent existing zlib records, open file handles reused per operation.
  ZIP uses ZIP_STORED around the existing compressed records and a custom header
  extra field. SQLite uses WITHOUT ROWID, an 8 MiB page cache and batches of 128
  keys; SQL can reorder results within a batch. LMDB uses separate metadata/data
  databases and a read transaction per operation.

## Python container results

Median milliseconds for the whole 8,192-record workload, not one record:

| Container | Probe including misses | Random read | Random read + inflate | Spatial read + inflate | Logical size MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Individual VPD files | 922.21 | 689.89 | 1009.37 | 996.70 | 18.78 |
| Indexed spatial regions | 5.20 | 80.85 | 315.38 | 222.56 | 19.32 |
| SQLite, default mmap disabled | 421.74 | 356.54 | 593.62 | 568.33 | 29.56 |
| LMDB | 30.12 | 166.17 | 402.61 | 401.22 | 30.06 used pages |
| ZIP_STORED container | 23.61 | 209.52 | 460.50 | 405.11 | 20.30 |

LMDB was created with a 1 GiB map reservation. On this Windows run the logical
file length remains 1 GiB, while used pages are 31,518,720 bytes (30.06 MiB).
The Windows compressed/sparse file size query reports 31,531,008 bytes including
the lock file. Do not report the 1 GiB reservation as live record storage or RAM.
Other logical sizes exclude filesystem directory/index/allocation overhead.

The initial import was also timed in raw results, but it is not a fair durable
write comparison: files/regions lack per-record fsync and production temporary
file replacement, whereas databases have their own commit behavior. It must not
be used to choose a writer without a durability/update benchmark.

### SQLite configuration check

Additional interleaved seven-round read-only check, using the same database:

| SQLite configuration | Probe | Random read | Random read + inflate |
| --- | ---: | ---: | ---: |
| Default mmap disabled | 413.07 | 355.93 | 590.43 |
| mmap_size = 256 MiB | 43.76 | 36.66 | 335.40 |

Memory-mapped SQLite is close to regions on this workload. The mapping is a
virtual address limit, not an unconditional allocation of 256 MiB of private RAM.
This does not measure concurrent writes, growing databases or memory pressure.

## JVM cross-check

Standard Java files, region files and ZipFile; same records, random requests,
zlib inflation via InflaterInputStream. Medians across seven measured rounds:

| Container | Probe | Random read | Random read + inflate |
| --- | ---: | ---: | ---: |
| Individual VPD files | 540.23 | 462.87 | 1157.15 |
| Indexed spatial regions | 2.48 | 36.73 | 701.67 |
| ZIP_STORED container | 2.32 | 67.24 | 738.84 |

Region read + inflate took 39.4% less time (1.65x throughput). ZIP took 36.2%
less time. This confirms a benefit in JVM IO, but the magnitude differs from
Python: bulk inflation and IO APIs differ between runtimes. Neither harness
executes the entire production PredictionDiskCache decoder. Java SQLite/LMDB
bindings were not tested, so Python database timings are not JVM predictions.

## Independent compression comparison

Same raw records, compressed independently per record, entirely in memory:

| Codec | Payload MiB | Encode ms | Decode ms |
| --- | ---: | ---: | ---: |
| zlib level 6 | 17.72 | 2187.83 | 195.24 |
| Zstd level 1 | 16.53 | 289.19 | 218.53 |
| Zstd level 3 | 16.46 | 323.45 | 215.30 |
| LZ4 frame default | 38.12 | 150.37 | 136.76 |
| Uncompressed | 1243.02 | 1.34 | 1.31 |

The uncompressed timing is a reference loop over existing byte objects, not a
disk read or memory copy. Re-encoded zlib differs in size from existing game
records; the container comparison never uses the re-encoded bytes. This dataset
is highly compressible; findings must not be extrapolated to every world.
LZ4 decode is about 30% shorter than zlib but payload is 2.15x larger. Zstd
encodes much faster and slightly smaller, but did not decode faster in this run.

## Decision

Storage organization has a measurable benefit even on an NVMe SSD with warm OS
cache. Regions and memory-mapped SQLite are both credible next candidates;
SQLite supplies transactions and free-space management that the region prototype
does not implement. ZIP works for static archives but has no efficient general
in-place replacement design here. NBT/JSON are serialization formats, not a fix
for small-file organization; RocksDB and full DH/Voxy loaders were not measured.

Before production migration, benchmark actual Java decoding and incremental
updates, invalidation, crash recovery, sustained memory use, and end-to-end
in-game restoration. A custom region implementation needs safe index publication,
concurrent reader semantics and reclamation. Keep these costs in the comparison.
The 1.65x Java read/inflate result is not a claim of 1.65x entire-world loading.

## Reproduction and evidence

```powershell
python -m pip install --target build/storage-benchmark-deps lmdb lz4 zstandard
$env:PYTHONPATH = (Resolve-Path build/storage-benchmark-deps).Path
python tools/prediction/storage_benchmark.py <source-cache-root> <new-output-directory> --rounds 7
python tools/prediction/storage_benchmark.py --sqlite-tuning <output-directory>
java tools/prediction/StorageContainerBenchmark.java <output-directory>
```

Local reports are copied to `build/storage-benchmark-20260921/`: `results.json`,
`sqlite-tuning.json`, `allocation.json`, and `java-results.txt`. The JSON includes
per-round times, seeds, sizes and a dataset manifest hash. Copied payloads remain
in the F: output directory. Original caches remain untouched.
