"""Summarize the opt-in decoration profile without altering the original comparison."""
import collections
import csv
import json
import pathlib
import re
import sys


def counters(text, name):
    match = re.search(re.escape(name) + r"=\{([^}]+)\}", text)
    if match is None:
        raise ValueError(f"Missing diagnostic group: {name}")
    return {k: int(v) for k, v in re.findall(r'"?(\w+)"?\s*[:=]\s*(\d+)', match.group(1))}


profile = pathlib.Path(sys.argv[1]).resolve()
if not (profile / "prediction-complete.txt").is_file():
    raise ValueError("Profile did not finish")
with (profile / "prediction.csv").open(newline="", encoding="utf-8") as stream:
    all_rows = list(csv.DictReader(stream))
rows = [r for r in all_rows if r["warmup"] == "false"]
if len(rows) != 5 or {r["round"] for r in rows} != {str(i) for i in range(5)}:
    raise ValueError("Expected five complete measured regions")
if any(r["step"] != "4" or r["chunks"] != "256" for r in rows):
    raise ValueError("Unexpected prediction footprint")
means = {name: sum(float(r[name]) for r in rows) / len(rows)
         for name in ("wallMs", "processCpuMs", "terrainMs", "decorationMs", "encodingMs")}
if abs(sum(means[k] for k in ("terrainMs", "decorationMs", "encodingMs")) - means["wallMs"]) > 5:
    raise ValueError("Phase timing does not account for the measured loop")
deltas = {}
for group in ("javaPlacement", "stageDetail", "groundQueries", "biomeIdentity"):
    total = collections.Counter()
    for row in rows:
        before = counters((profile / f"sampler-before-{row['round']}.txt").read_text(), group)
        after = counters((profile / f"sampler-after-{row['round']}.txt").read_text(), group)
        for key, value in after.items():
            difference = value - before[key]
            if difference < 0:
                raise ValueError(f"Counter reset: {group}.{key}")
            total[key] += difference
    deltas[group] = dict(total)
tree_before = counters((profile / "features-step4-round-1.txt").read_text(), "treeModels")
tree_after = counters((profile / "features-step4-round4.txt").read_text(), "treeModels")
tree_delta = {k: tree_after[k] - tree_before[k]
              for k in ("built", "hits", "fallback", "placed", "buildMs")}
events = json.loads((profile / "jfr-samples.json").read_text())
samples = [e for e in events if "frames" in e
           and any("PredictionGenerationComparison.run:" in f for f in e["frames"])]
if not samples:
    raise ValueError("No benchmark server-thread samples")
leaves = collections.Counter(e["frames"][0].rsplit(":", 1)[0] for e in samples if e["frames"])
inclusive = collections.Counter()
for event in samples:
    inclusive.update(set(f.rsplit(":", 1)[0] for f in event["frames"]))
hotspots = {
    "exact_surface_native": "RustWorldgenBackend.surfacePoints:",
    "java_biome_calculation": "PredictionBiomeCache.sample:",
    "native_feature": "RustWorldgenBackend.placedFeature:",
    "tree_model_path_inclusive": "PredictionTreeModels.place:",
    "surface_extraction_inclusive": "PredictionVegetation.surfaceBlocks:",
    "leaf_settling_inclusive": "PredictionLeafStates.settle:",
}
hotspot_counts = {name: sum(any(needle in frame for frame in event["frames"]) for event in samples)
                  for name, needle in hotspots.items()}
pauses = [e["durationNanos"] / 1e6 for e in events if e["type"] == "jdk.GCPhasePause"]
java_calls = sum(deltas["javaPlacement"][k] for k in
                 ("TREE_MODEL", "ORDER_MISMATCH", "UNSUPPORTED", "TRANSACTION", "PROXY", "UNREGISTERED"))
summary = {
    "means": means,
    "measuredCounterDeltas": deltas,
    "measuredTreeModelDeltas": tree_delta,
    "javaCalls": java_calls,
    "javaNoWriteFraction": deltas["javaPlacement"]["withoutWrites"] / java_calls,
    "sampleCount": len(samples),
    "sampleTypes": dict(collections.Counter(e["type"] for e in samples)),
    "hotspotSamples": hotspot_counts,
    "hotspotFractions": {k: n / len(samples) for k, n in hotspot_counts.items()},
    "topLeaves": leaves.most_common(30),
    "topInclusive": inclusive.most_common(60),
    "gcPauseMs": {"sum": sum(pauses), "max": max(pauses, default=0), "count": len(pauses)},
    "limitations": [
        "Java and native samples describe call-stack hotspots, not calibrated CPU shares.",
        "Inclusive stacks and native ground-query counters overlap other placement timings.",
        "Native-method stacks do not resolve internal Rust functions.",
        "This supplementary run uses the newer density-specialization DLL; see the input manifest.",
    ],
}
(profile / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: summary[k] for k in
                  ("means", "javaCalls", "javaNoWriteFraction", "sampleCount", "hotspotFractions", "gcPauseMs")}, indent=2))
