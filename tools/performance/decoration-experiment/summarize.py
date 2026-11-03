"""Summarize paired regions; compare to warmed baseline controls, not the first JIT-heavy arm."""
import argparse
import hashlib
import json
import pathlib
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=pathlib.Path)
args = parser.parse_args()
root = args.directory.resolve()
assert (root / 'prediction-complete.txt').is_file(), 'Experiment incomplete'
assert not (root / 'server-failure.txt').exists(), 'Runtime reported a failure'
rows = [r for r in json.loads((root / 'results.json').read_text()) if not r['warmup']]
env = json.loads((root / 'experiment-environment.json').read_text())
groups = {mode: [r for r in rows if r['mode'] == mode] for mode in dict.fromkeys(r['mode'] for r in rows)}
assert all(len(g) == env['rounds'] for g in groups.values()), 'Incomplete arm'
controls = [m for m in groups if m in ('baseline_repeat', 'baseline_tail')]
if not controls:
    controls = ['baseline']
assert all(r['matchesBaseline'] for m in controls for r in groups[m]), 'Baseline is not reproducible'
control_by_round = {i: statistics.mean(r['wallMs'] for m in controls for r in groups[m] if r['round'] == i)
                    for i in range(env['rounds'])}
control_cpu_by_round = {i: statistics.mean(r['processCpuMs'] for m in controls for r in groups[m] if r['round'] == i)
                        for i in range(env['rounds'])}
summary = {'controls': controls, 'environment': env, 'arms': {}}
keys = ['wallMs', 'decorationMs', 'terrainMs', 'encodingMs', 'processCpuMs', 'threadCpuMs',
        'javaThreadAllocatedMiB', 'heapUsedMiB', 'surfaceCalls', 'surfacePoints', 'biomeCalls', 'scans']
for mode, group in groups.items():
    result = {key: statistics.mean(r[key] for r in group) for key in keys}
    result['wallReductionPercent'] = 100 * (1-statistics.mean(r['wallMs']/control_by_round[r['round']] for r in group))
    result['cpuReductionPercent'] = 100 * (1-statistics.mean(r['processCpuMs']/control_cpu_by_round[r['round']] for r in group))
    result['wallReductionByRegionPercent'] = [100*(1-r['wallMs']/control_by_round[r['round']]) for r in group]
    result['matchesAllOutputs'] = all(r['matchesBaseline'] for r in group)
    result['totalGcMs'] = sum(r['gcMs'] for r in group)
    summary['arms'][mode] = result

manifest = json.loads((root / 'input-manifest.json').read_text())
resource = pathlib.Path('META-INF/vss-natives/windows-x86_64/vss_native_core.dll')
expected = manifest.get('experimentDllSha256', manifest['resources']['src/main/resources/' + resource.as_posix()])
actual = hashlib.sha256((root / 'gradle/resources/main' / resource).read_bytes()).hexdigest()
assert actual == expected, 'Runtime DLL differs from snapshot'
summary['runtimeDllSha256'] = actual
for name, digest in manifest['inputs'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, 'Input changed: ' + name
(root/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
for mode, a in summary['arms'].items():
    print(f"{mode:16s} wall={a['wallMs']:8.2f}ms decoration={a['decorationMs']:8.2f}ms "
          f"CPU={a['processCpuMs']:8.2f}ms wall_reduction={a['wallReductionPercent']:6.2f}% "
          f"cpu_reduction={a['cpuReductionPercent']:6.2f}% exact={a['matchesAllOutputs']}")
