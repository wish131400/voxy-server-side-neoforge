"""Compare benchmark record fields to VSS exact (not to generated Minecraft).

Block palette IDs are library-specific and deliberately not compared.
Dry-column fluid heights are not counted as water-surface differences.
"""
import argparse
import collections
import csv
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    results = []
    for seed in (0, 917):
        for scenario in ('tile66_step1', 'batch8_step1', 'batch8_step4', 'batch8_step64'):
            directory = args.run / 'runs' / '0' / str(seed) / scenario
            if not directory.is_dir():
                continue

            def read(version):
                with next((directory / version).glob('*.tsv')).open() as f:
                    return list(csv.DictReader(f, delimiter='\t'))

            exact = read('exact')
            versions = ['display', 'meridian015']
            if (directory / 'preview').is_dir():
                versions.append('preview')
            for version in versions:
                rows = read(version)
                assert len(rows) == len(exact)
                assert [(r['x'], r['z']) for r in rows] == [(r['x'], r['z']) for r in exact]
                delta = [int(a['height']) - int(b['height']) for a, b in zip(rows, exact)]
                wet = [(a, b) for a, b in zip(rows, exact) if int(a['fluid']) != 0 and int(b['fluid']) != 0]
                results.append(dict(seed=seed, scenario=scenario, version=version, points=len(rows),
                    height_different=sum(x != 0 for x in delta), height_abs_max=max(map(abs, delta)),
                    height_abs_mean=sum(map(abs, delta)) / len(rows),
                    fluid_presence_different=sum((int(a['fluid']) != 0) != (int(b['fluid']) != 0) for a, b in zip(rows, exact)),
                    both_wet=len(wet), wet_kind_different=sum(a['fluid'] != b['fluid'] for a, b in wet),
                    wet_surface_different=sum(a['waterY'] != b['waterY'] for a, b in wet),
                    height_delta_hist=dict(collections.Counter(delta).most_common(6))))
    output = args.run / 'output-differences.json'
    output.write_text(json.dumps(results, indent=2))
    print(f'{len(results)} comparisons written to {output}')


if __name__ == '__main__':
    main()
