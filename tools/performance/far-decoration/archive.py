"""Preserve reproducible evidence without copying Gradle caches or generated Minecraft worlds."""
from pathlib import Path
import argparse, hashlib, json, shutil
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--snapshot',type=Path,required=True);p.add_argument('--runs',type=Path,nargs='+',required=True);p.add_argument('--summary',type=Path,required=True);a=p.parse_args()
out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
snapshot=out/'snapshot'
for name in ['main-java','main-resources','harness-java']:
    shutil.copytree(a.snapshot/name,snapshot/name)
for name in ['worldgen-profile.bin','structure-templates.json','source-manifest.json','production-verification.json']:
    if (a.snapshot/name).exists():shutil.copy2(a.snapshot/name,snapshot/name)
for run in a.runs:
    dest=out/'runs'/run.name;dest.mkdir(parents=True)
    for f in run.iterdir():
        if f.is_file() and f.suffix in {'.json','.txt','.log'}:shutil.copy2(f,dest/f.name)
shutil.copy2(a.summary,out/'results.json')
shutil.copytree(Path(__file__).parent,out/'tools',ignore=shutil.ignore_patterns('__pycache__'))
files={str(f.relative_to(out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in out.rglob('*') if f.is_file()}
(out/'evidence-manifest.json').write_text(json.dumps(files,indent=2),encoding='utf-8')
print(out,'evidence files',len(files))
