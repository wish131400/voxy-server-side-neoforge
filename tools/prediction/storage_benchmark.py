"""Compare storage containers and codecs using read-only real VPD input.

Run with Python and optional lmdb/lz4/zstandard installed. All writes go into a
new output directory. OS caches are NOT flushed: results are warm-cache tests,
not Minecraft frame timing or end-to-end LOD restoration measurements.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import platform
import random
import sqlite3
import statistics
import struct
import sys
import time
import zipfile
import zlib

import lmdb
import lz4.frame
import zstandard


def timed(fn):
    start = time.perf_counter_ns()
    value = fn()
    return (time.perf_counter_ns() - start) / 1e6, value


def summary(values):
    ordered = sorted(values)
    return {"median_ms": statistics.median(values),
            "p90_ms": ordered[math.ceil(len(ordered) * .9) - 1], "rounds_ms": values}


def header(blob):
    return zlib.decompressobj().decompress(blob, 36)


def region_name(key):
    return key.rsplit("/", 1)[0]


class Reader:
    def __init__(self, kind, root):
        self.kind, self.root = kind, root
        self.handles = {}
        if kind == "region":
            self.index = {}
            with (root / "index.bin").open("rb") as f:
                while size := f.read(2):
                    key = f.read(struct.unpack(">H", size)[0]).decode()
                    offset, length = struct.unpack(">QI", f.read(12))
                    self.index[key] = (offset, length, f.read(36))
        elif kind == "sqlite":
            self.db = sqlite3.connect((root / "cache.db").as_uri() + "?mode=ro", uri=True)
            self.db.execute("PRAGMA cache_size=-8192")
        elif kind == "lmdb":
            self.env = lmdb.open(str(root), readonly=True, lock=False, max_dbs=2)
            self.meta = self.env.open_db(b"meta")
            self.data = self.env.open_db(b"data")
            self.tx = self.env.begin()
        elif kind == "zip":
            self.archive = zipfile.ZipFile(root / "cache.zip")

    def probe(self, keys):
        found = {}
        if self.kind == "sqlite":
            for start in range(0, len(keys), 128):
                batch = keys[start:start + 128]
                query = "SELECT k,head FROM records WHERE k IN (" + ",".join("?" * len(batch)) + ")"
                found.update(self.db.execute(query, batch))
            return found
        for key in keys:
            if self.kind == "files":
                try:
                    with (self.root / key).open("rb") as f:
                        value = header(f.read(8192))
                except FileNotFoundError:
                    continue
            elif self.kind == "region":
                entry = self.index.get(key)
                value = entry[2] if entry else None
            elif self.kind == "lmdb":
                value = self.tx.get(key.encode(), db=self.meta)
            else:
                try:
                    value = self.archive.getinfo(key).extra[4:40]
                except KeyError:
                    continue
            if value is not None:
                found[key] = value
        return found

    def get(self, key):
        if self.kind == "files":
            return (self.root / key).read_bytes()
        if self.kind == "region":
            name = region_name(key)
            if name not in self.handles:
                self.handles[name] = (self.root / (name + ".region")).open("rb")
            f = self.handles[name]
            offset, length, _ = self.index[key]
            f.seek(offset)
            return f.read(length)
        if self.kind == "sqlite":
            return self.db.execute("SELECT data FROM records WHERE k=?", (key,)).fetchone()[0]
        if self.kind == "lmdb":
            return self.tx.get(key.encode(), db=self.data)
        return self.archive.read(key)

    def blocks(self, keys):
        if self.kind == "sqlite":
            for start in range(0, len(keys), 128):
                batch = keys[start:start + 128]
                query = "SELECT k,data FROM records WHERE k IN (" + ",".join("?" * len(batch)) + ")"
                yield from self.db.execute(query, batch)
        else:
            for key in keys:
                yield key, self.get(key)

    def close(self):
        for f in self.handles.values():
            f.close()
        if self.kind == "sqlite":
            self.db.close()
        elif self.kind == "lmdb":
            self.tx.abort()
            self.env.close()
        elif self.kind == "zip":
            self.archive.close()


def build(kind, root, records):
    root.mkdir()
    if kind == "files":
        for key, blob in records.items():
            path = root / key
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(blob)
    elif kind == "region":
        groups = collections.defaultdict(list)
        for key in records:
            groups[region_name(key)].append(key)
        with (root / "index.bin").open("wb") as index:
            for name, keys in sorted(groups.items()):
                path = root / (name + ".region")
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("wb") as f:
                    for key in sorted(keys):
                        blob, encoded = records[key], key.encode()
                        index.write(struct.pack(">H", len(encoded)) + encoded)
                        index.write(struct.pack(">QI", f.tell(), len(blob)))
                        index.write(header(blob))
                        f.write(blob)
    elif kind == "sqlite":
        db = sqlite3.connect(root / "cache.db")
        db.execute("CREATE TABLE records (k TEXT PRIMARY KEY, head BLOB NOT NULL, data BLOB NOT NULL) WITHOUT ROWID")
        db.executemany("INSERT INTO records VALUES (?,?,?)",
                       ((k, header(v), v) for k, v in records.items()))
        db.commit()
        db.close()
    elif kind == "lmdb":
        env = lmdb.open(str(root), map_size=1024 ** 3, max_dbs=2)
        meta, data = env.open_db(b"meta"), env.open_db(b"data")
        with env.begin(write=True) as tx:
            for key, blob in records.items():
                tx.put(key.encode(), header(blob), db=meta)
                tx.put(key.encode(), blob, db=data)
        env.sync()
        env.close()
    else:
        with zipfile.ZipFile(root / "cache.zip", "w", compression=zipfile.ZIP_STORED) as archive:
            for key, blob in records.items():
                info = zipfile.ZipInfo(key)
                info.extra = struct.pack("<HH", 0x5653, 36) + header(blob)
                archive.writestr(info, blob)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--per-kind", type=int, default=4096)
    parser.add_argument("--rounds", type=int, default=7)
    args = parser.parse_args()
    source, out = args.source.resolve(), args.output.resolve()
    if source == out or source in out.parents:
        raise ValueError("Benchmark output must be outside the source cache")
    out.mkdir(parents=True, exist_ok=False)
    candidates = list(source.rglob("*.vpd"))
    selected = []
    for group in ("0-0", "1-31"):
        def distance(p):
            x, z = map(int, p.stem.split("_"))
            return (max(abs(x), abs(z)), x * x + z * z, x, z)
        selected.extend(sorted((p for p in candidates if p.relative_to(source).parts[0] == group),
                               key=distance)[:args.per_kind])
    records, expected, raw_sizes, categories = {}, {}, {}, collections.Counter()
    for path in selected:
        key = path.relative_to(source).as_posix()
        blob = path.read_bytes()
        raw = zlib.decompress(blob)
        if raw[:4] != b"VSPD":
            raise ValueError("Unexpected VPD magic")
        records[key], expected[key], raw_sizes[key] = blob, hashlib.sha256(raw).digest(), len(raw)
        categories[key.split("/")[0]] += 1
    if not records:
        raise ValueError("No supported source records")
    report = {"python": platform.python_version(), "platform": platform.platform(),
              "source": str(source), "output": str(out), "seed": 32021,
              "cache_policy": "Warm OS cache; one untimed warmup; fresh reader each timed operation; no system cache flush",
              "scope": "Single-thread storage microbenchmark; excludes Java object decoding, meshing and GPU upload",
              "inventory_count": len(candidates), "sample_count": len(records), "categories": dict(categories),
              "compressed_bytes": sum(map(len, records.values())), "raw_bytes": sum(raw_sizes.values()),
              "manifest_sha256": hashlib.sha256(b"".join(k.encode() + expected[k] for k in sorted(records))).hexdigest(),
              "containers": {}, "codecs": {}}
    kinds = ["files", "region", "sqlite", "lmdb", "zip"]
    for kind in kinds:
        ms, _ = timed(lambda: build(kind, out / kind, records))
        paths = [p for p in (out / kind).rglob("*") if p.is_file()]
        report["containers"][kind] = {"build_ms": ms, "file_count": len(paths),
                                       "logical_bytes": sum(p.stat().st_size for p in paths), "metrics": {}}
        reader = Reader(kind, out / kind)
        try:
            assert reader.probe(list(records)) == {k: header(v) for k, v in records.items()}
            for key in records:
                blob = reader.get(key)
                assert blob == records[key]
                assert hashlib.sha256(zlib.decompress(blob)).digest() == expected[key]
        finally:
            reader.close()
        print("built and verified", kind, flush=True)
    collected = collections.defaultdict(list)
    keys = list(records)
    for iteration in range(args.rounds + 1):
        rng = random.Random(32021 + iteration)
        random_keys = keys.copy()
        rng.shuffle(random_keys)
        ordered = sorted(keys, key=lambda k: (region_name(k), k))
        probes = random_keys + [k + ".missing" for k in random_keys[:len(keys) // 4]]
        rng.shuffle(probes)
        tasks = [(kind, op) for kind in kinds for op in
                 ("open", "probe_125pct", "read_random", "read_spatial", "decode_random", "decode_spatial")]
        rng.shuffle(tasks)
        for kind, op in tasks:
            def run():
                reader = Reader(kind, out / kind)
                try:
                    if op == "open":
                        return 0
                    if op == "probe_125pct":
                        hits = reader.probe(probes)
                        assert len(hits) == len(records)
                        return len(hits)
                    total = 0
                    for key, blob in reader.blocks(random_keys if op.endswith("random") else ordered):
                        total += len(zlib.decompress(blob)) if op.startswith("decode") else len(blob)
                    assert total == report["raw_bytes" if op.startswith("decode") else "compressed_bytes"]
                    return total
                finally:
                    reader.close()
            ms, _ = timed(run)
            if iteration:
                collected[(kind, op)].append(ms)
        print("container round", iteration, flush=True)
    for (kind, op), values in collected.items():
        report["containers"][kind]["metrics"][op] = summary(values)
    # Codec comparison uses the same raw records, entirely in memory.
    raw_records = [zlib.decompress(records[k]) for k in keys]
    codecs = {
        "zlib6": (lambda b: zlib.compress(b, 6), zlib.decompress),
        "zstd1": (zstandard.ZstdCompressor(level=1).compress, zstandard.ZstdDecompressor().decompress),
        "zstd3": (zstandard.ZstdCompressor(level=3).compress, zstandard.ZstdDecompressor().decompress),
        "lz4": (lz4.frame.compress, lz4.frame.decompress),
        "none": (lambda b: b, lambda b: b),
    }
    encoded = {}
    for name, (encode, decode) in codecs.items():
        encoded[name] = [encode(raw) for raw in raw_records]
        assert all(decode(blob) == raw for blob, raw in zip(encoded[name], raw_records))
        report["codecs"][name] = {"bytes": sum(map(len, encoded[name])), "metrics": {}}
    codec_times = collections.defaultdict(list)
    for iteration in range(args.rounds + 1):
        tasks = [(name, op) for name in codecs for op in ("encode", "decode")]
        random.Random(432 + iteration).shuffle(tasks)
        for name, op in tasks:
            encode, decode = codecs[name]
            fn = (lambda: sum(len(encode(raw)) for raw in raw_records)) if op == "encode" else (
                lambda: sum(len(decode(blob)) for blob in encoded[name]))
            ms, total = timed(fn)
            assert total == (report["codecs"][name]["bytes"] if op == "encode" else report["raw_bytes"])
            if iteration:
                codec_times[(name, op)].append(ms)
        print("codec round", iteration, flush=True)
    for (name, op), values in codec_times.items():
        report["codecs"][name]["metrics"][op] = summary(values)
    # LMDB may retain a sparse map reservation; distinguish it from used pages.
    for kind in kinds:
        paths = [p for p in (out / kind).rglob("*") if p.is_file()]
        report["containers"][kind]["logical_bytes"] = sum(p.stat().st_size for p in paths)
    env = lmdb.open(str(out / "lmdb"), readonly=True, lock=False, max_dbs=2)
    report["containers"]["lmdb"]["used_pages_bytes"] = (env.info()["last_pgno"] + 1) * env.stat()["psize"]
    env.close()
    (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"results": str(out / "results.json"), "records": len(records)}), flush=True)


def sqlite_tuning(root):
    """Read-only default versus mmap comparison on the already built database."""
    root = Path(root).resolve()
    report = json.loads((root / "results.json").read_text())
    db = sqlite3.connect((root / "sqlite/cache.db").as_uri() + "?mode=ro", uri=True)
    keys = [row[0] for row in db.execute("SELECT k FROM records ORDER BY k")]
    db.close()
    values = collections.defaultdict(list)
    for iteration in range(8):
        order = keys.copy()
        random.Random(32021 + iteration).shuffle(order)
        probes = order + [k + ".missing" for k in order[:len(order) // 4]]
        random.Random(iteration).shuffle(probes)
        tasks = [(mode, op) for mode in ("default", "mmap256")
                 for op in ("probe", "read_random", "decode_random")]
        random.Random(123 + iteration).shuffle(tasks)
        for mode, op in tasks:
            def run():
                reader = Reader("sqlite", root / "sqlite")
                try:
                    if mode == "mmap256":
                        actual = reader.db.execute("PRAGMA mmap_size=268435456").fetchone()[0]
                        assert actual == 268435456
                    if op == "probe":
                        assert len(reader.probe(probes)) == len(keys)
                    else:
                        total = sum(len(zlib.decompress(v)) if op.startswith("decode") else len(v)
                                    for _, v in reader.blocks(order))
                        assert total == report["raw_bytes" if op.startswith("decode") else "compressed_bytes"]
                finally:
                    reader.close()
            ms, _ = timed(run)
            if iteration:
                values[(mode, op)].append(ms)
    result = {mode: {op: summary(samples) for (m, op), samples in values.items() if m == mode}
              for mode in ("default", "mmap256")}
    (root / "sqlite-tuning.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--sqlite-tuning":
        sqlite_tuning(sys.argv[2])
    else:
        main()
