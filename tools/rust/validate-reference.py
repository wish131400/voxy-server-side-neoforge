"""Validate reference exports and report measured coverage without asserting vanilla parity."""
import collections
import gzip
import hashlib
import json
import re
from pathlib import Path
import struct
import sys

root = Path(sys.argv[1])
totals = collections.Counter()
blocks = collections.Counter()
drifts = []
fields = ("surfaceY fluidY biomeIndex topBlockIndex structureIndex treeKind treeDensity "
          "treeHeight fluid flags groundFeatureKind underBlockIndex deepBlockIndex "
          "surfaceBottom lowerTop lowerBottom spanFloor").split()
fixtures = sorted(root.glob("*/manifest.json")) or [root / "manifest.json"]
for path in fixtures:
    metadata = json.loads(path.read_text())
    assert metadata["complete"]
    legacy = metadata["format"] == "vss-rust-reference-1"
    assert legacy or metadata["format"] == "vss-rust-reference-2"
    folder = path.parent
    for name, digest in metadata["sha256"].items():
        assert hashlib.sha256((folder / name).read_bytes()).hexdigest() == digest, name
    if legacy:
        assert metadata["nativeAbi"] == 10
        raw = {}
        fmt = struct.Struct(("<" if metadata["byteOrder"] == "LITTLE_ENDIAN" else ">") + "iiHHH6BHH4h")
        assert fmt.size == metadata["columnBytes"] == 32
        for grid in metadata["grids"]:
            data = (folder / grid["file"]).read_bytes()
            assert len(data) == grid["axis"] ** 2 * fmt.size
            raw[grid["spacing"]] = list(fmt.iter_unpack(data))
    with gzip.open(folder / "columns.jsonl.gz", "rt") as stream:
        for line in stream:
            row = json.loads(line)
            if legacy:
                expected = dict(zip(fields, raw[row["spacing"]][row["rawRecord"]]))
                assert expected == row["native"], (folder, row["rawRecord"])
                assert row["resolved"]["surfaceY"] == expected["surfaceY"]
            else:
                assert metadata["source"] == "minecraft-java"
                expected = row["resolved"]
            drifts.append(abs(expected["surfaceY"] - row["vanillaOceanFloorWg"]))
            totals["columns"] += 1
    for name in ("density", "noise", "base-columns", "random", "vegetation"):
        with gzip.open(folder / f"{name}.jsonl.gz", "rt") as stream:
            for line in stream:
                row = json.loads(line)
                totals[name] += 1
                if name == "vegetation":
                    for block in row["blocks"]:
                        blocks[block[3]] += 1
    totals["fixtures"] += 1
report = {"validated": True, "counts": dict(totals),
          "surfaceVsVanillaBaseHeight": {"meanAbsDrift": sum(drifts) / len(drifts), "maxAbsDrift": max(drifts)},
          "vegetationBlocks": dict(blocks.most_common()),
          "limits": "Fixed-biome and virtual-ground references do not establish exact full-world feature placement."}
(root / "validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps({key: value for key, value in report.items() if key != "vegetationBlocks"}, indent=2))
for term in ("log", "leaves", "grass", "dandelion", "poppy", "bamboo"):
    # Exported states use SNBT; waterlogged is a property, not a block identifier.
    print(term, sum(count for state, count in blocks.items()
                   if term in re.search(r'Name:"([^"]+)"', state).group(1)))
