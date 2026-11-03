"""Build and run an already prepared isolated snapshot, requiring runtime success."""
from pathlib import Path
import argparse, os, shutil, subprocess

p=argparse.ArgumentParser();p.add_argument('output',type=Path)
p.add_argument('--java-home',type=Path,default=Path('C:/Program Files/Java/jdk-21'))
p.add_argument('--reverse',action='store_true')
p.add_argument('--diagnostic',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parents[3];out=a.output.resolve();crate=out/'native'
env=os.environ.copy();env['JAVA_HOME']=str(a.java_home)
def run(command,log):
    with (out/log).open('w',encoding='utf-8') as stream:
        result=subprocess.run(command,cwd=root,env=env,stdout=stream,stderr=subprocess.STDOUT)
    if result.returncode:
        print((out/log).read_text(encoding='utf-8',errors='replace')[-6000:])
        raise SystemExit(result.returncode)
    print(log,'passed',flush=True)
run(['cargo','build','--release','--offline','--locked','--manifest-path',str(crate/'Cargo.toml')],'native-build.log')
if not a.diagnostic:
    run(['cargo','test','--release','--offline','--locked','--manifest-path',str(crate/'Cargo.toml'),'--lib','dry_air_experiment'],'pressure-checks.log')
    run(['cargo','test','--release','--offline','--locked','--manifest-path',str(crate/'Cargo.toml'),'--lib','aquifer_nearby_experiment'],'nearest-checks.log')
    run(['cargo','test','--release','--offline','--locked','--manifest-path',str(crate/'Cargo.toml'),'--test','height_air_checks'],'column-checks.log')
shutil.copy2(crate/'target/release/vss_native_core.dll',out/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')
for name in ['prediction-complete.txt','server-failure.txt']:
    if (out/name).exists():raise SystemExit(f'Refusing to reuse a runtime result: {out/name}')
modes='baseline,cross,cross_dry,cross_dry_nearby'
if a.reverse:modes='baseline,cross,cross_dry_nearby,cross_dry'
run([str(root/'gradlew.bat'),f'-Dorg.gradle.java.home={a.java_home}','--offline','--project-cache-dir',str(out/'project-cache'),
    '-I','tools/performance/decoration-experiment/experiment.gradle',f'-PvssExperimentRoot={out}',
    '-PvssExperimentRounds=5',f'-PvssExperimentWarmups={1 if a.diagnostic else 2}','-PvssExperimentSide=16',
    f'-PvssExperimentModes={modes}','--console=plain','--max-workers=2','runServer'],'launch.log')
assert (out/'prediction-complete.txt').read_text().strip()=='success'
assert not (out/'server-failure.txt').exists()
print('Full path completed:',out,flush=True)
