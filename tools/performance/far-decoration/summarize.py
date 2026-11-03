"""Validate frozen inputs and complete CPU-path runs before calculating paired results."""
from pathlib import Path
import argparse, hashlib, json, re, statistics

p=argparse.ArgumentParser()
p.add_argument('runs',type=Path,nargs='+')
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
all_rows=[]
records=[]
common_manifest=None
for run in a.runs:
    run=run.resolve()
    assert (run/'prediction-complete.txt').read_text().strip()=='success',run
    assert not (run/'server-failure.txt').exists(),run
    manifest={k.replace('\\','/'):v for k,v in json.loads((run/'source-manifest.json').read_text()).items()}
    if common_manifest is None:common_manifest=manifest
    else:assert common_manifest==manifest,'Do not merge different experiment implementations'
    for name,digest in manifest.items():
        assert hashlib.sha256((run/name).read_bytes()).hexdigest()==digest,(run,name)
    native='META-INF/vss-natives/windows-x86_64/vss_native_core.dll'
    dll=run/'gradle/resources/main'/native
    assert hashlib.sha256(dll.read_bytes()).hexdigest()==manifest['main-resources/'+native],dll
    rows=json.loads((run/'results.json').read_text())
    env=json.loads((run/'environment.json').read_text())
    modes=sorted({r['mode'] for r in rows})
    assert len(rows)==(env['rounds']+env['warmups'])*len(env['steps'])*len(modes),(run,len(rows),env)
    for row in rows:
        assert row['terrainMatchesBaseline'],row
        assert len(row['mappedHash'])==64,row
        diag=(run/f"diagnostics-{row['mode']}-{row['round']}-{row['step']}.txt").read_text()
        match=re.search(r'horizontalCopied=(\d+)',diag)
        assert match and int(match.group(1))>0,(run,row['mode'],row['round'])
        row=dict(row,run=run.name)
        all_rows.append(row)
    checks=json.loads((run/'stability-checks.json').read_text())
    assert all(c['stationaryEqual'] and c['lodAnchorStable'] and c['negativeCoordinateOverlapStable'] for c in checks),checks
    records.append({'run':str(run),'sourceManifestSha256':hashlib.sha256((run/'source-manifest.json').read_bytes()).hexdigest(),
                    'nativeSha256':manifest['main-resources/'+native],'environment':env,'stability':checks})

formal=[r for r in all_rows if not r['warmup']]
repeated={}
for row in all_rows:
    key=(row['mode'],row['round'],row['step'],row['baseX'],row['baseZ'])
    hashes=tuple(row[k] for k in ['geometryHash','terrainHash','mappedHash'])
    if key in repeated:assert repeated[key]==hashes,('Reversed run changed output',key)
    repeated[key]=hashes
summary={}
for step in sorted({r['step'] for r in formal}):
    groups={}
    baseline={(r['run'],r['round']):r for r in formal if r['mode']=='baseline' and r['step']==step}
    for mode in sorted({r['mode'] for r in formal}):
        rows=[r for r in formal if r['mode']==mode and r['step']==step]
        if not rows:continue
        base=[baseline[(r['run'],r['round'])] for r in rows]
        avg=lambda key:statistics.mean(r[key] for r in rows)
        values={k:avg(k) for k in ['wallMs','terrainMs','fallbackDecorationMs','scatterMs','decorationMs','encodingMs','threadCpuMs','javaThreadAllocatedMiB','gcMs','featureBoxes','tileBytesEstimate']}
        values['measurements']=len(rows)
        values['speedup']=statistics.mean(r['wallMs'] for r in base)/values['wallMs']
        values['timeReductionPercent']=100*(1-1/values['speedup'])
        values['pairedTimeReductionsPercent']=[100*(1-r['wallMs']/b['wallMs']) for r,b in zip(rows,base)]
        values['meanCounters']={key:statistics.mean(r['counts'][key] for r in rows) for key in rows[0]['counts']}
        qualities=[r['qualityAtStep'] for r in rows]
        values['quality']={key:{'mean':statistics.mean(q[key] for q in qualities),'min':min(q[key] for q in qualities),'max':max(q[key] for q in qualities)}
                           for key in ['baselineWoodyVoxels','candidateWoodyVoxels','candidateToBaselineVolume','intersectionOverUnion']}
        total_base=sum(q['baselineWoodyVoxels'] for q in qualities)
        values['quality']['aggregateVolumeRatio']=sum(q['candidateWoodyVoxels'] for q in qualities)/max(1,total_base)
        values['quality']['aggregateIntersectionOverUnion']=sum(q['intersection'] for q in qualities)/max(1,sum(q['baselineWoodyVoxels']+q['candidateWoodyVoxels']-q['intersection'] for q in qualities))
        if mode!='baseline':
            values['meanScatterCounters']={key:statistics.mean(r['scatterCounts'][key] for r in rows) for key in ['anchors','selected','accepted','anchorSamples','rawSpans','tinyOmitted']}
            values['anchorHeightP95PerPatch']=[r['anchorVsRenderedCellHeight']['p95'] for r in rows if 'anchorVsRenderedCellHeight' in r]
            values['anchorHeightMax']=max((r['anchorVsRenderedCellHeight']['max'] for r in rows if 'anchorVsRenderedCellHeight' in r),default=0)
            values['meanCoarseEnvironmentColumns']=statistics.mean(r.get('coarseEnvironmentColumns',0) for r in rows)
        groups[mode]=values
    summary[str(step)]=groups

result={'scope':'Actual NeoForge 1.21.1 CPU prediction path. Approximate decorations, not block equality. Excludes client queues, disk, GPU and rendering.',
        'runs':records,'summary':summary,'rows':all_rows}
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
for step,groups in summary.items():
    print('STEP',step)
    for mode,s in groups.items():
        print(mode,'ms',round(s['wallMs'],3),'speedup',round(s['speedup'],3),'MiB',round(s['javaThreadAllocatedMiB'],3),
              'exactPoints',round(s['meanCounters']['surfacePoints'],1),'displayPoints',round(s['meanCounters']['displayPoints'],1),'boxes',round(s['featureBoxes'],1))
