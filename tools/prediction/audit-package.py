"""Measure ZIP-compressed payloads and inventory source; never modifies a JAR."""
import argparse
import collections
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

def category(name):
    if name.startswith('META-INF/vss-natives/'):
        return 'native_core' if 'vss_native_core.' in name else 'legacy_native'
    if name.endswith('.class'):
        return 'prediction_java' if '/client/prediction/' in name else 'other_java'
    if name.startswith('assets/vss/shaders/'):
        return 'shaders'
    if name.startswith('assets/') or name == 'icon.png':
        return 'assets'
    return 'metadata'

def archive(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        groups = collections.defaultdict(lambda: dict(files=0, raw=0, compressed=0))
        duplicates = collections.defaultdict(list)
        entries = []
        for entry in z.infolist():
            if entry.is_dir():
                continue
            payload = z.read(entry.filename)
            group = groups[category(entry.filename)]
            group['files'] += 1
            group['raw'] += entry.file_size
            group['compressed'] += entry.compress_size
            digest = hashlib.sha256(payload).hexdigest()
            duplicates[digest].append(entry.filename)
            entries.append(dict(name=entry.filename, raw=entry.file_size, compressed=entry.compress_size, sha256=digest))
        return dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), groups=dict(groups),
                    zip_overhead=len(data)-sum(e['compressed'] for e in entries),
                    duplicate_payloads=[v for v in duplicates.values() if len(v)>1],
                    entries=sorted(entries,key=lambda e:e['compressed'],reverse=True))

def sources(root):
    paths = list((root/'src/main/java').rglob('*.java')) + list((root/'tools/rust/vss-native-core/src').rglob('*.rs'))
    texts = {p:p.read_text(encoding='utf-8-sig') for p in paths}
    by_language = {}
    for ext in ('.java','.rs'):
        values = [s for p,s in texts.items() if p.suffix==ext]
        by_language[ext] = dict(files=len(values),lines=sum(len(s.splitlines()) for s in values))
    identical = collections.defaultdict(list)
    windows = collections.defaultdict(set)
    for p,s in texts.items():
        name=str(p.relative_to(root))
        identical[hashlib.sha256(s.encode()).hexdigest()].append(name)
        lines=[re.sub(r'\s+',' ',l.strip()) for l in s.splitlines()
               if l.strip() and not l.strip().startswith(('//','*','import ','package '))]
        for i in range(len(lines)-15):
            block=tuple(lines[i:i+16])
            if sum(map(len,block))>700: windows[block].add(name)
    groups=collections.Counter(tuple(sorted(v)) for v in windows.values() if len(v)>1)
    return dict(languages=by_language, duplicate_files=[v for v in identical.values() if len(v)>1],
                repeated_windows=[dict(files=list(k),windows=v) for k,v in groups.most_common()],
                limits='Exact files and normalized 16-line windows over 700 characters; not a proof of semantic non-duplication or reachability.')

parser=argparse.ArgumentParser()
parser.add_argument('--before-backup',type=Path,required=True)
parser.add_argument('--after',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--native-windows',type=Path,default=Path('tools/rust/vss-native-core/target/release/vss_native_core.dll'))
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
with zipfile.ZipFile(args.before_backup) as backup:
    before=archive(backup.read('build/libs/vss-0.3-neoforge-1.21.1.jar'))
    before_sources={ext:dict(files=0,lines=0) for ext in ('.java','.rs')}
    for name in backup.namelist():
        if name.startswith('src/main/java/') and name.endswith('.java') or name.startswith('tools/rust/vss-native-core/src/') and name.endswith('.rs'):
            group=before_sources[Path(name).suffix]
            group['files']+=1
            group['lines']+=len(backup.read(name).decode('utf-8-sig').splitlines())
after=archive(args.after.read_bytes())
assert not after['groups'].get('legacy_native'), 'Retired backend is still packaged'
assert len({e['name'] for e in after['entries']}) == len(after['entries']), 'Duplicate ZIP entries'
for entry in after['entries']:
    assert not any(name in entry['name'] for name in ('NativeTerrainGraphSampler','VssNativeBridge','NativeDeclarationClass','RustWorldgenSnapshot','VssLodPalette','VssLodOcclusion','PlayerRespawnNoBlockingMixin'))
native_path=root/args.native_windows
assert next(e['sha256'] for e in after['entries'] if e['name'].endswith('/vss_native_core.dll')) == hashlib.sha256(native_path.read_bytes()).hexdigest()
mask='dev/xantha/vss/client/prediction/PredictionVanillaMask.class'
assert next(e['sha256'] for e in before['entries'] if e['name']==mask) == next(e['sha256'] for e in after['entries'] if e['name']==mask), 'Mask bytecode changed'
report=dict(before=before, after=after, saved_bytes=before['bytes']-after['bytes'],before_sources=before_sources, sources=sources(root))
args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ('before','after')},ensure_ascii=False,indent=2))
print(json.dumps(dict(before_bytes=before['bytes'],after_bytes=after['bytes'],after_groups=after['groups']),indent=2))
