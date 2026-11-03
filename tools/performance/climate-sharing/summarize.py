"""Compare naturally entered Rust and Java requests; replay parity is separate."""
import argparse,hashlib,json,statistics,struct
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--output',type=Path);a=p.parse_args();d=a.directory
names=['temperature','humidity','continentalness','erosion','depth','weirdness']
rows=[]
for jf in sorted(d.glob('java-sharing-baseline-*.json')):
    r=int(jf.stem.rsplit('-',1)[-1])
    if '--' in jf.stem:continue
    metadata=json.loads(jf.read_text());temporal='availableBeforeJava3D' in metadata
    jfmt=struct.Struct('>iiiIIQI6Q6q' if temporal else '>iiiIIQ6Q6q');nfmt=struct.Struct('>iiiIQQ6Q6q')
    jb=d/f'java-climate-baseline-{r}.bin';nb=d/f'native-climate-baseline-{r}.bin'
    j={v[:3]:v for v in jfmt.iter_unpack(jb.read_bytes())};n={v[:3]:v for v in nfmt.iter_unpack(nb.read_bytes())}
    jxz={(x,z) for x,y,z in j};nxz={(x,z) for x,y,z in n}
    pre={k:v for k,v in n.items() if v[3]&2}
    prexz={(x,z) for x,y,z in pre}
    shared=j.keys()&n.keys();early=j.keys()&pre.keys()
    jo=7 if temporal else 6
    rawmatch=[sum(j[k][jo+i]==n[k][6+i] for k in shared) for i in range(6)]
    quantmatch=[sum(j[k][jo+6+i]==n[k][12+i] for k in shared) for i in range(6)]
    vary=[]
    for i in range(6):
        byxz={}
        for k,v in j.items():byxz.setdefault((k[0],k[2]),set()).add(v[jo+i])
        vary.append(sum(len(v)>1 for v in byxz.values()))
    item={'round':r,'java3D':len(j),'javaXZ':len(jxz),'native3D':len(n),'nativeXZ':len(nxz),
          'shared3D':len(shared),'shared3DPct':100*len(shared)/len(j),'sharedXZ':len(jxz&nxz),
          'terrainPrior3D':len(early),'terrainPriorXZ':len(jxz&prexz),
          'sameRawAtNaturalIntersection':rawmatch,'sameTargetAtNaturalIntersection':quantmatch,
          'javaXZWithYDependentValues':dict(zip(names,vary)),
          'replay':metadata,'nativeStats':json.loads((d/f'native-sharing-baseline-{r}.json').read_text())}
    native_by_xz={(k[0],k[2]):v for k,v in n.items()}
    item['sameHorizontalRootAcrossJavaRust']={names[i]:sum(v[jo+i]==native_by_xz[(k[0],k[2])][6+i] for k,v in j.items() if (k[0],k[2]) in native_by_xz) for i in [0,1,2,3,5]}
    item['java3DAtSharedXZ']=sum((k[0],k[2]) in native_by_xz for k in j)
    if temporal:
        jc={v[:2]:v[2:] for v in struct.Struct('>iiII').iter_unpack((d/f'java-columns-baseline-{r}.bin').read_bytes())}
        nc={v[:2]:v[2] for v in struct.Struct('>iiI').iter_unpack(Path(str(nb)+'.columns').read_bytes())}
        produced={k for k,v in jc.items() if v[0]&1}|{k for k,v in nc.items() if v&1}
        consumed={k for k,v in jc.items() if v[1]}|{k for k,v in nc.items() if v&4}
        item['columns']={'exactRequestedJavaAndNative':len(produced),'exactRequestedObserved':len(produced&consumed),
                         'exactRequestedUnobserved':len(produced-consumed),'readJavaAndNative':len(consumed),
                         'note':'Coordinate-union coverage, including display readers. Not a safe skip oracle: surface rules/neighbour geometry also need data; query batching may amortize work.'}
    rows.append(item)
