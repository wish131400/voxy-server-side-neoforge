"""Summarize completed full-runtime cases, keeping scope and routing evidence."""
from pathlib import Path
import argparse,hashlib,json,re,statistics,zipfile
p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();root=a.output.resolve()
cases=[]
for directory in sorted(root.iterdir()):
    if not directory.is_dir() or not (directory/'complete.txt').exists():continue
    rows=json.loads((directory/'results.json').read_text());measured=[r for r in rows if not r['warmup']]
    manifest=json.loads((directory/'manifest.json').read_text())
    metrics={name:statistics.median(r[name] for r in measured) for name in ['wallMs','terrainMs','decorationMs','encodingMs','threadCpuMs','allocatedMiB','gcMs','horizontalCopied']}
    route=[]
    for row in rows:
        diag=(directory/f'diagnostics-{row["round"]}.txt').read_text()
        identity=re.search(r'biomeIdentity=\{([^}]+)\}',diag)
        route.append({'round':row['round'],'identity':identity.group(1) if identity else None,'javaClimateCopied':row['horizontalCopied'],'rustHorizontal':row['rustHorizontal']})
    cases.append({'label':directory.name,'platform':manifest['platform'],'target':manifest['target'],'gpuRequested':manifest['gpu'],'rounds':len(measured),'median':metrics,
                  'msPerChunk':metrics['wallMs']/measured[0]['chunks'],'equivalentChunksPerSecond':1000*measured[0]['chunks']/metrics['wallMs'],
                  'decorationShareTotal':sum(r['decorationMs'] for r in measured)/sum(r['wallMs'] for r in measured),
                  'wallRangeMs':[min(r['wallMs'] for r in measured),max(r['wallMs'] for r in measured)],
                  'hashes':{str(r['round']):r['hash'] for r in rows},'plantBlocks':{str(r['round']):r['plantBlocks'] for r in rows},
                  'route':route,'lastVegetation':rows[-1]['vegetation'],'lastDisplay':rows[-1]['display'],
                  'gpuInitialization':json.loads((directory/'gpu-initialization.json').read_text()) if (directory/'gpu-initialization.json').exists() else None})
by_name={c['label']:c for c in cases};gpu_comparison=None
if 'fabric-263-cpu' in by_name and 'fabric-263-gpu' in by_name:
    cpu=by_name['fabric-263-cpu'];gpu=by_name['fabric-263-gpu']
    gpu_comparison={'hashesIdenticalAllRounds':cpu['hashes']==gpu['hashes'],
                    'medianWallReduction':1-gpu['median']['wallMs']/cpu['median']['wallMs'],
                    'medianTerrainReduction':1-gpu['median']['terrainMs']/cpu['median']['terrainMs']}
summary={'scope':'Actual version runtime, one worker, seed42, 16x16 target chunks plus decoration halo; terrain spacing4, decoration spacing1; cold disjoint regions, 2 warmups + 5 measured; excludes initialization/hash, scheduler/network/disk/Voxy ingestion/mesh/rendering/frame time',
         'cases':cases,'gpuComparison':gpu_comparison}
(root/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
for c in cases:print(c['label'],{k:round(v,3) for k,v in c['median'].items() if k in ['wallMs','terrainMs','decorationMs','encodingMs']})
if gpu_comparison:print('GPU',gpu_comparison)
