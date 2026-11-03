"""Summarize paired feature experiments, including unsuccessful correctness checks."""
from pathlib import Path
import argparse
import json
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
args = parser.parse_args()
root = args.directory
rows = json.loads((root / 'results.json').read_text())
assert (root / 'prediction-complete.txt').read_text().strip() == 'success'
assert not (root / 'server-failure.txt').exists()
by_mode = {}
for row in rows:
    by_mode.setdefault(row['mode'], []).append(row)
controls = [name for name in ('baseline_repeat', 'baseline_tail') if name in by_mode]
assert controls, 'A repeated baseline is needed to distinguish JIT/order effects.'
metrics = ('wallMs', 'terrainMs', 'decorationMs', 'javaThreadAllocatedMiB',
           'surfaceCalls', 'surfacePoints', 'biomeQueries')
report = {'scope': 'Instrumented offline generation, no rendering; paired control is '
                   'the mean of repeated baselines at the same coordinates.',
          'controls': controls, 'modes': {}}
for mode, selected in by_mode.items():
    formal = [r for r in selected if not r['warmup']]
    pairs = []
    for row in formal:
        control = [r for name in controls for r in by_mode[name]
                   if r['round'] == row['round']]
        assert len(control) == len(controls)
        means = {key: statistics.mean(r[key] for r in control) for key in metrics}
        pairs.append({'round': row['round'], 'matchesBaseline': row['matchesBaseline'],
                      'wallReductionPercent': (1-row['wallMs']/means['wallMs'])*100,
                      'decorationReductionPercent': (1-row['decorationMs']/means['decorationMs'])*100,
                      'wallMs': row['wallMs'], 'controlWallMs': means['wallMs'],
                      'individualControls': {r['mode']: {
                          'wallMs': r['wallMs'],
                          'wallReductionPercent': (1-row['wallMs']/r['wallMs'])*100
                      } for r in control},
                      'surfacePoints': row['surfacePoints'],
                      'controlSurfacePoints': means['surfacePoints']})
    entry = {'formalRounds': len(formal),
             'means': {key: statistics.mean(r[key] for r in formal) for key in metrics},
             'mismatchesIncludingWarmups': [r['round'] for r in selected if not r['matchesBaseline']],
             'meanPairedWallReductionPercent': statistics.mean(p['wallReductionPercent'] for p in pairs),
             'meanPairedDecorationReductionPercent': statistics.mean(p['decorationReductionPercent'] for p in pairs),
             'meanWallReductionAgainstEachControlPercent': {
                 name: statistics.mean(p['individualControls'][name]['wallReductionPercent'] for p in pairs)
                 for name in controls},
             'pairs': pairs}
    probes = [json.loads((root/f"feature-costs-{mode}-{r['round']}.json").read_text()) for r in formal]
    entry['meanOracleSkipped'] = statistics.mean(p['oracleSkipped'] for p in probes)
    entry['meanRepeatedJavaExactPoints'] = statistics.mean(p['repeatedJavaExactPoints'] for p in probes)
    report['modes'][mode] = entry
control_formal = {name: {r['round']: r for r in by_mode[name] if not r['warmup']} for name in controls}
report['controlWallSpreadPercentByRound'] = {
    str(round_number): (max(control_formal[name][round_number]['wallMs'] for name in controls)
                        / min(control_formal[name][round_number]['wallMs'] for name in controls)-1)*100
    for round_number in control_formal[controls[0]]}
report['caution'] = 'Large differences between unchanged controls make a paired mean unreliable; inspect each control.'
(root/'comparison-summary.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
