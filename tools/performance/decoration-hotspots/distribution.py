"""Reconcile saved generation timers without adding nested durations twice.

Reads existing measurements only. Does not launch Minecraft or alter production.
"""
from pathlib import Path
import argparse
import collections
import csv
import hashlib
import json
import math
import re
import statistics


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_hashes(base, hashes):
    changed = [name for name, expected in hashes.items()
               if not (base / name).is_file() or digest(base / name) != expected]
    if changed:
        raise ValueError(f"Files differ from recorded inputs: {changed}")
    return len(hashes)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def summarize(values):
    return {"mean": statistics.mean(values), "min": min(values), "max": max(values)}


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--modes", default="candidate,candidate_repeat")
parser.add_argument("--control", action="append", type=Path, default=[])
parser.add_argument("--native-source-manifest", type=Path)
args = parser.parse_args()
project = Path(__file__).resolve().parents[3]
root = args.directory.resolve()
require((root / "prediction-complete.txt").read_text().strip() == "success", "Incomplete run")
manifest = read_json(root / "input-manifest.json")
hotspot = read_json(root / "hotspot-manifest.json")
verification = {}
for key in ["sources", "resources", "implementationSources"]:
    verification[key] = verify_hashes(project, manifest[key])
verification["snapshotSources"] = verify_hashes(root, hotspot["sources"])
verification["inputs"] = verify_hashes(root, manifest["inputs"])
if args.native_source_manifest:
    verification["nativeSources"] = verify_hashes(project, read_json(args.native_source_manifest))
dll = Path("src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll")
require(digest(project / dll) == manifest["experimentDllSha256"], "Production DLL changed")
require(digest(root / "main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll")
        == manifest["experimentDllSha256"], "Measured DLL differs")
verification["dllSha256"] = manifest["experimentDllSha256"]

all_rows = read_json(root / "results.json")
modes = set(args.modes.split(","))
rows = [r for r in all_rows if not r["warmup"] and r["mode"] in modes]
require(bool(rows), "No formal rows selected")
require(len({(r["mode"], r["round"]) for r in rows}) == len(rows), "Duplicate rounds")
for control in args.control:
    require((control / "prediction-complete.txt").read_text().strip() == "success", "Incomplete control")
    original = {r["round"]: r["hash"] for r in read_json(control / "results.json") if not r["warmup"]}
    require(all(original.get(r["round"]) == r["hash"] for r in rows), "Output hash mismatch")
verification["matchingControlDirectories"] = [str(p.resolve()) for p in args.control]

