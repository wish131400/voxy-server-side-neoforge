"""Prepare exact kernel parity inputs from validated, copied Minecraft captures.

Only Java noise/random results are used as expected output, never the old DLL grid.
"""
import gzip
import json
from pathlib import Path
import struct
import sys

root, output = map(Path, sys.argv[1:3])
output.parent.mkdir(parents=True, exist_ok=True)
count = 0
with output.open("w", encoding="utf8") as out:
    for folder in sorted(root.glob("capture-*")):
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf8"))
        registry = json.loads((folder / "registries.json").read_text(encoding="utf8"))
        generator = json.loads((folder / "generator.json").read_text(encoding="utf8"))
        settings = generator.get("settings", {})
        if not isinstance(settings, dict):
            raise ValueError(f"{folder}: expanded noise settings required")
        kind = 0 if settings.get("legacy_random_source", False) else 1
        with gzip.open(folder / "noise.jsonl.gz", "rt", encoding="utf8") as stream:
            for line in stream:
                row = json.loads(line)
                for name, params in registry["noises"].items():
                    expected = struct.unpack(">Q", struct.pack(">d", float.fromhex(row[name])))[0]
                    out.write(json.dumps({"type": "captured_noise", "capture": folder.name,
                        "seed": manifest["seed"], "kind": kind, "name": name,
                        "first": params["firstOctave"], "amplitudes": params["amplitudes"],
                        "point": [row[axis] for axis in ("x", "y", "z")],
                        "expected": format(expected, "x")}) + "\n")
                    count += 1
        with gzip.open(folder / "random.jsonl.gz", "rt", encoding="utf8") as stream:
            for line in stream:
                row = json.loads(line)
                out.write(json.dumps({"type": "captured_random", "capture": folder.name,
                    "seed": manifest["seed"], "x": manifest["originX"] & ~15,
                    "z": manifest["originZ"] & ~15, **row}) + "\n")
                count += 1
print(json.dumps({"records": count, "output": str(output)}, ensure_ascii=False))
