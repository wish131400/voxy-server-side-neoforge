"""Archive the dependency investigation with paired controls and all output checks."""
from pathlib import Path
import argparse,collections,hashlib,json,statistics

project=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);args=parser.parse_args()
report={'scope':'Windows NeoForge 1.21.1, seed 42, five cold 16x16 regions plus two warmups per mode; no rendering.',
        'note':'Each experiment has its own repeated controls. Do not compare absolute milliseconds across experiments.',
        'experiments':{},'trace':[]}
names=['noise-reuse','subtree-reuse','lattice-reuse','environment-reuse','arithmetic-plan']
for name in names:
    suffix='-b' if name=='arithmetic-plan' else ''
    root=project/'build'/f'decoration-{name}-20261101{suffix}'
    if not (root/'prediction-complete.txt').exists():raise SystemExit(f'Incomplete: {root}')
    rows=json.loads((root/'results.json').read_text())
    for r in rows:
        if not r['matchesBaseline']:raise SystemExit(f'Output mismatch: {root} {r["mode"]} {r["round"]}')
    controls=['baseline_repeat','baseline_tail'];summary={}
    for mode in dict.fromkeys(r['mode'] for r in rows):
        formal=[r for r in rows if r['mode']==mode and not r['warmup']]
        metrics=['wallMs','terrainMs','decorationMs','threadCpuMs','processCpuMs','surfacePoints','javaThreadAllocatedMiB']
        reductions={key:[] for key in metrics};individual={key:[] for key in controls}
        for r in formal:
            paired=[x for x in rows if x['mode'] in controls and x['round']==r['round']]
            assert len(paired)==2
            for key in metrics:
                base=statistics.mean(x[key] for x in paired)
                reductions[key].append((1-r[key]/base)*100)
            for c in paired:individual[c['mode']].append((1-r['wallMs']/c['wallMs'])*100)
        caches=[]
        for r in formal:
            p=root/f'reuse-decoration-{mode}-{r["round"]}.json'
            if p.exists():caches.append(json.loads(p.read_text()))
        summary[mode]={'means':{k:statistics.mean(r[k] for r in formal) for k in metrics},
            'pairedMeanReductionPercent':{k:statistics.mean(v) for k,v in reductions.items()},
            'wallReductionAgainstEachControl':{k:statistics.mean(v) for k,v in individual.items()},
            'cacheMeans':{k:statistics.mean(c[k] for c in caches) for k in caches[0]} if caches else {},
            'formalCount':len(formal),'allOutputMatches':True}
    spread=[]
    for i in range(5):
        controls_i=[r['wallMs'] for r in rows if r['mode'] in controls and r['round']==i]
        spread.append((max(controls_i)/min(controls_i)-1)*100)
    dll=root/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'
    report['experiments'][name]={'directory':root.relative_to(project).as_posix(),
        'rows':rows,'summary':summary,'controlWallSpreadPercentByRound':spread,
        'dllSha256':hashlib.sha256(dll.read_bytes()).hexdigest()}
root=project/'build/decoration-dependencies-20261101'
for p in sorted(root.glob('dependency-decoration-baseline-*.json')):
    d=json.loads(p.read_text());kinds=collections.defaultdict(lambda:{'calls':0,'dispatch':0,'horizontalHits':0,'memoHits':0})
    for n in d['nodes']:
        k=kinds[n['kind']];k['calls']+=sum(n['calls']);k['dispatch']+=sum(n['dispatch'])
        k['horizontalHits']+=n['horizontalHits'];k['memoHits']+=n['memoHits']
    sampled=sum(n['sampled'] for n in d['noises']);unique=sum(n['unique'] for n in d['noises'])
    report['trace'].append({'file':p.relative_to(project).as_posix(),
        'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'normalNoiseCalls':sum(n['calls'] for n in d['noises']),
        'sampleRate':d['sampleRate'],'sampled':sampled,'uniqueSampledInputs':unique,
        'repeatedSampledInputPercent':(1-unique/sampled)*100,'dropped':d['dropped'],
        'nodeKinds':dict(kinds),'raw':d})
manifest=json.loads((root/'input-manifest.json').read_text())
unchanged={}
for group in ['sources','resources']:
    changed=[name for name,h in manifest[group].items() if hashlib.sha256((project/name).read_bytes()).hexdigest()!=h]
    unchanged[group]={'files':len(manifest[group]),'changedSinceExperimentSnapshot':changed}
    assert not changed,changed
native=json.loads((root/'native-source-manifest.json').read_text())
changed=[name for name,h in native.items() if hashlib.sha256((project/'tools/rust/vss-native-core'/name).read_bytes()).hexdigest()!=h]
assert not changed,changed
unchanged['native']={'files':len(native),'changedSinceExperimentSnapshot':changed}
report['productionUnchanged']=unchanged
testlog=project/'build/decoration-arithmetic-plan-20261101-b/differential-tests.log'
assert '3 passed; 0 failed' in testlog.read_text(encoding='utf-8')
report['differentialChecks']={'log':testlog.relative_to(project).as_posix(),
    'sha256':hashlib.sha256(testlog.read_bytes()).hexdigest(),'passed':3,'failed':0}
patch=Path(__file__).with_name('prototypes')/'dependency-candidate-20261101.patch'
report['archivedCandidatePatch']={'path':patch.relative_to(project).as_posix(),
    'sha256':hashlib.sha256(patch.read_bytes()).hexdigest()}
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({name:data['summary'] for name,data in report['experiments'].items()},ensure_ascii=False,indent=2))