# stageDetail counters persist across modes; use the immediately preceding row,
# including warmups. They are cumulative integer milliseconds, not per-row time.
stage_deltas = {}
previous = None
for r in all_rows:
    path = root / f"diagnostics-{r['mode']}-{r['round']}.txt"
    text = path.read_text(encoding="utf-8")
    block = re.search(r"stageDetail=\{([^}]*)\}", text)
    require(block is not None, f"Missing native stage counters: {path}")
    current = {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", block.group(1))}
    if previous is not None:
        delta = {k: v - previous[k] for k, v in current.items()}
        require(all(v >= 0 for v in delta.values()), "Cumulative counters reset unexpectedly")
        stage_deltas[r["mode"], r["round"]] = delta
    previous = current

costs = collections.defaultdict(collections.Counter)
metadata = {}
partitions = []
checks = []
counts = collections.Counter()
for r in rows:
    data = read_json(root / f"feature-costs-{r['mode']}-{r['round']}.json")
    require(data["oracleSkipped"] == 0 and data["heightCalls"] == 0, "Altered experiment path")
    require(data["repeatedJavaExactPoints"] == 0, "Unexpected repeated Java exact coordinates")
    groups = collections.defaultdict(collections.Counter)
    for c in data["features"]:
        id = c["id"]
        group = "features" if not id.startswith("@") else (
            "structures" if id.startswith("@structures/") else id)
        require(group in {"features", "structures", "@extraction", "@leaf-settle"}, "Unknown scope")
        numeric = {k: v for k, v in c.items() if isinstance(v, int)}
        groups[group].update(numeric)
        costs[id].update(numeric)
        metadata[id] = {k: v for k, v in c.items() if isinstance(v, str)}
        require(c["nanos"] >= c["exactNanos"] + c["biomeNanos"] + c["displayNanos"],
                f"Query timers overlap: {id}")
    nested_keys = ["exactNanos", "biomeNanos", "displayNanos"]
    totals = {k: sum(c[k] for c in groups.values()) / 1e6 for k in ["nanos", *nested_keys]}
    native = stage_deltas[r["mode"], r["round"]]["featureMs"]
    parts = {
        "terrain": r["terrainMs"],
        "decoration_exact_query": totals["exactNanos"],
        "decoration_java_biome_query": totals["biomeNanos"],
        "decoration_display_query": totals["displayNanos"],
        "decoration_native_feature": native,
        "decoration_feature_remainder": (
            groups["features"]["nanos"] - sum(groups["features"][k] for k in nested_keys)) / 1e6 - native,
        "decoration_structure_remainder": (
            groups["structures"]["nanos"] - sum(groups["structures"][k] for k in nested_keys)) / 1e6,
        "decoration_extraction_remainder": (
            groups["@extraction"]["nanos"] - sum(groups["@extraction"][k] for k in nested_keys)) / 1e6,
        "decoration_leaf_remainder": (
            groups["@leaf-settle"]["nanos"] - sum(groups["@leaf-settle"][k] for k in nested_keys)) / 1e6,
        "decoration_unscoped": r["decorationMs"] - totals["nanos"],
        "voxy_source_encoding": r["encodingMs"],
    }
    require(all(v >= 0 for v in parts.values()), "Negative exclusive duration")
    require(math.isclose(sum(parts.values()), r["wallMs"], abs_tol=1e-6), "Partition does not close")
    require(data["uniqueJavaExactPoints"] == r["surfacePoints"] ==
            sum(c["exactPoints"] for c in groups.values()), "Point counts differ")
    partitions.append(parts)
    checks.append({"mode": r["mode"], "round": r["round"], "wallMs": r["wallMs"],
                   "sumExclusiveMs": sum(parts.values()), "hash": r["hash"]})
    counts.update({k: data[k] for k in ["uniqueJavaExactPoints", "repeatedJavaExactPoints"]})

n = len(rows)
wall = statistics.mean(r["wallMs"] for r in rows)
deco = statistics.mean(r["decorationMs"] for r in rows)
exclusive = []
for key in partitions[0]:
    stats = summarize([r[key] for r in partitions])
    exclusive.append({"id": key, "meanMs": stats["mean"], "minMs": stats["min"], "maxMs": stats["max"],
                      "totalPercent": stats["mean"] / wall * 100})
features = []
for id, c in sorted(costs.items(), key=lambda kv: -kv[1]["nanos"]):
    item = dict(metadata[id])
    for key in ["nanos", "exactNanos", "biomeNanos", "biomeLoadNanos", "displayNanos", "noWriteNanos"]:
        item["meanMs" if key == "nanos" else key.removesuffix("Nanos") + "Ms"] = c[key] / n / 1e6
    for key in ["calls", "writes", "noWriteCalls", "exactPoints", "biomeCalls", "biomeLoads",
                "javaCalls", "nativeCalls", "treeCalls"]:
        item[key] = c[key] / n
    item["totalPercent"] = item["meanMs"] / wall * 100
    item["decorationPercent"] = item["meanMs"] / deco * 100
    features.append(item)
stage = {k: statistics.mean(stage_deltas[r["mode"], r["round"]][k] for r in rows)
         for k in stage_deltas[rows[0]["mode"], rows[0]["round"]]}
result = {
    "measurementDirectory": str(root), "environment": read_json(root / "experiment-environment.json"),
    "modes": sorted(modes), "formalRows": n, "uniqueRegions": len({r["round"] for r in rows}),
    "verification": verification,
    "metrics": {k: summarize([r[k] for r in rows]) for k in [
        "wallMs", "terrainMs", "decorationMs", "encodingMs", "threadCpuMs", "processCpuMs",
        "javaThreadAllocatedMiB", "gcMs", "plantBlocks", "surfacePoints", "biomeQueries"]},
    "exclusive": exclusive, "inclusiveScopes": features, "nativeStageDeltas": stage,
    "pointCounts": {k: v / n for k, v in counts.items()}, "roundChecks": checks,
    "notes": [
        "Reanalysis of saved November 1 runs; no new gameplay measurement.",
        "Percentages use aggregate elapsed generation time; not whole-game CPU or GPU utilization.",
        "Native featureMs includes internal native terrain/biome work and successful JNI calls only.",
        "Native stage counters have integer millisecond truncation; retained in the exclusive remainder.",
        "biomeLoadMs is nested in biomeMs. Feature scopes include all their query times.",
        "Upload/download/proxy times are cross-checks inside feature scopes; do not add to exclusive totals.",
        "No journal write does not imply that an attempt can be safely skipped.",
        "Unscoped remainder includes setup, source/cache merging, copies and attribution overhead.",
        "No renderer, mesh building, GPU upload, disk I/O, scheduler wait or world-context setup measured.",
    ],
}
feature_costs = collections.Counter()
for id, c in costs.items():
    if not id.startswith("@"):
        feature_costs.update(c)
result["featureAttempts"] = {
    "calls": feature_costs["calls"] / n,
    "noWriteCalls": feature_costs["noWriteCalls"] / n,
    "noWritePercent": feature_costs["noWriteCalls"] / feature_costs["calls"] * 100,
    "noWriteMs": feature_costs["noWriteNanos"] / n / 1e6,
    "noWriteExactMs": feature_costs["noWriteExactNanos"] / n / 1e6,
}
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
(out / "distribution.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
with (out / "exclusive.csv").open("w", newline="", encoding="utf-8-sig") as f:
    writer = csv.DictWriter(f, fieldnames=list(exclusive[0]))
    writer.writeheader()
    writer.writerows(exclusive)
with (out / "inclusive-features.csv").open("w", newline="", encoding="utf-8-sig") as f:
    writer = csv.DictWriter(f, fieldnames=list(features[0]))
    writer.writeheader()
    writer.writerows(features)
print(json.dumps({"formalRows": n, "uniqueRegions": result["uniqueRegions"], "verification": verification,
                  "exclusive": exclusive, "output": str(out)}, indent=2, ensure_ascii=False))
