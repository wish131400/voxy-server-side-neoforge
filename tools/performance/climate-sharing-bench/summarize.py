"""Summarize paired complete-path results and preserve evidence outside ignored build output."""
import argparse,hashlib,json,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('directories',nargs='+',type=Path);p.add_argument('--shipping-directory',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
fields=['wallMs','terrainMs','decorationMs','encodingMs','threadCpuMs','processCpuMs','javaThreadAllocatedMiB','gcMs']
report={'scope':'Single generation thread in real NeoForge 1.21.1 runtime; complete terrain, decorations, settling and Voxy CPU input; excludes render/GPU, disk, networking, startup and output hashing. Warmups excluded. No skipped feature candidates or reduced fidelity.','runs':[]}
all_rows=[]
for d in a.directories:
    assert (d/'prediction-complete.txt').read_text().strip()=='success',d
    assert not (d/'server-failure.txt').exists(),d
    rows=json.loads((d/'results.json').read_text());formal=[r for r in rows if not r['warmup']]
    assert all(r['matchesBaseline'] for r in rows),[(r['mode'],r['round']) for r in rows if not r['matchesBaseline']]
    base={r['round']:r for r in formal if r['mode']=='baseline'}
    tail={r['round']:r for r in formal if r['mode']=='baseline_tail'}
    modes={}
    for mode in dict.fromkeys(r['mode'] for r in formal):
        selected=[r.copy() for r in formal if r['mode']==mode]
        for r in selected:
            features=json.loads((d/f'feature-costs-{mode}-{r["round"]}.json').read_text())['features']
            for target,field in [('javaBiomeMs','biomeNanos'),('exactMs','exactNanos'),('displayMs','displayNanos')]:r[target]=sum(f[field] for f in features)/1e6
            r['nativeFeatureInclusiveMs']=sum(f['nanos'] for f in features if f['nativeCalls']>0)/1e6
        result={k:statistics.mean(r[k] for r in selected) for k in fields+['javaBiomeMs','exactMs','displayMs','nativeFeatureInclusiveMs']}
        result['chunksPerSecond']=selected[0]['chunks']*1000/result['wallMs']
        result['pairedWallReductionPct']=100*(1-sum(r['wallMs'] for r in selected)/sum((base[r['round']]['wallMs']+tail.get(r['round'],base[r['round']])['wallMs'])/2 for r in selected))
        result['pairedReductionByRegionPct']=[100*(1-r['wallMs']/((base[r['round']]['wallMs']+tail.get(r['round'],base[r['round']])['wallMs'])/2)) for r in selected]
        result['horizontalMean']={key:statistics.mean(r['horizontal'][key] for r in selected) for key in selected[0]['horizontal'] if key not in ['native','cuts']}
        native=selected[0]['horizontal']['native']
        result['nativeMean']={key:(native[key] if isinstance(native[key],bool) else statistics.mean(r['horizontal']['native'][key] for r in selected)) for key in native}
        modes[mode]=result
    dll=d/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'
    run={'directory':str(d),'environment':json.loads((d/'experiment-environment.json').read_text()),'modes':modes,'allOutputHashesMatch':True,'rows':rows,'featureFilesSha256':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in d.glob('feature-costs-*.json')},'dllSha256':hashlib.sha256(dll.read_bytes()).hexdigest()}
    prior=json.loads(Path('tools/performance/climate-sharing/results-20261102.json').read_text())['outputHashes']
    run['matchesPriorProductionOutputs']=all(r['hash']==prior[str(r['round'])] for r in formal)
    assert run['matchesPriorProductionOutputs'],d
    report['runs'].append(run);all_rows.extend(formal)
report['pooled']={}
for mode in dict.fromkeys(r['mode'] for r in all_rows if 'verify' not in r['mode']):
    selected=[r for r in all_rows if r['mode']==mode]
    report['pooled'][mode]={k:statistics.mean(r[k] for r in selected) for k in fields}
    if mode not in ['baseline','baseline_tail']:
        summary=report['pooled'][mode];controls=[r for r in all_rows if r['mode'] in ['baseline','baseline_tail']]
        summary['wallReductionPct']=100*(1-summary['wallMs']/statistics.mean(r['wallMs'] for r in controls))
        summary['speedup']=statistics.mean(r['wallMs'] for r in controls)/summary['wallMs']
        summary['regionsTested']=len(selected)
report['productionInputsUnchanged']=[]
for d in a.directories:
    manifest=json.loads((d/'bench-source-manifest.json').read_text())
    changes=[k for k,v in manifest['inputs'].items() if hashlib.sha256(Path(k).read_bytes()).hexdigest()!=v]
    report['productionInputsUnchanged'].append({'run':str(d),'checkedFiles':len(manifest['inputs']),'changedFiles':changes})
    assert not changes,changes
if a.shipping_directory:
    d=a.shipping_directory
    assert (d/'prediction-complete.txt').read_text().strip()=='success' and not (d/'server-failure.txt').exists()
    rows=json.loads((d/'results.json').read_text());formal=[r for r in rows if not r['warmup']]
    prior=json.loads(Path('tools/performance/climate-sharing/results-20261102.json').read_text())['outputHashes']
    assert all(r['matchesBaseline'] and r['hash']==prior[str(r['round'])] for r in formal)
    means={k:statistics.mean(r[k] for r in formal) for k in fields}
    for target,field in [('javaBiomeMs','biomeNanos'),('exactMs','exactNanos'),('displayMs','displayNanos')]:
        means[target]=statistics.mean(sum(f[field] for f in json.loads((d/f'feature-costs-{r["mode"]}-{r["round"]}.json').read_text())['features'])/1e6 for r in formal)
    dll=d/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'
    current=Path('src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')
    assert dll.read_bytes()==current.read_bytes()
    report['shippingControl']={'directory':str(d),'means':means,'rows':rows,'dllSha256':hashlib.sha256(dll.read_bytes()).hexdigest(),'allOutputHashesMatch':True,'featureFilesSha256':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in d.glob('feature-costs-*.json')}}
    for mode,result in report['pooled'].items():
        if mode not in ['baseline','baseline_tail']:
            result['wallReductionVsShippingPct']=100*(1-result['wallMs']/means['wallMs'])
            result['speedupVsShipping']=means['wallMs']/result['wallMs']
report['prototypeSourceSha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in Path(__file__).parent.iterdir() if f.suffix in ['.py','.ps1','.java','.rs']}
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'pooled':report['pooled'],'shipping':report.get('shippingControl',{}).get('means'),'runModes':[{m:{k:round(v[k],3) for k in ['wallMs','javaBiomeMs','exactMs','pairedWallReductionPct']} for m,v in r['modes'].items()} for r in report['runs']],'unchanged':report['productionInputsUnchanged']},indent=2))
