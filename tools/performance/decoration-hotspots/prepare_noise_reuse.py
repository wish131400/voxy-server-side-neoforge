"""Measure exact primitive sharing across existing jobs without changing production."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
project=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
args=parser.parse_args();out=args.output.resolve()
subprocess.run([sys.executable,str(Path(__file__).with_name('prepare.py')),str(out)],check=True)
crate=out/'native'
shutil.copytree(project/'tools/rust/vss-native-core',crate,ignore=shutil.ignore_patterns('target','.git'))
(out/'native-source-manifest.json').write_text(json.dumps({p.relative_to(crate).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
    for p in crate.rglob('*') if p.is_file()},indent=2))
def patch(p,old,new,count=1):
    t=p.read_text(encoding='utf-8');assert t.count(old)==count,(p,old[:80],t.count(old))
    p.write_text(t.replace(old,new),encoding='utf-8')
p=crate/'src/density.rs'
patch(p,'mod column_plan;','mod noise_reuse_experiment;\npub(crate) use noise_reuse_experiment::snapshot as reuse_snapshot;\nmod column_plan;')
shutil.copy2(Path(__file__).with_name('noise_reuse_experiment.rs'),crate/'src/density/noise_reuse_experiment.rs')
patch(p,'pub struct Graph {','pub struct Graph {\n    reuse_identity:u64,\n    reuse_capacity:usize,')
patch(p,'            noise_ids: HashMap::new(),','            noise_ids: HashMap::new(),\n            reuse_identity:noise_reuse_experiment::identity(),\n            reuse_capacity:0,',2)
patch(p,'            legacy,\n        };','            legacy,\n        };\n        let mut graph=graph;\n        graph.reuse_capacity=match doc["vss_experiment_noise_reuse"].as_u64() {Some(n @ (8192|32768|131072))=>n as usize,_=>0};')
patch(p,'        self.noises[id].sample(x, y, z)','''        if self.reuse_capacity==0 {return self.noises[id].sample(x,y,z);}
        noise_reuse_experiment::sample(self.reuse_identity,
            [id as u64,x.to_bits(),y.to_bits(),z.to_bits()],self.reuse_capacity,
            ||self.noises[id].sample(x,y,z))''')
patch(p,'            Node::Blended(n) => n.sample(x, y, z),','''            Node::Blended(n) => if self.reuse_capacity==0 {n.sample(x,y,z)} else {
                noise_reuse_experiment::sample(self.reuse_identity,
                    [(1u64<<63)|id as u64,x as u64,y as u64,z as u64],self.reuse_capacity,
                    ||n.sample(x,y,z))
            },''')
package=out/'main-java/dev/xantha/vss/client/prediction'
patch(package/'RustWorldgenBackend.java','    public static native String describe(long handle);',
      '    public static native String reuseProfile(boolean reset);\n    public static native String describe(long handle);')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            JsonObject document=RustWorldgenDocument.snapshot(generator,registriesJson,context);',
      '''            JsonObject document=RustWorldgenDocument.snapshot(generator,registriesJson,context);
            document.addProperty("vss_experiment_noise_reuse",mode.startsWith("reuse")?Integer.parseInt(mode.substring(5)):0);''')
patch(h,'                    FeatureHotspotProbe.beginRound(mode,round);',
      '                    FeatureHotspotProbe.beginRound(mode,round);\n                    RustWorldgenBackend.reuseProfile(true);')
patch(h,'                    long terrainEnd=System.nanoTime();',
      '                    long terrainEnd=System.nanoTime();\n                    Files.writeString(output.resolve("reuse-terrain-"+mode+"-"+round+".json"),RustWorldgenBackend.reuseProfile(true));')
patch(h,'                    long decorationEnd=System.nanoTime();',
      '                    long decorationEnd=System.nanoTime();\n                    Files.writeString(output.resolve("reuse-decoration-"+mode+"-"+round+".json"),RustWorldgenBackend.reuseProfile(false));')
with (crate/'src/jni_worldgen.rs').open('a',encoding='utf-8') as f:f.write('''
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_reuseProfile(
    mut e: JNIEnv, _c: JClass, reset: jni::sys::jboolean,
) -> jstring { output(&mut e,Ok(crate::density::reuse_snapshot(reset!=0))) }
''')
print(out)
