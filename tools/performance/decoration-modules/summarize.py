"""Validate complete outputs, random continuation and actual module entry before comparing time."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
args = parser.parse_args()
root = args.directory.resolve()
assert (root/'prediction-complete.txt').read_text().strip() == 'success'
assert not (root/'server-failure.txt').exists()
rows = json.loads((root/'results.json').read_text())
by_mode = {}
for row in rows:
    by_mode.setdefault(row['mode'], []).append(row)
assert 'baseline' in by_mode and 'baseline_tail' in by_mode
controls = [m for m in ('baseline_repeat', 'baseline_tail') if m in by_mode]
oracle = {r['round']: r for r in by_mode['baseline']}
fixture_path = root/'module-fixtures.json'
fixtures = json.loads(fixture_path.read_text()) if fixture_path.exists() else []
module_reports = {}
probe_reports = {}
inputs = {}
for row in rows:
    key = row['mode'], row['round']
    module_file = root/f"module-costs-{key[0]}-{key[1]}.json"
    probe_file = root/f"feature-costs-{key[0]}-{key[1]}.json"
    module_reports[key] = json.loads(module_file.read_text())
    probe_reports[key] = json.loads(probe_file.read_text())
    for p in (module_file, probe_file):
        inputs[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
    assert row['matchesBaseline'] and row['hash'] == oracle[row['round']]['hash'], key
    assert probe_reports[key]['oracleSkipped'] == 0, key
    feature_probes = {f['id']: f for f in probe_reports[key]['features']}
    for name, cost in module_reports[key]['features'].items():
        probe = feature_probes[name]
        assert cost['calls'] == probe['biomePassed'], (key, name, 'terminal coverage')
        assert cost['writes'] == probe['writes'], (key, name, 'write coverage')
        assert cost['fallbacks'] == 0, (key, name, 'unexpected fallback')
    baseline = module_reports.get(('baseline', row['round']))
    assert baseline is not None
    assert module_reports[key]['randomTail'] == baseline['randomTail'], key
    assert module_reports[key]['randomChecks'] == baseline['randomChecks'], key
if fixtures:
    assert len(fixtures) == 360
    assert all(f['matched'] and f['moduleEntered'] for f in fixtures)

summary = {'scope': 'Full isolated prediction generation through final Voxy columns/boxes. No rendering, disk I/O, scheduling or network. Feature and module sub-times are nested.',
           'fixtures': len(fixtures), 'fullRegionsIncludingWarmups': len(rows),
           'allOutputsAndRandomContinuationsMatched': True, 'controls': controls, 'modes': {}}
metrics = ('wallMs', 'terrainMs', 'decorationMs', 'encodingMs', 'threadCpuMs', 'processCpuMs',
           'javaThreadAllocatedMiB', 'surfaceCalls', 'surfacePoints', 'biomeQueries', 'gcMs')
for mode, selected in by_mode.items():
    formal = [r for r in selected if not r['warmup']]
    means = {m: statistics.mean(r[m] for r in formal) for m in metrics}
    pairs = []
    for row in formal:
        other = [r for m in controls for r in by_mode[m] if r['round'] == row['round']]
        control_wall = statistics.mean(r['wallMs'] for r in other)
        pairs.append({'round': row['round'], 'wallMs': row['wallMs'], 'controlWallMs': control_wall,
                      'reductionPercent': (1-row['wallMs']/control_wall)*100,
                      'eachControlReductionPercent': {r['mode']: (1-row['wallMs']/r['wallMs'])*100 for r in other}})
    features = {}
    for row in formal:
        key = mode, row['round']
        for name, cost in module_reports[key]['features'].items():
            target = features.setdefault(name, {})
            for k, v in cost.items(): target[k] = target.get(k, 0) + v/len(formal)
    if mode in ('templates', 'pages', 'compact'):
        assert sum(f['calls'] for f in features.values()) > 0, f'Unreached module: {mode}'
        assert sum(f['writes'] for f in features.values()) > 0, f'Module never wrote: {mode}'
        if mode != 'templates':
            assert sum(f['batchCalls'] for f in features.values()) > 0, f'Unreached batch: {mode}'
    tracked = {'exactMs': 0, 'biomeMs': 0, 'displayMs': 0, 'allDiskFeatureMs': 0}
    for row in formal:
        for f in probe_reports[mode, row['round']]['features']:
            tracked['exactMs'] += f['exactNanos']/1e6/len(formal)
            tracked['biomeMs'] += f['biomeNanos']/1e6/len(formal)
            tracked['displayMs'] += f['displayNanos']/1e6/len(formal)
            if f['type'] == 'minecraft:disk': tracked['allDiskFeatureMs'] += f['nanos']/1e6/len(formal)
    summary['modes'][mode] = {'formalRegions': len(formal), 'means': means, 'nestedQueries': tracked,
        'meanPairedWallReductionPercent': statistics.mean(p['reductionPercent'] for p in pairs),
        'meanReductionAgainstEachControlPercent': {
            m: statistics.mean(p['eachControlReductionPercent'][m] for p in pairs) for m in controls},
        'moduleFeatures': features, 'pairs': pairs}
summary['controlSpreadPercentByRound'] = {
    str(r['round']): (max(next(o['wallMs'] for o in by_mode[m] if o['round']==r['round']) for m in controls)
                     /min(next(o['wallMs'] for o in by_mode[m] if o['round']==r['round']) for m in controls)-1)*100
    for r in by_mode[controls[0]] if not r['warmup']}
summary['inputHashes'] = inputs
(root/'module-summary.json').write_text(json.dumps(summary, indent=2)+'\n', encoding='utf-8')
print('Validated regions:', len(rows), 'fixtures:', len(fixtures))
for name, entry in summary['modes'].items():
    m = entry['means']
    print(name, 'wallMs', round(m['wallMs'], 2), 'decorationMs', round(m['decorationMs'], 2),
          'points', m['surfacePoints'], 'allocationMiB', round(m['javaThreadAllocatedMiB'], 2),
          'pairedReduction', round(entry['meanPairedWallReductionPercent'], 3),
          'moduleCalls', round(sum(v['calls'] for v in entry['moduleFeatures'].values()), 2),
          'batches', round(sum(v['batchCalls'] for v in entry['moduleFeatures'].values()), 2))
print('Control spread:', summary['controlSpreadPercentByRound'])
