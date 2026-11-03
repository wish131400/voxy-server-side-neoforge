"""Run the isolated actual NeoForge runtime, checking completion rather than Gradle exit alone."""
from pathlib import Path
import argparse, os, subprocess
p=argparse.ArgumentParser()
p.add_argument('output',type=Path)
p.add_argument('--rounds',type=int,default=5)
p.add_argument('--warmups',type=int,default=2)
p.add_argument('--side',type=int,default=16)
p.add_argument('--steps',default='4')
p.add_argument('--modes',default='baseline,scatter_full,scatter_lod')
p.add_argument('--compile-only',action='store_true')
a=p.parse_args()
root=Path(__file__).resolve().parents[3]
out=a.output.resolve()
assert not (out/'results.json').exists(), 'Use a fresh runtime directory'
env=os.environ.copy()
env['JAVA_HOME']='C:/Program Files/Java/jdk-21'
cmd=[str(root/'gradlew.bat'),'-Dorg.gradle.java.home='+env['JAVA_HOME'],'--offline',
     '--project-cache-dir',str(out/'project-cache'),'-I','tools/performance/decoration-experiment/experiment.gradle',
     '-I','tools/performance/lod-decoration/experiment.gradle',f'-PvssExperimentRoot={out}',
     f'-PvssExperimentRounds={a.rounds}',f'-PvssExperimentWarmups={a.warmups}',f'-PvssExperimentSide={a.side}',
     f'-PvssExperimentModes={a.modes}',f'-PvssLodSteps={a.steps}','--console=plain','--max-workers=2',
     'decorationExperimentClasses' if a.compile_only else 'runServer']
log=out/('compile.log' if a.compile_only else 'launch.log')
with log.open('w',encoding='utf-8') as f:
    result=subprocess.run(cmd,cwd=root,env=env,stdout=f,stderr=subprocess.STDOUT)
if result.returncode or (not a.compile_only and (not (out/'prediction-complete.txt').exists() or (out/'server-failure.txt').exists())):
    print(log.read_text(encoding='utf-8',errors='replace')[-9000:])
    raise SystemExit(result.returncode or 1)
print('Compiled' if a.compile_only else 'Complete',out)
