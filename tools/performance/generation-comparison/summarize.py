"""Summarize paired cold-area benchmarks; CPU totals and elapsed time stay separate."""
import csv
import json
import statistics
import sys
from pathlib import Path


def main():
    root = Path(sys.argv[1])
    assert (root / "server-complete.txt").exists(), "Real generation did not complete"
    assert (root / "prediction-complete.txt").exists(), "Prediction did not complete"
    assert not (root / "server-failure.txt").exists(), "A benchmark phase failed"
    runtime_log = (root / "prediction-server-launch.log").read_text(encoding="utf-8-sig", errors="replace")
    assert "BUILD SUCCESSFUL" in runtime_log and "VSS_PREDICTION_COMPARISON terrain_vegetation_structures,4,4,false" in runtime_log
    with (root / "full-generation.csv").open(encoding="utf-8-sig", newline="") as stream:
        full = {int(r["round"]): r for r in csv.DictReader(stream) if r["warmup"] == "false"}
    assert set(full) == set(range(5))
    assert all(int(r["lightCorrect"]) == 256 for r in full.values())
    with (root / "prediction.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = [r for r in csv.DictReader(stream) if r["warmup"] == "false"]
    groups = {}
    for row in rows:
        expected = full[int(row["round"])]
        assert all(row[k] == expected[k] for k in ("chunkX", "chunkZ", "chunks"))
        groups.setdefault((row["mode"], int(row["step"])), []).append(row)
    assert len(groups) == 4
    summary = {
        "environment": json.loads((root / "environment.json").read_text(encoding="utf-8")),
        "full": {
            "medianWallMs": statistics.median(float(r["wallMs"]) for r in full.values()),
            "totalWallMs": sum(float(r["wallMs"]) for r in full.values()),
            "totalProcessCpuMs": sum(float(r["processCpuMs"]) for r in full.values()),
            "medianProcessCpuMs": statistics.median(float(r["processCpuMs"]) for r in full.values()),
            "requestedChunks": 1280,
            "loadedHolderDeltaPerRound": [int(r["loadedAfter"]) - int(r["loadedBefore"]) for r in full.values()],
        },
        "prediction": [],
        "limits": [
            "One Ryzen 7 7700, Java 21, vanilla Overworld generation plus VSS; no terrain mods or C2ME.",
            "Five separate cold 16x16-chunk areas, seed 42; two disjoint warmup areas are excluded.",
            "Real generation includes required neighbour statuses; no generated target is read from disk.",
            "Prediction includes production sampling and Voxy CPU tile encoding, optional vegetation and supported surface structures.",
            "No Voxy mesh building, rendering, network, disk-cache restore or final world save is timed.",
            "Process CPU includes Java, native, workers, JIT and GC. Windows CPU accounting has finite granularity.",
            "Different outputs: predicted surface is not a playable FULL chunk and does not reproduce all underground worldgen.",
            "This is serial comparison of the two pipelines, not a gameplay FPS or continuous exploration benchmark.",
            "The production bounded decoration context skipped 3 feature attempts and 11 structure attempts over the 7 warmup/measured windows per decorated configuration.",
            "Skipped attempts report decoration writes outside bounds and unsupported getLevel; timings describe current behaviour, not fully identical output.",
        ],
    }
    for (mode, step), group in sorted(groups.items()):
        assert len(group) == 5 and {int(r["round"]) for r in group} == set(range(5))
        wall = sum(float(r["wallMs"]) for r in group)
        cpu = sum(float(r["processCpuMs"]) for r in group)
        result = {
            "mode": mode, "step": step,
            "medianWallMs": statistics.median(float(r["wallMs"]) for r in group),
            "medianProcessCpuMs": statistics.median(float(r["processCpuMs"]) for r in group),
            "totalWallMs": wall, "totalProcessCpuMs": cpu,
            "elapsedSpeedup": summary["full"]["totalWallMs"] / wall,
            "processCpuRatio": summary["full"]["totalProcessCpuMs"] / cpu,
            "pairedElapsedRatios": [float(full[int(r["round"])]["wallMs"]) / float(r["wallMs"]) for r in group],
            "pairedCpuRatios": [float(full[int(r["round"])]["processCpuMs"]) / float(r["processCpuMs"]) for r in group],
            "featureBoxes": [int(r["featureBoxes"]) for r in group],
            "encodedBytes": [int(r["encodedBytes"]) for r in group],
        }
        if mode != "terrain":
            assert sum(result["featureBoxes"]) > 0, "Decoration path did not produce features"
        summary["prediction"].append(result)
    (root / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
