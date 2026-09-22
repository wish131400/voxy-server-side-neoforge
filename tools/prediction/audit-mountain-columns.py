"""Compare a read-only live prediction snapshot with saved block occupancy."""
import argparse
import io
import json
import re
import sys
import zlib
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('world', type=Path)
p.add_argument('snapshot', type=Path)
p.add_argument('output', type=Path)
p.add_argument('--nbt-library', type=Path)
a = p.parse_args()
if a.nbt_library:
    sys.path.insert(0, str(a.nbt_library))
import nbtlib

chunks = {}
def load(cx, cz):
    key = cx, cz
    if key in chunks:
        return chunks[key]
    path = a.world / 'region' / f'r.{cx >> 5}.{cz >> 5}.mca'
    if not path.exists():
        chunks[key] = None
        return None
    with path.open('rb') as f:
        f.seek(4 * ((cx & 31) + 32 * (cz & 31)))
        offset = int.from_bytes(f.read(3), 'big')
        if not offset:
            chunks[key] = None
            return None
        f.seek(offset * 4096)
        length = int.from_bytes(f.read(4), 'big')
        compression = f.read(1)[0]
        raw = f.read(length - 1)
    if compression == 2:
        raw = zlib.decompress(raw)
    elif compression != 3:
        raise ValueError(f'Unsupported region compression {compression}')
    chunk = nbtlib.File.parse(io.BytesIO(raw))
    result = {}
    for s in chunk['sections']:
        if 'block_states' not in s:
            continue
        b = s['block_states']
        palette = [str(v['Name']) for v in b['palette']]
        bits = max(4, (len(palette) - 1).bit_length())
        result[int(s['Y'])] = palette, bits, b.get('data')
    chunks[key] = result
    return result

def state(sections, x, y, z):
    if (y >> 4) not in sections:
        return 'minecraft:air'
    palette, bits, words = sections[y >> 4]
    if len(palette) == 1:
        return palette[0]
    index = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
    count = 64 // bits
    word = int(words[index // count]) & ((1 << 64) - 1)
    return palette[(word >> ((index % count) * bits)) & ((1 << bits) - 1)]

samples = {}
step = None
for line in a.snapshot.read_text(encoding='utf-8').splitlines():
    m = re.search(r' step=(\d+)', line)
    if m:
        step = int(m[1])
    m = re.match(r'(-?\d+),(-?\d+) ClientColumnSample\[surfaceY=(-?\d+),.*flags=(\d+),', line)
    if m and step == 1:
        x, z, height, flags = map(int, m.groups())
        samples[x, z] = height, flags

rows = []
for (x, z), (height, flags) in sorted(samples.items()):
    sections = load(x >> 4, z >> 4)
    if sections is None:
        continue
    # Exact air only: water, ores, and modded blocks all remain occupied.
    runs = []
    start = None
    for y in range(-64, 321):
        occupied = y < 320 and state(sections, x, y, z) not in (
            'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air')
        if occupied and start is None:
            start = y
        if not occupied and start is not None:
            runs.append([start, y]); start = None
    gaps = [[runs[i][1], runs[i+1][0]] for i in range(len(runs)-1)
            if runs[i+1][0] - runs[i][1] >= 8 and runs[i][1] >= 60
            and runs[i+1][0] < height]
    if gaps:
        rows.append(dict(x=x,z=z,predictedTop=height,flags=flags,
                         occupiedRuns=runs,exteriorGaps=gaps))
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(dict(sampledFineColumns=len(samples),
    availableChunks=sum(v is not None for v in chunks.values()),
    columnsWithAboveGroundGaps=len(rows),columns=rows), indent=2), encoding='utf-8')
print(json.dumps(dict(sampledFineColumns=len(samples),columnsWithAboveGroundGaps=len(rows),
    largestExamples=sorted(rows,key=lambda r:max(hi-lo for lo,hi in r['exteriorGaps']),reverse=True)[:6])))
