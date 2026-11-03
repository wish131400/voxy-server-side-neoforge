"""Isolate native demand loading; no experiment selector enters release sources."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
out=parser.parse_args().output.resolve()
project=Path(__file__).resolve().parents[3]
subprocess.run([sys.executable,str(Path(__file__).parent/'prepare_demand.py'),str(out)],check=True)
native=out/'native'
shutil.copytree(project/'tools/rust/vss-native-core',native,ignore=shutil.ignore_patterns('target','.git'))
overlay=out/'prototype-input/tools/rust/vss-native-core'
if overlay.exists():shutil.copytree(overlay,native,dirs_exist_ok=True)
p=native/'src/backend.rs'
t=p.read_text(encoding='utf-8')
old='''        if !display {
            let height_owner = Arc::clone(self);'''
assert t.count(old)==1
p.write_text(t.replace(old,'''        if !display && self.document["vss_experiment_demand_native"].as_bool().unwrap_or(false) {
            let height_owner = Arc::clone(self);'''),encoding='utf-8')
p=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
t=p.read_text(encoding='utf-8')
old='            JsonObject document=RustWorldgenDocument.snapshot(generator,registriesJson,context);'
assert t.count(old)==1
p.write_text(t.replace(old,old+'\n            document.addProperty("vss_experiment_demand_native",mode.equals("native")||mode.equals("combined"));'),encoding='utf-8')
(out/'native-overlay.json').write_text(json.dumps({'sourceHashes':{
 p.relative_to(native).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
 for p in native.rglob('*') if p.is_file()}},indent=2),encoding='utf-8')
print(out)
