"""Verify frozen inputs and archive small raw evidence for completed matrix cases."""
from pathlib import Path
import argparse,hashlib,json,shutil,zipfile,statistics
p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();root=a.root.resolve()
dest=root/'evidence';dest.mkdir(exist_ok=False)
selected=[]
for parent in [root/'formal',root/'retries']:
    for directory in parent.iterdir():
        if directory.is_dir() and (directory/'complete.txt').exists():selected.append(directory)
for name in ['gpu-confirm-cpu','gpu-confirm-gpu','soil-probe-263']:
    if (root/name/'complete.txt').exists():selected.append(root/name)
hash_file=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
proof=[]
for directory in selected:
    manifest=json.loads((directory/'manifest.json').read_text())
    for name,expected in manifest['sources'].items():
        if hash_file(directory/name)!=expected:raise RuntimeError('Snapshot changed: '+str(directory/name))
    target=dest/directory.name;target.mkdir()
    for file in directory.iterdir():
        if file.is_file() and file.suffix in ['.json','.txt','.log']:shutil.copy2(file,target/file.name)
    proof.append({'case':directory.name,'relativeSource':directory.relative_to(root).as_posix(),
                  'verifiedSnapshotFiles':len(manifest['sources']),
                  'nativeSha256':hash_file(directory/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')})
(dest/'snapshots-verified.json').write_text(json.dumps(proof,indent=2))
release_proof=[]
for directory in selected:
    manifest=json.loads((directory/'manifest.json').read_text())
    project=Path(__file__).resolve().parents[3]
    if manifest['platform']=='fabric':project=project.parent/'voxy-server-side-Horizon'
    jar=project/'lib'/f'vss-0.3.7-{manifest["platform"]}-{manifest["target"]}.jar'
    native='META-INF/vss-natives/windows-x86_64/vss_native_core.dll'
    with zipfile.ZipFile(jar) as archive:
        attributes=archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ','').replace('\n ','')
        attrs=dict(line.split(': ',1) for line in attributes.splitlines() if ': ' in line)
        expected=manifest['sources']['main-resources/'+native]
        actual=hashlib.sha256(archive.read(native)).hexdigest()
        if actual!=expected:raise RuntimeError('Benchmark DLL differs from released DLL: '+directory.name)
        release_proof.append({'case':directory.name,'jar':jar.name,'jarSha256':hash_file(jar),
                              'buildId':attrs.get('VSS-Build-Id'),'nativeSha256':actual,'matchesReleasedNative':True})
(dest/'release-native-verification.json').write_text(json.dumps(release_proof,indent=2))
for parent in [root/'formal',root/'retries']:
    for file in parent.glob('summary.json'):shutil.copy2(file,dest/(parent.name+'-summary.json'))
entries={f.relative_to(dest).as_posix():hash_file(f) for f in dest.rglob('*') if f.is_file()}
(dest/'evidence-sha256.json').write_text(json.dumps(entries,indent=2))
print(json.dumps({'cases':len(proof),'files':len(entries),'nativeHashes':sorted({r['nativeSha256'] for r in proof})},indent=2))
