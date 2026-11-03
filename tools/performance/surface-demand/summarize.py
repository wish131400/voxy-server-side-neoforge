"""Compare same-coordinate full-path runs; invalid variants are never speed claims."""
from pathlib import Path
import argparse,hashlib,json,statistics
p=argparse.ArgumentParser();p.add_argument('directories',nargs='+',type=Path);p.add_argument('--rejected',type=Path);p.add_argument('--trace',type=Path);p.add_argument('--paired',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
report={'scope':'Single-thread full NeoForge runtime generation; terrain, decorations, settling and Voxy CPU inputs. Counters are a separate diagnostic run; no render/GPU, disk, queues or network.','runs':[]}
prior=json.loads(Path('tools/performance/climate-sharing/results-20261102.json').read_text())['outputHashes']
def digest(f):return hashlib.sha256(f.read_bytes()).hexdigest()
def load(d):
    assert (d/'prediction-complete.txt').read_text().strip()=='success',d
    assert not (d/'server-failure.txt').exists(),d
    return json.loads((d/'results.json').read_text())
def artifact_hashes(d):
    return {p.relative_to(d).as_posix():digest(p) for folder in ['main-java','harness-java','native/src'] for p in (d/folder).rglob('*') if p.is_file()}
fields=['wallMs','terrainMs','decorationMs','encodingMs','threadCpuMs','javaThreadAllocatedMiB','gcMs']
for d in a.directories:
    rows=load(d);formal=[r.copy() for r in rows if not r['warmup']]
    assert all(r['matchesBaseline'] for r in rows),[(r['mode'],r['round']) for r in rows if not r['matchesBaseline']]
    assert all(r['hash']==prior[str(r['round'])] for r in formal),d
    for r in formal:
        f=json.loads((d/f'feature-costs-{r["mode"]}-{r["round"]}.json').read_text())['features']
        for name,key in [('exactMs','exactNanos'),('javaBiomeMs','biomeNanos'),('displayMs','displayNanos')]:r[name]=sum(x[key] for x in f)/1e6
    by={(r['mode'],r['round']):r for r in formal};modes={}
    for mode in dict.fromkeys(r['mode'] for r in formal):
        selected=[r for r in formal if r['mode']==mode]
        means={k:statistics.mean(r[k] for r in selected) for k in fields+['exactMs','javaBiomeMs','displayMs']}
        if mode not in ['baseline','baseline_tail','cross','cross_tail']:
            control=[(by['cross',r['round']]['wallMs']+by['cross_tail',r['round']]['wallMs'])/2 for r in selected]
            means['wallReductionVsCrossPct']=100*(1-sum(r['wallMs'] for r in selected)/sum(control))
            means['pairedReductionsVsCrossPct']=[100*(1-r['wallMs']/c) for r,c in zip(selected,control)]
        modes[mode]=means
    report['runs'].append({'directory':str(d),'environment':json.loads((d/'experiment-environment.json').read_text()),'modes':modes,'formalRows':formal,'allOutputHashesMatch':True,'dllSha256':digest(d/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'),'snapshotSha256':artifact_hashes(d),'evidenceSha256':{p.name:digest(p) for p in d.glob('feature-costs-*.json')}})
report['pooled']={}
report['pooledUse']='Exploratory only: the second run has a slow trailing cross control. Do not use this pooled reduction as the performance conclusion.'
allrows=[r for run in report['runs'] for r in run['formalRows']]
cross=[r for r in allrows if r['mode'] in ['cross','cross_tail']]
base=[r for r in allrows if r['mode'] in ['baseline','baseline_tail']]
report['controls']={n:{k:statistics.mean(r[k] for r in v) for k in fields+['exactMs','javaBiomeMs','displayMs']} for n,v in [('baseline',base),('cross',cross)]}
for mode in ['cross_ore','cross_demand','cross_demand_ore']:
    selected=[r for r in allrows if r['mode']==mode]
    means={k:statistics.mean(r[k] for r in selected) for k in fields+['exactMs','javaBiomeMs','displayMs']}
    means['wallReductionVsCrossPct']=100*(1-means['wallMs']/report['controls']['cross']['wallMs'])
    means['exactReductionVsCrossPct']=100*(1-means['exactMs']/report['controls']['cross']['exactMs'])
    means['wallReductionVsBaselinePct']=100*(1-means['wallMs']/report['controls']['baseline']['wallMs'])
    report['pooled'][mode]=means
if a.paired:
    rows=load(a.paired);formal=[r.copy() for r in rows if not r['warmup']]
    assert all(r['matchesBaseline'] for r in rows),a.paired
    assert all(r['hash']==prior[str(r['round'])] for r in formal),a.paired
    for r in formal:
        f=json.loads((a.paired/f'feature-costs-{r["mode"]}-{r["round"]}.json').read_text())['features']
        for name,key in [('exactMs','exactNanos'),('javaBiomeMs','biomeNanos'),('displayMs','displayNanos')]:r[name]=sum(x[key] for x in f)/1e6
    by={(r['mode'],r['round']):r for r in formal};means={}
    for mode in dict.fromkeys(r['mode'] for r in formal):
        chosen=[r for r in formal if r['mode']==mode]
        means[mode]={k:statistics.mean(r[k] for r in chosen) for k in fields+['exactMs','javaBiomeMs','displayMs']}
        for r in chosen:
            c=by['cross',r['round']]
            for k in ['surfacePoints','surfaceCalls','biomeQueries','plantBlocks','featureBoxes']:assert r[k]==c[k],(mode,r['round'],k)
        if mode not in ['baseline','cross']:
            means[mode]['pairedReductionsVsCrossPct']=[100*(1-r['wallMs']/by['cross',r['round']]['wallMs']) for r in chosen]
    for mode in ['cross_demand','cross_demand_ore']:
        means[mode]['wallReductionVsCrossPct']=100*(1-means[mode]['wallMs']/means['cross']['wallMs'])
        means[mode]['exactReductionVsCrossPct']=100*(1-means[mode]['exactMs']/means['cross']['exactMs'])
        means[mode]['wallReductionVsBaselinePct']=100*(1-means[mode]['wallMs']/means['baseline']['wallMs'])
    report['primaryInterleavedRun']={'directory':str(a.paired),'environment':json.loads((a.paired/'experiment-environment.json').read_text()),'modes':means,'formalRows':formal,'dllSha256':digest(a.paired/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'),'snapshotSha256':artifact_hashes(a.paired),'evidenceSha256':{p.name:digest(p) for p in a.paired.glob('feature-costs-*.json')}}
if a.rejected:
    rows=load(a.rejected);failed=[r for r in rows if not r['matchesBaseline']]
    report['rejectedPrototype']={'directory':str(a.rejected),'reason':'Removed earlier-neighbour biome queries changed last-leaf tie history; output mismatch. Performance excluded.','mismatches':failed,'allFormalRows':[r for r in rows if not r['warmup']]}
if a.trace:
    rows=load(a.trace);formal=[r for r in rows if not r['warmup']]
    assert all(r['matchesBaseline'] for r in rows),a.trace
    assert all(r['hash']==prior[str(r['round'])] for r in formal),a.trace
    means={}
    for mode in dict.fromkeys(r['mode'] for r in formal):
        chosen=[r['horizontal']['native']['surfaceTrace'] for r in formal if r['mode']==mode]
        means[mode]={k:statistics.mean(r[k] for r in chosen) for k in chosen[0]}
    report['diagnosticCounters']={'directory':str(a.trace),'scope':'Full generation path; instrumented timings excluded from speed comparisons.','means':means,'formalRows':formal,'dllSha256':digest(a.trace/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')}
report['prototypeSha256']={p.name:digest(p) for p in Path(__file__).parent.iterdir() if p.suffix in ['.py','.rs']}
reference=report['runs'][0]['snapshotSha256']
for run in report['runs'][1:]+([report['primaryInterleavedRun']] if a.paired else []):
    for name,sha in reference.items():
        if name.startswith(('main-java/','native/src/')):assert run['snapshotSha256'][name]==sha,(run['directory'],name)
    assert run['dllSha256']==report['runs'][0]['dllSha256'],run['directory']
report['frozenSourceChecks']={'mainJavaAndNativeSourcesIdenticalAcrossPerformanceRuns':True,'performanceDllsIdentical':True}
report['differentialChecks']={'testFile':'tools/performance/surface-demand/checks.rs','sha256':digest(Path(__file__).parent/'checks.rs'),'scope':'Native fixtures and rewritten rules, including synthetic terrablender:merged/custom namespace; not an installed modpack runtime test. Test execution is recorded in the report; summarizing performance does not run tests.'}
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'primaryInterleavedRun':report.get('primaryInterleavedRun',{}).get('modes'),'perRunExploratory':[{m:v.get('wallReductionVsCrossPct') for m,v in r['modes'].items() if 'wallReductionVsCrossPct' in v} for r in report['runs']],'counters':report.get('diagnosticCounters',{}).get('means')},indent=2))
