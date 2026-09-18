"""Alternate two JNI libraries on identical cold/warm display workloads.

Uses the existing StageDisplayGridBench/StageExactGridBench harness with
instrumentation disabled. Each invocation has a fresh JVM/native World.
Checks the full 40-byte output checksum as well as exported visible fields.
"""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--after', type=Path, required=True)
    p.add_argument('--classes', type=Path, required=True)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--document', action='append', required=True, help='name=path')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--rounds', type=int, default=3)
    p.add_argument('--seeds', nargs='+', type=int, default=[0, 917])
    p.add_argument('--exact', action='store_true')
    p.add_argument('--scenes', nargs='+', default=['tile66_step1', 'batch8_step1', 'batch8_step4', 'batch8_step64', 'tile66_warm'])
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    docs = dict(item.split('=', 1) for item in a.document)
    sha = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
    manifest = dict(rounds=a.rounds, seeds=a.seeds, exact=a.exact,
                    libraries={label: dict(path=str(getattr(a, label).resolve()), sha256=sha(getattr(a, label)))
                               for label in ['before', 'after']},
                    documents={name: dict(path=str(Path(path).resolve()), sha256=sha(path)) for name, path in docs.items()},
                    harness={str(path.relative_to(a.classes)): sha(path) for path in a.classes.rglob('*.class')},
                    java=str(a.java), units='microseconds per external requested column; fresh JVM/world per run')
    scenes = a.scenes
    manifest['scenes'] = scenes
    (a.output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    records = []
    with (a.output / 'runs.jsonl').open('w', encoding='utf-8') as log:
        for repeat in range(a.rounds):
            for world, doc in docs.items():
                for seed in a.seeds:
                    for scene in scenes:
                        for label in (['before', 'after'] if repeat % 2 == 0 else ['after', 'before']):
                            dest = a.output / str(repeat) / world / str(seed) / scene / label
                            dest.mkdir(parents=True)
                            cmd = [str(a.java), '--enable-native-access=ALL-UNNAMED', '-Xms256m', '-Xmx1g',
                                   '-cp', str(a.classes), 'StageExactGridBench' if a.exact else 'StageDisplayGridBench',
                                   'vss', str(getattr(a, label).resolve()), doc, str(seed), str(dest), scene]
                            run = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
                            (dest / 'stdout.txt').write_text(run.stdout, encoding='utf-8')
                            (dest / 'stderr.txt').write_text(run.stderr, encoding='utf-8')
                            run.check_returncode()
                            values = run.stdout.strip().split(',')
                            if len(values) != 9:
                                raise ValueError(run.stdout)
                            row = dict(round=repeat, world=world, seed=seed, scene=scene, library=label,
                                       count=int(values[4]), ms=float(values[6]), us=float(values[7]), checksum=values[8])
                            stats = {}
                            for line in run.stderr.splitlines():
                                for phase in ['BEFORE', 'AFTER']:
                                    prefix = f'VSS_QUERY_STATS_{phase}='
                                    if line.startswith(prefix):
                                        stats[phase] = json.loads(line[len(prefix):])
                            if len(stats) == 2:
                                row['fallback'] = stats['AFTER']['displayFallback'] - stats['BEFORE']['displayFallback']
                            records.append(row)
                            log.write(json.dumps(row) + '\n')
                            log.flush()
            print(f'Completed round {repeat + 1}/{a.rounds}', flush=True)
    summary = []
    for world in docs:
        for seed in a.seeds:
            for scene in scenes:
                group = [r for r in records if (r['world'], r['seed'], r['scene']) == (world, seed, scene)]
                identical = len({r['checksum'] for r in group}) == 1
                before = statistics.median(r['us'] for r in group if r['library'] == 'before')
                after = statistics.median(r['us'] for r in group if r['library'] == 'after')
                summary.append(dict(world=world, seed=seed, scene=scene, before_us=before, after_us=after,
                                    speedup=before / after, identical=identical,
                                    fallback=sorted({r['fallback'] for r in group if 'fallback' in r})))
    (a.output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    for row in summary:
        print(row)
    if not all(r['identical'] for r in summary):
        raise AssertionError('Output changed; inspect retained TSVs and checksums before accepting.')
    if any(len(r['fallback']) > 1 for r in summary):
        raise AssertionError('Fallback counts changed; inspect individual runs.')


if __name__ == '__main__':
    main()
