"""Keep verified experiment evidence outside disposable Gradle build directories."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
from summarize import load_run

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
parser.add_argument("runs", nargs="+", type=Path)
parser.add_argument("--comparison", type=Path)
parser.add_argument("--report", type=Path)
args = parser.parse_args()
runs = [load_run(path.resolve()) for path in args.runs]
assert len({run["sourceDigest"] for run in runs}) == 1, "Source snapshots differ"
out = args.output.resolve()
out.mkdir(exist_ok=False)
first = Path(runs[0]["root"])
snapshot = out / "snapshot"
snapshot.mkdir()
for name in ["main-java", "main-resources", "harness-java"]:
    shutil.copytree(first / name, snapshot / name)
for name in ["worldgen-profile.bin", "structure-templates.json", "experiment-manifest.json"]:
    shutil.copy2(first / name, snapshot / name)
for run in runs:
    source = Path(run["root"])
    target = out / source.name
    target.mkdir()
    for path in source.iterdir():
        if path.is_file() and path.suffix in [".json", ".txt", ".log"]:
            shutil.copy2(path, target / path.name)
tools = out / "tools"
shutil.copytree(Path(__file__).parent, tools, ignore=shutil.ignore_patterns("__pycache__"))
if args.comparison:
    shutil.copy2(args.comparison, out / "comparison.json")
if args.report:
    shutil.copy2(args.report, out / args.report.name)
manifest = {"scope": "One shared patched source/native/input snapshot, per-run raw logs/results, and experiment tools",
            "sourceDigest": runs[0]["sourceDigest"], "nativeSha256": runs[0]["nativeSha256"],
            "runs": [{k: v for k, v in run.items() if k != "rows"} for run in runs],
            "files": {str(path.relative_to(out)): hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in out.rglob("*") if path.is_file()}}
(out / "evidence-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
print("Archived verified evidence:", out)
