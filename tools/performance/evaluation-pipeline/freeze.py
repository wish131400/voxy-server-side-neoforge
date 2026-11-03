"""Freeze one explicitly identified release into an isolated experiment tree."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,sys
HERE=Path(__file__).resolve().parent
p=argparse.ArgumentParser()
p.add_argument('output',type=Path)
p.add_argument('--project',type=Path,default=HERE.parents[2].parent/'voxy-server-side-Horizon')
p.add_argument('--jdk',default=os.environ.get('JAVA_HOME'))
p.add_argument('--build-id',required=True,help='Expected native build id; prevents silently changing the control')
a=p.parse_args()
if not a.jdk:p.error('--jdk or JAVA_HOME is required')
project=a.project.resolve();out=a.output.resolve()
metadata=json.loads((project/'src/main/resources/META-INF/vss-native-build.json').read_text(encoding='utf-8'))
if metadata.get('validation',{}).get('buildId')!=a.build_id:p.error('The bundled native build differs from --build-id')
out.mkdir(parents=True,exist_ok=False)
subprocess.run([sys.executable,str(HERE.parent/'version-loading/run.py'),'fabric','26.3',str(out/'baseline'),
                '--fabric-root',str(project),'--jdk',a.jdk,'--compile-only'],check=True)
native=project/'tools/rust/vss-native-core'
shutil.copytree(native,out/'native',ignore=shutil.ignore_patterns('target','.git'))
snapshot={f.relative_to(out/'native').as_posix():hashlib.sha256(f.read_bytes()).hexdigest() for f in (out/'native').rglob('*') if f.is_file()}
(out/'native-baseline-snapshot.json').write_text(json.dumps(snapshot,indent=2),encoding='utf-8')
shutil.copy2(out/'baseline/main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll',out/'baseline.dll')
print('Frozen baseline',a.build_id,'at',out)
