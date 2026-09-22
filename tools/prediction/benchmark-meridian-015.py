"""Standalone same-input JNI comparison of packaged VSS and Meridian 0.1.5.

Reuses the preserved grid harness, without the old reference DLL or JAR paths.
Output is deliberately a stage benchmark, not an equivalent-quality/FPS claim.
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
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--vss-jar', type=Path, required=True)
    p.add_argument('--meridian-jar', type=Path, required=True)
    p.add_argument('--previous-meridian-jar', type=Path)
    p.add_argument('--preserved-run', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--rounds', type=int, default=5)
    p.add_argument('--scenarios', nargs='+', default=['tile66_step1', 'batch8_step1', 'batch8_step4', 'batch8_step64', 'tile66_warm'],
                   choices=['tile66_step1', 'tile66_chunked', 'batch8_step1', 'batch8_step4', 'batch8_step64', 'tile66_warm'])
    p.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Java/jdk-21'))
    a = p.parse_args()
    if a.rounds < 3:
        p.error('At least three rounds required')
    repo = Path(__file__).resolve().parents[2]
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    src = out / 'source'
    src.mkdir()
    classes = out / 'classes'
    classes.mkdir()
    sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()
    jars = {'vss': a.vss_jar.resolve(), 'meridian015': a.meridian_jar.resolve()}
    if a.previous_meridian_jar:
        jars['meridian014'] = a.previous_meridian_jar.resolve()
    for name, jar in jars.items():
        entry = ('META-INF/vss-natives/windows-x86_64/vss_native_core.dll' if name == 'vss'
                 else 'META-INF/meridian-natives/windows-x86_64/meridian_native.dll')
        with zipfile.ZipFile(jar) as z:
            (out / f'{name}.dll').write_bytes(z.read(entry))
    for name in ('DisplayGridBench', 'ExactGridBench', 'MeridianNative'):
        shutil.copy2(a.preserved_run / 'source' / f'{name}.java', src)
    preview = (src / 'DisplayGridBench.java').read_text().replace('DisplayGridBench', 'PreviewGridBench').replace('.displayPoints(', '.previewPoints(')
    (src / 'PreviewGridBench.java').write_text(preview)
    shutil.copy2(repo / 'src/main/java/dev/xantha/vss/client/prediction/RustWorldgenBackend.java', src)
    shutil.copy2(a.preserved_run / 'vanilla.json', out / 'vanilla.json')
    subprocess.run([str(a.jdk / 'bin/javac.exe'), '-d', str(classes), *map(str, src.glob('*.java'))], check=True)
    manifest = dict(time=datetime.datetime.now().isoformat(), rounds=a.rounds, seeds=[0, 917], scenarios=a.scenarios,
                    jars={k: dict(path=str(v), sha256=sha(v)) for k, v in jars.items()},
                    dlls={k: sha(out / f'{k}.dll') for k in jars}, input_sha256=sha(out / 'vanilla.json'),
                    sources={f.name: sha(f) for f in src.glob('*.java')},
                    method='Sequential fresh JVM/world per case, alternating backend order. 32 disjoint warmup columns. JNI calls only, one caller. VSS <=64-point batches; Meridian regular sampleGrid. Initialization measured separately. Java sample cache, vegetation placement, meshing, disk I/O and rendering excluded. Different output policies: NOT equivalent-output speedups. tile66_warm is native repeated query, not game cache loading.')
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    fields = ['round', 'version', 'backend', 'abi', 'seed', 'scenario', 'count', 'init_ms', 'ms', 'us', 'checksum']
    rows = []
    with (out / 'timings.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for repeat in range(a.rounds):
            for seed in (0, 917):
                for scenario in a.scenarios:
                    versions = ['exact', 'display', 'meridian015']
                    if 'meridian014' in jars:
                        versions.append('meridian014')
                    if scenario in ('batch8_step4', 'batch8_step64'):
                        versions.append('preview')
                    if (repeat + (seed != 0)) % 2:
                        versions.reverse()
                    for version in versions:
                        reference = version.startswith('meridian')
                        harness = ('ExactGridBench' if reference or version == 'exact' else
                                   'PreviewGridBench' if version == 'preview' else 'DisplayGridBench')
                        library = out / (f'{version}.dll' if reference else 'vss.dll')
                        dest = out / 'runs' / str(repeat) / str(seed) / scenario / version
                        dest.mkdir(parents=True)
                        cmd = [str(a.jdk / 'bin/java.exe'), '-Xms256m', '-Xmx1g', '-cp', str(classes),
                               harness, 'reference' if reference else 'vss', str(library),
                               str(out / 'vanilla.json'), str(seed), str(dest), scenario]
                        result = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
                        (dest / 'stdout.txt').write_text(result.stdout)
                        (dest / 'stderr.txt').write_text(result.stderr)
                        result.check_returncode()
                        values = result.stdout.strip().split(',')
                        assert len(values) == 9, result.stdout
                        if reference:
                            assert int(values[1]) == (15 if version == 'meridian015' else 12), ('Unexpected Meridian ABI', values)
                        row = dict(zip(fields, [repeat, version, *values]))
                        rows.append(row)
                        writer.writerow(row)
                        f.flush()
                    print(f'round={repeat+1} seed={seed} scenario={scenario}', flush=True)
    summary = []
    for seed, scenario in sorted({(r['seed'], r['scenario']) for r in rows}):
        versions = {}
        entries = [r for r in rows if (r['seed'], r['scenario']) == (seed, scenario)]
        for version in sorted({r['version'] for r in entries}):
            group = [r for r in entries if r['version'] == version]
            assert len(group) == a.rounds
            assert len({r['checksum'] for r in group}) == 1, ('Unstable output', seed, scenario, version)
            values = [float(r['us']) for r in group]
            versions[version] = dict(median_us=statistics.median(values), min_us=min(values), max_us=max(values),
                                     median_ms=statistics.median(float(r['ms']) for r in group), checksum=group[0]['checksum'])
        summary.append(dict(seed=int(seed), scenario=scenario, count=int(entries[0]['count']), versions=versions))
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    print('Completed. Repeated output checksums stable in every case.', flush=True)


if __name__ == '__main__':
    main()
