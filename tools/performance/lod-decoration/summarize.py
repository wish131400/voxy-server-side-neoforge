"""Validate frozen inputs and complete output equivalence before summarizing timings."""
from collections import defaultdict
from pathlib import Path
import argparse
import hashlib
import json
import statistics


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stats(values):
    return {"mean": statistics.mean(values), "median": statistics.median(values),
            "min": min(values), "max": max(values)}


def load_run(root):
    assert (root / "prediction-complete.txt").read_text().strip() == "success", root
    assert not (root / "server-failure.txt").exists(), root
    assert (root / "checks-complete.txt").read_text().startswith("success"), root
    manifest = json.loads((root / "experiment-manifest.json").read_text())
    for name, expected in manifest["sources"].items():
        assert digest(root / name) == expected, (root, "modified snapshot", name)
    for name, expected in manifest["inputs"].items():
        assert digest(root / name) == expected, (root, "modified input", name)
    environment = json.loads((root / "experiment-environment.json").read_text())
    assert environment.get("horizontalClimateEnabledAndBound") is True, (root, "Unbound pre-production climate baseline")
    rows = json.loads((root / "results.json").read_text())
    expected_count = (environment["warmups"] + environment["rounds"]) * len(environment["steps"]) * len(environment["modes"])
    assert len(rows) == expected_count, (root, len(rows), expected_count)
    groups = defaultdict(list)
    for row in rows:
        assert row["matchesBaseline"] is True, (root, row["mode"], row["round"])
        assert row["horizontalCopiedDelta"] > 0 and row["horizontalBatchesDelta"] > 0, (root, "Climate sharing did not execute")
        groups[row["round"], row["step"]].append(row)
    for identity, group in groups.items():
        assert sorted(r["mode"] for r in group) == sorted(environment["modes"]), identity
        assert len({r["hash"] for r in group}) == 1, (root, identity, "output mismatch")
        if identity[0] >= 0:
            for field in ["modelCalls", "modelCells", "sourceChunks"]:
                assert len({r["counts"][field] for r in group}) == 1, (root, identity, field)
    native = "main-resources\\META-INF\\vss-natives\\windows-x86_64\\vss_native_core.dll"
    processed_native = root / "gradle/resources/main/META-INF/vss-natives/windows-x86_64/vss_native_core.dll"
    assert digest(processed_native) == manifest["sources"][native], (root, "Runtime native differs from frozen snapshot")
    return {"root": str(root), "environment": environment, "inputHashes": manifest["inputs"],
            "sourceDigest": hashlib.sha256(json.dumps(manifest["sources"], sort_keys=True).encode()).hexdigest(),
            "nativeSha256": manifest["sources"][native], "verifiedRows": len(rows),
            "formalQueryCountsIdentical": all(len({r[field] for r in group}) == 1
                for identity, group in groups.items() if identity[0] >= 0
                for field in ["surfacePoints", "surfaceCalls", "biomeQueries"]), "rows": rows}


def summarize(runs):
    assert len({r["sourceDigest"] for r in runs}) == 1, "Runs must use identical source snapshots"
    assert len({r["nativeSha256"] for r in runs}) == 1, "Runs must use identical native libraries"
    assert len({json.dumps(r["inputHashes"], sort_keys=True) for r in runs}) == 1, "Runs must use identical inputs"
    cross_run_oracles = {}
    for run in runs:
        for row in run["rows"]:
            identity = (run["environment"]["seed"], run["environment"]["sideChunks"], row["round"], row["step"])
            expected = cross_run_oracles.setdefault(identity, row["hash"])
            assert row["hash"] == expected, (run["root"], identity, "cross-run output mismatch")
    metrics = ["wallMs", "terrainMs", "decorationMs", "encodingMs", "threadCpuMs", "javaThreadAllocatedMiB",
               "gcMs", "plantBlocks", "featureBoxes", "plantBytes", "surfacePoints", "surfaceCalls", "biomeQueries",
               "horizontalCopiedDelta", "horizontalBatchesDelta"]
    pooled = defaultdict(list)
    by_run = []
    hotspot_pool = defaultdict(list)
    for run in runs:
        formal = [r for r in run["rows"] if not r["warmup"]]
        buckets = defaultdict(list)
        for row in formal:
            buckets[row["step"], row["mode"]].append(row)
            pooled[row["step"], row["mode"]].append(row)
        report = {}
        for (step, mode), rows in sorted(buckets.items()):
            base = {r["round"]: r for r in buckets[step, "baseline"]}
            reductions = [(1 - r["wallMs"] / base[r["round"]]["wallMs"]) * 100 for r in rows]
            report[f"{step}:{mode}"] = {k: stats([r[k] for r in rows]) for k in metrics}
            report[f"{step}:{mode}"].update({"pairedWallReductionPct": stats(reductions), "samples": len(rows),
                "counts": {k: stats([r["counts"][k] for r in rows]) for k in rows[0]["counts"]}})
            for row in rows:
                path = Path(run["root"]) / f'feature-costs-{mode}-{row["round"]}.json'
                if path.exists() and len(run["environment"]["steps"]) == 1:
                    features = json.loads(path.read_text())["features"]
                    hotspot_pool[step, mode].append({
                        "extractionMs": sum(f["nanos"] for f in features if f["id"] == "@extraction") / 1e6,
                        "leafSettleMs": sum(f["nanos"] for f in features if f["id"] == "@leaf-settle") / 1e6,
                        "featureMs": sum(f["nanos"] for f in features if not f["id"].startswith("@")) / 1e6,
                        "structuresMs": sum(f["nanos"] for f in features if f["id"].startswith("@structures/")) / 1e6,
                        "nestedExactMs": sum(f["exactNanos"] for f in features) / 1e6,
                        "nestedBiomeMs": sum(f["biomeNanos"] for f in features) / 1e6,
                    })
        by_run.append({k: v for k, v in run.items() if k != "rows"} | {"summary": report})
    aggregate = {}
    for (step, mode), rows in sorted(pooled.items()):
        aggregate[f"{step}:{mode}"] = {k: stats([r[k] for r in rows]) for k in metrics}
        aggregate[f"{step}:{mode}"]["counts"] = {k: stats([r["counts"][k] for r in rows]) for k in rows[0]["counts"]}
        if hotspot_pool[step, mode]:
            aggregate[f"{step}:{mode}"]["hotspots"] = {k: stats([r[k] for r in hotspot_pool[step, mode]]) for k in hotspot_pool[step, mode][0]}
    return {"scope": "Exact output-preserving isolated full CPU generation; no disk, scheduling, GPU or real-game frame measurement",
            "notes": ["Feature/extraction/leaf scopes are top-level; exact and biome timings are nested and must not be added.",
                      "plantBytes is an implementation-specific estimator, not measured retained heap; mappingValidationMs is not a renderer benchmark.",
                      "Repeated runs cover the same regions; do not count repeated coordinates as independent terrain samples."],
            "runs": by_run, "pooled": aggregate}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("runs", nargs="+", type=Path)
    parser.add_argument("--write", type=Path, required=True)
    args = parser.parse_args()
    report = summarize([load_run(root.resolve()) for root in args.runs])
    args.write.parent.mkdir(parents=True, exist_ok=True)
    args.write.write_text(json.dumps(report, indent=2), encoding="utf-8")
    for run in report["runs"]:
        print(Path(run["root"]).name, run["verifiedRows"], "verified rows")
        for name, row in run["summary"].items():
            print(name, f'{row["wallMs"]["mean"]:.3f} ms', f'{row["pairedWallReductionPct"]["mean"]:+.2f}% paired reduction')
    print("Saved:", args.write)