results=json.loads((d/'results.json').read_text())
prior=Path('build/decoration-modules-20261102-reuse/results.json')
oracles={r['round']:r['hash'] for r in json.loads(prior.read_text()) if r['mode']=='baseline'} if prior.exists() else {}
report={'scope':'Instrumented full prediction path, not a speed benchmark. Replay occurs after timed path; natural overlap does not imply temporal availability.',
        'regions':rows,'mean':{key:statistics.mean(row[key] for row in rows) for key in ['java3D','javaXZ','native3D','nativeXZ','shared3D','shared3DPct','sharedXZ','terrainPrior3D','terrainPriorXZ']},
        'outputMatchesPrior':all(r['hash']==oracles[r['round']] for r in results if not r['warmup']),
        'outputHashes':{str(r['round']):r['hash'] for r in results if not r['warmup']},
        'evidenceSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in d.glob('*sharing*.json')}}
if all('columns' in r for r in rows):
    for key in ['availableBeforeJava3D','availableBeforeJavaXZ','submittedExact','readExact','submittedDisplay','readDisplay']:
        report['mean'][key]=statistics.mean(row['replay'][key] for row in rows)
    for key in ['exactRequestedJavaAndNative','exactRequestedObserved','exactRequestedUnobserved','readJavaAndNative']:
        report['mean'][key]=statistics.mean(row['columns'][key] for row in rows)
    report['mean']['availableBeforeJava3DPct']=100*sum(row['replay']['availableBeforeJava3D'] for row in rows)/sum(row['java3D'] for row in rows)
    report['mean']['availableBeforeJavaXZPct']=100*sum(row['replay']['availableBeforeJavaXZ'] for row in rows)/sum(row['java3D'] for row in rows)
    report['mean']['exactRequestedUnobservedPct']=100*sum(row['columns']['exactRequestedUnobserved'] for row in rows)/sum(row['columns']['exactRequestedJavaAndNative'] for row in rows)
    for key in ['records','recordNs','coldNs','colorNs','targetCalls','targetMisses']:
        report['mean'][key]=[statistics.mean(row['nativeStats'][key][i] for row in rows) for i in range(5)]
if (d/'native-document.json').exists():
    doc=json.loads((d/'native-document.json').read_text());registry=doc['density_functions'];router=doc['settings']['noise_router']
    def dependencies(value,seen=None):
        seen=set() if seen is None else seen
        if isinstance(value,str) and value in registry and value not in seen:
            seen.add(value);dependencies(registry[value],seen)
        elif isinstance(value,dict):
            for key,child in value.items():
                if key not in ['noise','type','argument'] or key=='argument' and not value.get('type','').endswith(('shift_a','shift_b','shift')):dependencies(child,seen)
        elif isinstance(value,list):
            for child in value:dependencies(child,seen)
        return seen
    terrain=dependencies(router['final_density'])|dependencies(router['initial_density_without_jaggedness'])
    report['graph']={'depth':registry['minecraft:overworld/depth'],'climateRoots':{},'terrainNamedDependencies':sorted(terrain)}
    for name in ['temperature','vegetation','continents','erosion','depth','ridges']:
        refs=dependencies(router[name]);report['graph']['climateRoots'][name]={'definition':router[name],
            'namedDependencies':sorted(refs),'sharedWithTerrainNamedDependencies':sorted(refs&terrain)}
    report['documentSha256']=hashlib.sha256((d/'native-document.json').read_bytes()).hexdigest()
report['binaryEvidenceSha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in d.glob('*climate-*.bin*')}
report['columnEvidenceSha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in d.glob('java-columns-*.bin')}
root=Path(__file__).resolve().parents[3]
manifest=json.loads((d/'input-manifest.json').read_text())
source_mismatch=[]
for group in ['sources','resources']:
    for name,expected in manifest[group].items():
        if hashlib.sha256((root/name).read_bytes()).hexdigest()!=expected:source_mismatch.append(name)
native_manifest=json.loads((d/'native-source-manifest.json').read_text())
native_mismatch=[name for name,expected in native_manifest.items() if hashlib.sha256((root/'tools/rust/vss-native-core'/name).read_bytes()).hexdigest()!=expected]
report['sourceValidation']={'javaResourceCount':sum(len(manifest[g]) for g in ['sources','resources']),
    'nativeInputCount':len(native_manifest),'productionMismatch':source_mismatch,'nativeMismatch':native_mismatch,
    'diagnosticDllSha256':hashlib.sha256((d/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll').read_bytes()).hexdigest(),
    'productionDllSha256':hashlib.sha256((root/'src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll').read_bytes()).hexdigest()}
report['runtimeComplete']=(d/'prediction-complete.txt').read_text().strip()=='success' and not (d/'server-failure.txt').exists()
text=json.dumps(report,indent=2)
if a.output:a.output.write_text(text,encoding='utf-8')
print(json.dumps({k:report[k] for k in ['mean','outputMatchesPrior','runtimeComplete','sourceValidation']},indent=2))
