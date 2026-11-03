"""Validate exact full-path results and keep diagnostic counts separate from timings."""
from pathlib import Path
import argparse, hashlib, json, statistics

p=argparse.ArgumentParser();p.add_argument('directories',nargs='+',type=Path)
p.add_argument('--trace',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
prior=json.loads(Path('tools/performance/climate-sharing/results-20261102.json').read_text())['outputHashes']
report={'scope':'Frozen NeoForge 1.21.1 full generation CPU path. Exact dry-air proof, no rendering, queues, network or disk-cache loading.','runs':[]}
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(d):
    assert (d/'prediction-complete.txt').read_text().strip()=='success',d
    assert not (d/'server-failure.txt').exists(),d
    rows=json.loads((d/'results.json').read_text())
    assert all(r['matchesBaseline'] for r in rows),d
    formal=[r.copy() for r in rows if not r['warmup']]
    assert all(r['hash']==prior[str(r['round'])] for r in formal),d
    by={(r['mode'],r['round']):r for r in formal}
    for r in formal:
        control=by['cross',r['round']]
        for key in ['surfaceCalls','surfacePoints','biomeQueries','plantBlocks','featureBoxes']:
            assert r[key]==control[key],(d,r['mode'],r['round'],key)
    return formal,by
def hashes(d):
    return {p.relative_to(d).as_posix():digest(p) for folder in ['main-java','harness-java','native/src'] for p in (d/folder).rglob('*') if p.is_file()}
allrows=[]
fields=['wallMs','terrainMs','decorationMs','encodingMs','threadCpuMs','javaThreadAllocatedMiB','gcMs','exactMs','javaBiomeMs','displayMs']
for d in a.directories:
    for name in ['pressure-checks.log','column-checks.log']:
        assert '4 passed; 0 failed;' in (d/name).read_text(encoding='utf-8'),(d,name)
    assert '1 passed; 0 failed;' in (d/'nearest-checks.log').read_text(encoding='utf-8'),d
    rows,by=load(d)
    for r in rows:
        f=json.loads((d/f'feature-costs-{r["mode"]}-{r["round"]}.json').read_text())['features']
        for name,key in [('exactMs','exactNanos'),('javaBiomeMs','biomeNanos'),('displayMs','displayNanos')]:
            r[name]=sum(x[key] for x in f)/1e6
    modes={}
    for mode in dict.fromkeys(r['mode'] for r in rows):
        chosen=[r for r in rows if r['mode']==mode]
        modes[mode]={k:statistics.mean(r[k] for r in chosen) for k in fields}
    control=[r for r in rows if r['mode']=='cross']
    summary={};paired={}
    for mode in ['cross_dry','cross_dry_nearby']:
        candidate=[r for r in rows if r['mode']==mode];summary[mode]={}
        for k in fields:
            summary[mode][k]={'control':statistics.mean(r[k] for r in control),'candidate':statistics.mean(r[k] for r in candidate)}
        for k in ['wallMs','exactMs','threadCpuMs']:
            summary[mode][k]['reductionPct']=100*(1-summary[mode][k]['candidate']/summary[mode][k]['control'])
        paired[mode]=[100*(1-r['wallMs']/by['cross',r['round']]['wallMs']) for r in candidate]
    allrows.extend(rows)
    report['runs'].append({'directory':str(d),'environment':json.loads((d/'experiment-environment.json').read_text()),'modes':modes,'summary':summary,'pairedWallReductionsPct':paired,'formalRows':rows,'sourceSha256':hashes(d),'dllSha256':digest(d/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'),'inputSha256':{name:digest(d/name) for name in ['worldgen-profile.bin','structure-templates.json']},'testLogs':{name:(d/name).read_text(encoding='utf-8') for name in ['pressure-checks.log','column-checks.log','nearest-checks.log']},'evidenceSha256':{p.name:digest(p) for p in d.glob('feature-costs-*.json')}})
if len(report['runs'])>1:
    for r in report['runs'][1:]:
        for k in ['sourceSha256','dllSha256','inputSha256']:assert r[k]==report['runs'][0][k],k
control=[r for r in allrows if r['mode']=='cross']
report['pooled']={}
for mode in ['cross_dry','cross_dry_nearby']:
    candidate=[r for r in allrows if r['mode']==mode];report['pooled'][mode]={}
    for k in fields:
        c=statistics.mean(r[k] for r in control);v=statistics.mean(r[k] for r in candidate)
        report['pooled'][mode][k]={'control':c,'candidate':v}
        if k in ['wallMs','exactMs','threadCpuMs']:report['pooled'][mode][k]['reductionPct']=100*(1-v/c)
if a.trace:
    rows,_=load(a.trace);means={}
    for mode in dict.fromkeys(r['mode'] for r in rows):
        selected=[r['horizontal']['native']['heightTrace'] for r in rows if r['mode']==mode]
        means[mode]={k:statistics.mean(r[k] for r in selected) for k in selected[0]}
    report['diagnostic']={'directory':str(a.trace),'scope':'Entry counts only; instrumented timings excluded.','means':means,'formalRows':rows,'dllSha256':digest(a.trace/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')}
report['toolsSha256']={p.name:digest(p) for p in Path(__file__).parent.iterdir() if p.suffix in ['.py','.rs']}
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'pooled':report['pooled'],'perRun':[r['pairedWallReductionsPct'] for r in report['runs']],'counts':report.get('diagnostic',{}).get('means')},indent=2))
