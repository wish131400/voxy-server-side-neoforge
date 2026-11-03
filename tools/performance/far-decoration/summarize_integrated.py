"""Summarize opt-in complete production path benchmarks from JUnit XML, without sampling a game."""
from pathlib import Path
import argparse, json, re, statistics, xml.etree.ElementTree as ET
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("xml", type=Path)
p.add_argument("--output", type=Path)
a = p.parse_args()
r = ET.parse(a.xml).getroot()
if int(r.get("failures", "0")) or int(r.get("errors", "0")):
    raise SystemExit("Cannot summarize a failed test run")
text = r.findtext("system-out") or ""
rows = []
for line in text.splitlines():
    if "FAR_INTEGRATED " not in line:
        continue
    pairs = dict(re.findall(r"(\w+)=([^ ]+)", line.split("FAR_INTEGRATED ", 1)[1]))
    if int(pairs["round"]) < 0:
        continue
    rows.append({k: (v == "true" if k == "approximate" else float(v)) for k, v in pairs.items()})
medians = {str(fast).lower(): {key: statistics.median(row[key] for row in rows if row["approximate"] == fast)
            for key in ["wallMs", "terrainMs", "decorationAndEncodingMs", "bytes", "boxes"]} for fast in [False, True]}
summary = {"scope": "fixed forest, seed 42, 256 chunks, spacing 4; CPU terrain + decoration + Voxy input encoding; no renderer or disk",
           "rounds": len(rows) // 2, "median": medians,
           "speedup": medians["false"]["wallMs"] / medians["true"]["wallMs"],
           "occupancy": [line[line.index("FAR_OCCUPANCY "):] for line in text.splitlines() if "FAR_OCCUPANCY " in line],
           "largeFootprint": [line[line.index("FAR_LARGE "):] for line in text.splitlines() if "FAR_LARGE " in line]}
result = json.dumps(summary, indent=2)
if a.output:
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(result + "\n", encoding="utf-8")
print(result)
