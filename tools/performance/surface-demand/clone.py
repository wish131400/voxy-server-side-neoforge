"""Reuse a frozen benchmark source snapshot while giving each run its own runtime output."""
from pathlib import Path
import argparse,shutil
p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path);a=p.parse_args();out=a.output.resolve();out.mkdir(exist_ok=False)
for name in ['main-java','main-resources','harness-java','native']:
    shutil.copytree(a.source/name,out/name,ignore=shutil.ignore_patterns('target','.git','__pycache__'))
for name in ['worldgen-profile.bin','structure-templates.json','input-manifest.json','bench-source-manifest.json','surface-demand-manifest.json']:
    shutil.copy2(a.source/name,out/name)
(out/'server').mkdir()
for name in ['eula.txt','server.properties']:shutil.copy2(a.source/'server'/name,out/'server'/name)
print(out)
