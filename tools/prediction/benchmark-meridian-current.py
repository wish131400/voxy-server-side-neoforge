"""Replay the preserved JNI grid benchmark against the current packaged DLL.

Requires the 20260915 performance-evidence directory (frozen reference and
original harness). Writes a new output directory; never overwrites old evidence.
All measured processes run sequentially, with alternating backend order.
"""
import argparse
import csv
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Java/jdk-21'))
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--extra-document', type=Path)
    parser.add_argument('--native-library', type=Path, help='Candidate DLL override; otherwise extract the packaged library')
    parser.add_argument('--baseline-library', type=Path, help='Frozen previous VSS DLL; compare its displayPoints in the same run')
    parser.add_argument('--stage-breakdown', action='store_true', help='Isolated four-stage ablation plus timed full-path sections')
    args = parser.parse_args()
    if args.rounds < 3:
        parser.error('Use at least three rounds')
    repo = Path(__file__).resolve().parents[2]
    if args.stage_breakdown:
        if args.native_library or args.baseline_library:
            parser.error('Stage breakdown builds its own isolated library and uses the packaged DLL as the reference')
        from display_stage_benchmark import run
        run(args, repo)
        return
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    source = out / 'source'
    source.mkdir()
    classes = out / 'classes'
    classes.mkdir()
    evidence = args.evidence_dir.resolve()
    jar = repo / 'build/libs/vss-0.3-neoforge-1.21.1.jar'
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    with zipfile.ZipFile(jar) as z:
        (out / 'current.dll').write_bytes(z.read('META-INF/vss-natives/windows-x86_64/vss_native_core.dll'))
    if args.native_library:
        shutil.copy2(args.native_library, out / 'current.dll')
    if args.baseline_library:
        shutil.copy2(args.baseline_library, out / 'baseline.dll')
    shutil.copy2(evidence / 'current-comparison/reference.dll', out / 'reference.dll')
    expected_reference = '76ff922e134b4901a348bea530f16f5e703cad7bc873d6d85968eaee2192f52d'
    assert sha(out / 'reference.dll') == expected_reference
    shutil.copy2(evidence / 'current-comparison/shared-document.json', out / 'vanilla.json')
    for name in ['DisplayGridBench', 'ExactGridBench']:
        shutil.copy2(evidence / f'implementation/{name}.java', source / f'{name}.java')
        harness_path = source / f'{name}.java'
        harness_text = harness_path.read_text()
        harness_text = harness_text.replace('} finally { if(vss)',
            '} finally { if(vss) System.err.println(RustWorldgenBackend.decorationQueryStats(h)); if(vss)')
        harness_path.write_text(harness_text)
    preview = (source / 'DisplayGridBench.java').read_text().replace('DisplayGridBench', 'PreviewGridBench').replace('.displayPoints(', '.previewPoints(')
    (source / 'PreviewGridBench.java').write_text(preview)
    shutil.copy2(evidence / 'current-comparison/MeridianNative.java', source / 'MeridianNative.java')
    shutil.copy2(repo / 'src/main/java/dev/xantha/vss/client/prediction/RustWorldgenBackend.java', source / 'RustWorldgenBackend.java')
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '-d', str(classes), *map(str, source.glob('*.java'))], check=True)
    docs = {'vanilla': out / 'vanilla.json'}
    if args.extra_document:
        shutil.copy2(args.extra_document, out / 'captured-derived.json')
        docs['captured-derived'] = out / 'captured-derived.json'
    manifest = dict(time=datetime.datetime.now().isoformat(), jar=str(jar), jar_sha256=sha(jar),
                    native_override=str(args.native_library.resolve()) if args.native_library else None,
                    libraries={v: sha(out / (v + '.dll')) for v in ['current', 'reference'] + (['baseline'] if args.baseline_library else [])},
                    inputs={k: sha(v) for k, v in docs.items()},
                    source={p.name: sha(p) for p in source.glob('*.java')}, rounds=args.rounds,
                    java=str(args.jdk), seeds=[0, 917],
                    method='Single caller. Fresh JVM/world per measurement. 32 disjoint warmup points. JNI time only. VSS 8x8 batches, Meridian sampleGrid. Initialization, Java buffer setup/copy, vegetation, mesh, disk and rendering excluded. API semantics differ; not equivalent-output speedups. Captured input, if present, is derived, and tested only with VSS seed 0, not a live-world reproduction.')
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    fields = ['round', 'world', 'version', 'backend', 'abi', 'seed', 'scenario', 'count', 'init_ms', 'ms', 'us', 'checksum']
    rows = []
    scenarios = ['tile66_step1', 'batch8_step1', 'batch8_step4', 'batch8_step64', 'tile66_warm']
    harness = dict(exact='ExactGridBench', display='DisplayGridBench', preview='PreviewGridBench', reference='ExactGridBench', baseline='DisplayGridBench')
    with (out / 'timings.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for repeat in range(args.rounds):
            for world, doc in docs.items():
                for seed in ([0, 917] if world == 'vanilla' else [0]):
                    for scenario in scenarios:
                        versions = ['exact', 'display', 'reference'] if world == 'vanilla' else ['exact', 'display']
                        if args.baseline_library:
                            versions.append('baseline')
                        if scenario in ['batch8_step4', 'batch8_step64']:
                            versions.append('preview')
                        if (repeat + (seed != 0)) % 2:
                            versions.reverse()
                        for version in versions:
                            dest = out / 'runs' / str(repeat) / world / str(seed) / scenario / version
                            dest.mkdir(parents=True)
                            backend = 'reference' if version == 'reference' else 'vss'
                            library = out / (version + '.dll' if version in ['reference', 'baseline'] else 'current.dll')
                            command = [str(args.jdk / 'bin/java.exe'), '-Xms256m', '-Xmx1g', '-cp', str(classes), harness[version], backend, str(library), str(doc), str(seed), str(dest), scenario]
                            result = subprocess.run(command, capture_output=True, text=True, timeout=180)
                            (dest / 'stdout.txt').write_text(result.stdout)
                            (dest / 'stderr.txt').write_text(result.stderr)
                            result.check_returncode()
                            values = result.stdout.strip().split(',')
                            assert len(values) == 9, result.stdout
                            row = dict(zip(fields, [repeat, world, version, *values]))
                            rows.append(row)
                            writer.writerow(row)
                            stream.flush()
                        print(f'round={repeat + 1} world={world} seed={seed} scenario={scenario}', flush=True)
    summary = []
    for world, seed, scenario in sorted({(r['world'], r['seed'], r['scenario']) for r in rows}):
        entries = [r for r in rows if (r['world'], r['seed'], r['scenario']) == (world, seed, scenario)]
        versions = {}
        for version in sorted({r['version'] for r in entries}):
            group = [r for r in entries if r['version'] == version]
            assert len(group) == args.rounds
            assert len({r['checksum'] for r in group}) == 1, (world, seed, scenario, version, 'unstable output')
            values = [float(r['us']) for r in group]
            versions[version] = dict(median_us=statistics.median(values), min_us=min(values), max_us=max(values),
                                     median_ms=statistics.median(float(r['ms']) for r in group), checksum=group[0]['checksum'])
        summary.append(dict(world=world, seed=int(seed), scenario=scenario, count=int(entries[0]['count']), versions=versions))
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    print('Completed; all within-version repeated checksums stable.', flush=True)


if __name__ == '__main__':
    main()
