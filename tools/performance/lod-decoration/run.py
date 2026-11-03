"""Run one prepared, isolated full-runtime experiment with fresh result files."""
from pathlib import Path
import argparse,os,subprocess
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--rounds',type=int,default=5);p.add_argument('--warmups',type=int,default=2);p.add_argument('--side',type=int,default=16)
p.add_argument('--modes',default='baseline,span,demand,span_demand');p.add_argument('--steps',default='4');p.add_argument('--compile-only',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parents[3];out=a.output.resolve()
for name in ['results.json','prediction-complete.txt','server-failure.txt']:
    assert not (out/name).exists(),f'Use a fresh runtime: {out/name}'
env=os.environ.copy();env['JAVA_HOME']='C:/Program Files/Java/jdk-21'
command=[str(root/'gradlew.bat'),'-Dorg.gradle.java.home=C:/Program Files/Java/jdk-21','--offline','--project-cache-dir',str(out/'project-cache'),'-I','tools/performance/decoration-experiment/experiment.gradle','-I','tools/performance/lod-decoration/experiment.gradle',f'-PvssExperimentRoot={out}',f'-PvssExperimentRounds={a.rounds}',f'-PvssExperimentWarmups={a.warmups}',f'-PvssExperimentSide={a.side}',f'-PvssExperimentModes={a.modes}',f'-PvssLodSteps={a.steps}','--console=plain','--max-workers=2']
command+=['decorationExperimentClasses'] if a.compile_only else ['runServer']
logname='compile.log' if a.compile_only else 'launch.log'
with (out/logname).open('w',encoding='utf-8') as log:result=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
if a.compile_only:
    if result.returncode:print((out/logname).read_text(encoding='utf-8',errors='replace')[-7500:]);raise SystemExit(result.returncode)
    print('Compiled:',out);raise SystemExit(0)
if result.returncode or not (out/'prediction-complete.txt').exists() or (out/'server-failure.txt').exists():
    print((out/'launch.log').read_text(encoding='utf-8',errors='replace')[-7500:]);raise SystemExit(result.returncode or 1)
print('Complete:',out)
