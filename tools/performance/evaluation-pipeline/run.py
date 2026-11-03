"""Run one isolated candidate against the same frozen Java/resource snapshot."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, time

HERE=Path(__file__).resolve().parent
PROJECT=HERE.parents[2].parent/'voxy-server-side-Horizon'
p=argparse.ArgumentParser()
p.add_argument('root',type=Path);p.add_argument('name');p.add_argument('--mode',default='')
p.add_argument('--original',action='store_true');p.add_argument('--trace',action='store_true')
p.add_argument('--rounds',type=int,default=5);p.add_argument('--warmups',type=int,default=2)
p.add_argument('--jdk',default=os.environ.get('JAVA_HOME'),help='JDK 25 for the 26.3 runtime; defaults to JAVA_HOME')
a=p.parse_args();root=a.root.resolve();bench=root/'baseline';out=root/'runs'/a.name
if not a.jdk:p.error('--jdk or JAVA_HOME is required')
out.mkdir(parents=True,exist_ok=False)
dll=root/'baseline.dll' if a.original else root/'native/target/release/vss_native_core.dll'
shutil.copy2(dll,bench/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')
outputs=['results.json','complete.txt','failure.txt','runtime-environment.json','native-document.json','registries.json','generator.json']
for f in [*(bench/name for name in outputs),*bench.glob('diagnostics-*.txt')]:
    if f.exists():f.unlink()
env=os.environ.copy();env['JAVA_HOME']=a.jdk;env['VSS_EVAL_MODE']=a.mode
env.pop('VSS_EVAL_TRACE',None)
if a.trace:env['VSS_EVAL_TRACE']='1'
manifest={'mode':a.mode,'trace':a.trace,'original':a.original,'rounds':a.rounds,'warmups':a.warmups,
          'nativeSha256':hashlib.sha256(dll.read_bytes()).hexdigest(),
          'javaSha256':{f.relative_to(bench/'main-java').as_posix():hashlib.sha256(f.read_bytes()).hexdigest() for f in (bench/'main-java').rglob('*.java')}}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
command=[str(PROJECT/'gradlew.bat'),f'-Dorg.gradle.java.home={a.jdk}','--offline','--console=plain','--max-workers=2','-PmcTarget=26.3',
         '-I',str(HERE.parent/'version-loading/fabric.gradle'),f'-PvssBenchRoot={bench}',
         f'-PserverRunDir={Path(os.path.relpath(bench/"server",PROJECT)).as_posix()}',f'-PvssBenchRounds={a.rounds}',
         f'-PvssBenchWarmups={a.warmups}','-PvssBenchSide=16','-PvssBenchStep=4','-PvssBenchGpu=false','runServer']
start=time.monotonic()
with (out/'launch.log').open('w',encoding='utf-8') as log:
    result=subprocess.run(command,cwd=PROJECT,env=env,stdout=log,stderr=subprocess.STDOUT)
for f in [*(bench/name for name in outputs),*bench.glob('diagnostics-*.txt')]:
    if f.exists():shutil.copy2(f,out/f.name)
if result.returncode or (out/'failure.txt').exists() or not (out/'complete.txt').exists():
    print((out/'launch.log').read_text(encoding='utf-8',errors='replace')[-7000:]);raise SystemExit(1)
rows=json.loads((out/'results.json').read_text());formal=[r for r in rows if not r['warmup']]
print(json.dumps({'run':a.name,'elapsedSec':round(time.monotonic()-start,1),
      **{key:sum(r[key] for r in formal)/len(formal) for key in ['wallMs','decorationMs','terrainMs','allocatedMiB']},
      'hashes':[r['hash'] for r in rows]},indent=2),flush=True)
