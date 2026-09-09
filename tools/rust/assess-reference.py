"""Measure reference coverage; run validate-reference.py first on a workspace copy."""
from collections import Counter, defaultdict
import gzip
import json
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
captures = []
columns, vegetation = [], []
counts = Counter()
registry_counts = {}
custom_types = set()
chunks = defaultdict(list)
blocks = Counter()


def rows(folder, name):
    with gzip.open(folder / f"{name}.jsonl.gz", "rt", encoding="utf-8") as stream:
        return [json.loads(line) for line in stream]


def types(value):
    if isinstance(value, dict):
        item = value.get("type")
        if isinstance(item, str) and ":" in item and not item.startswith("minecraft:"):
            custom_types.add(item)
        for item in value.values():
            types(item)
    elif isinstance(value, list):
        for item in value:
            types(item)


for manifest_path in sorted(root.glob("*/manifest.json")):
    folder = manifest_path.parent
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identity = (manifest["seed"], manifest["dimension"], manifest["fingerprint"])
    captured = {name: rows(folder, name) for name in ("columns", "density", "noise", "base-columns", "random", "vegetation")}
    counts.update({name: len(values) for name, values in captured.items()})
    for row in captured["columns"]:
        columns.append((identity, row))
    for row in captured["vegetation"]:
        chunks[(*identity, row["chunkX"], row["chunkZ"])].append(sorted(row["blocks"]))
        for block in row["blocks"]:
            blocks[re.search(r'Name:"([^"]+)"', block[3]).group(1)] += 1
    registry = json.loads((folder / "registries.json").read_text(encoding="utf-8"))
    registry_counts[manifest["sha256"]["registries.json"]] = {k: len(v) for k, v in registry.items()}
    types(registry)
    captures.append({"directory": folder.name, "seed": identity[0], "dimension": identity[1],
                     "fingerprint": identity[2], "origin": [manifest["originX"], manifest["originZ"]],
                     "vegetationBlocks": sum(len(r["blocks"]) for r in captured["vegetation"]),
                     "vegetationDiagnostics": manifest["vegetationDiagnostics"]})

drift = defaultdict(list)
for _, row in columns:
    drift[row["spacing"]].append(abs(row.get("native", row["resolved"])["surfaceY"] - row["vanillaOceanFloorWg"]))
report = {
    "captures": captures, "records": dict(counts),
    "distinctSeeds": len({x[0][0] for x in columns}),
    "distinctDimensions": sorted({x[0][1] for x in columns}),
    "uniqueColumnsXZ": len({(*identity, row["x"], row["z"]) for identity, row in columns}),
    "sampledBiomes": dict(Counter(row["biome"] for _, row in columns)),
    "fluidCodes": dict(Counter(row.get("native", row["resolved"])["fluid"] for _, row in columns)),
    "uniqueRgb": {name: len({row[name] for _, row in columns}) for name in ("grassRgb", "foliageRgb", "waterRgb")},
    "vegetationBlocksIncludingRepeatedChunks": dict(blocks),
    "uniqueVegetationChunks": len(chunks),
    "repeatedVegetationChunks": sum(len(v) > 1 for v in chunks.values()),
    "repeatedVegetationOutputsIdentical": all(all(r == values[0] for r in values) for values in chunks.values()),
    "surfaceVsVanillaBaseHeightBySpacing": {s: {"count": len(v), "meanAbsDrift": sum(v) / len(v), "maxAbsDrift": max(v)} for s, v in drift.items()},
    "registrySnapshots": registry_counts, "customRegistryTypes": sorted(custom_types),
}
replay_path = root / "native-replay.json"
if replay_path.exists():
    replay = json.loads(replay_path.read_text(encoding="utf-8"))
    trials = [r for capture in replay for r in capture["trials"]]
    report["nativeReplay"] = {
        "grids": len(trials), "allMatchCapture": all(t["matchesCapture"] for t in trials),
        "originChangesWithSpacing": sum(len({json.dumps(t["first"], sort_keys=True) for t in c["trials"]}) > 1 for c in replay),
        "singleColumnMatchesBySpacing": {s: {
            "matches": sum(t["singleColumnMatchesOf16"]["blockOrigin"] for t in trials if t["order"] == 0 and t["spacing"] == s),
            "total": 16 * sum(t["order"] == 0 and t["spacing"] == s for t in trials),
        } for s in (1, 16, 256, 4096)},
    }
output = root / "assessment.json"
output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({k: v for k, v in report.items() if k not in ("captures", "registrySnapshots", "sampledBiomes")}, ensure_ascii=False, indent=2))
