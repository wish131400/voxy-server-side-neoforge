"""Trace real dependency work in an isolated full-runtime decoration snapshot."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, sys

project=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
args=parser.parse_args()
out=args.output.resolve()
subprocess.run([sys.executable,str(Path(__file__).with_name('prepare.py')),str(out)],check=True)
crate=out/'native'
shutil.copytree(project/'tools/rust/vss-native-core',crate,ignore=shutil.ignore_patterns('target','.git'))
manifest={p.relative_to(crate).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
          for p in crate.rglob('*') if p.is_file()}
(out/'native-source-manifest.json').write_text(json.dumps(manifest,indent=2))

def patch(p,old,new):
    text=p.read_text(encoding='utf-8')
    assert text.count(old)==1,(p,old[:80],text.count(old))
    p.write_text(text.replace(old,new),encoding='utf-8')

p=crate/'src/density.rs'
patch(p,'mod column_plan;','mod dependency_trace;\npub(crate) use dependency_trace::snapshot as dependency_snapshot;\nmod column_plan;')
shutil.copy2(Path(__file__).with_name('dependency_trace.rs'),crate/'src/density/dependency_trace.rs')
patch(p,'    pub fn noise(&self, id: usize, x: f64, y: f64, z: f64) -> f64 {',
        '    pub fn noise(&self, id: usize, x: f64, y: f64, z: f64) -> f64 {\n        dependency_trace::noise(id,x,y,z);')
patch(p,'    pub fn compute(&self, id: Id, p: [i32; 3], mode: Mode, s: &mut Scratch) -> f64 {',
        '    pub fn compute(&self, id: Id, p: [i32; 3], mode: Mode, s: &mut Scratch) -> f64 {\n        dependency_trace::call(self,id,mode);')
patch(p,'if let Some(v) = s.horizontal[slot].get(p[0], p[2], mode) { return v; }',
        'if let Some(v) = s.horizontal[slot].get(p[0], p[2], mode) { dependency_trace::horizontal(id); return v; }')
patch(p,'            if q == p && m == mode {\n                return v;',
        '            if q == p && m == mode {\n                dependency_trace::memo(id);\n                return v;')
patch(p,'        let v = match &self.nodes[id] {',
        '        dependency_trace::dispatch(id,mode);\n        let v = match &self.nodes[id] {')

package=out/'main-java/dev/xantha/vss/client/prediction'
patch(package/'RustWorldgenBackend.java','    public static native String describe(long handle);',
      '    public static native String dependencyProfile(boolean reset);\n    public static native String describe(long handle);')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'                    FeatureHotspotProbe.beginRound(mode,round);',
      '                    FeatureHotspotProbe.beginRound(mode,round);\n                    RustWorldgenBackend.dependencyProfile(true);')
patch(h,'                    long terrainEnd=System.nanoTime();',
      '                    long terrainEnd=System.nanoTime();\n                    Files.writeString(output.resolve("dependency-terrain-"+mode+"-"+round+".json"),RustWorldgenBackend.dependencyProfile(true));')
patch(h,'                    long decorationEnd=System.nanoTime();',
      '                    long decorationEnd=System.nanoTime();\n                    Files.writeString(output.resolve("dependency-decoration-"+mode+"-"+round+".json"),RustWorldgenBackend.dependencyProfile(false));')
with (crate/'src/jni_worldgen.rs').open('a',encoding='utf-8') as f:
    f.write('''
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_dependencyProfile(
    mut e: JNIEnv, _c: JClass, reset: jni::sys::jboolean,
) -> jstring { output(&mut e,Ok(crate::density::dependency_snapshot(reset!=0))) }
''')
print(out)
