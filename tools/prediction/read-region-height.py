"""Read stored terrain heights without opening or modifying a Minecraft world."""
import argparse
import io
import json
from pathlib import Path
import sys
import zlib

parser = argparse.ArgumentParser()
parser.add_argument("world", type=Path)
parser.add_argument("--nbt-library", type=Path)
parser.add_argument("--points", nargs="+", default=["71,217", "256,256", "-17,35"])
args = parser.parse_args()
if args.nbt_library:
    sys.path.insert(0, str(args.nbt_library))
import nbtlib

data = nbtlib.load(args.world / "level.dat")["Data"]
print(json.dumps({"world": str(data["LevelName"]), "seed": int(data["WorldGenSettings"]["seed"]),
                  "player": data.get("Player", {}).get("Pos", []).unpack() if "Player" in data else None}, ensure_ascii=False))
for point in args.points:
    x, z = map(int, point.split(","))
    cx, cz = x >> 4, z >> 4
    path = args.world / "region" / f"r.{cx >> 5}.{cz >> 5}.mca"
    if not path.exists():
        continue
    with path.open("rb") as region:
        region.seek(4 * ((cx & 31) + (cz & 31) * 32))
        sector = int.from_bytes(region.read(3), "big")
        if not sector:
            continue
        region.seek(sector * 4096)
        size = int.from_bytes(region.read(4), "big")
        compression = region.read(1)[0]
        raw = region.read(size - 1)
    if compression == 2:
        raw = zlib.decompress(raw)
    elif compression != 3:
        raise ValueError(f"Unsupported compression: {compression}")
    chunk = nbtlib.File.parse(io.BytesIO(raw))
    index = (z & 15) * 16 + (x & 15)
    values = {}
    for name, array in chunk["Heightmaps"].items():
        bits = 9
        packed = int(array[index // (64 // bits)]) & ((1 << 64) - 1)
        values[name] = ((packed >> ((index % (64 // bits)) * bits)) & ((1 << bits) - 1)) + int(chunk["yPos"]) * 16
    print(json.dumps({"x": x, "z": z, "heights": values}))
