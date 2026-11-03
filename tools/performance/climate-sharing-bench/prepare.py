"""Bounded horizontal-input reuse experiment; all mutations stay in an isolated snapshot."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
root=Path(__file__).resolve().parents[3];here=Path(__file__).parent
p=argparse.ArgumentParser();p.add_argument('output',type=Path);args=p.parse_args();out=args.output.resolve()
subprocess.run([sys.executable,str(root/'tools/performance/decoration-hotspots/prepare.py'),str(out)],check=True)
crate=out/'native';shutil.copytree(root/'tools/rust/vss-native-core',crate,ignore=shutil.ignore_patterns('target','.git'))
package=out/'main-java/dev/xantha/vss/client/prediction'
shutil.copy2(here/'HorizontalSharing.java',package/'HorizontalSharing.java')
shutil.copy2(here/'horizontal.rs',crate/'src/density/horizontal_experiment.rs')
def patch(path,old,new,count=1):
    t=path.read_text(encoding='utf-8');assert t.count(old)==count,(path,old[:80],t.count(old));path.write_text(t.replace(old,new),encoding='utf-8')
patch(package/'PredictionClimateSampler.java','        compilation = new DensityCompilation(roots);','        roots = HorizontalSharing.wrap(roots);\n        compilation = new DensityCompilation(roots);')
patch(package/'RustWorldgenBackend.java','    public static native String describe(long handle);','''    public static native int horizontalLookup(long handle,java.nio.ByteBuffer input,java.nio.ByteBuffer output,int count);
    public static native String horizontalStats(long handle,boolean reset);
    public static native String describe(long handle);''')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            DecorationExperimentSettings.select(mode);','            DecorationExperimentSettings.select(mode);\n            HorizontalSharing.select(mode);')
patch(h,'            document.addProperty("vss_experiment_scratch_pool",mode.contains("scratch"));','            document.addProperty("vss_experiment_horizontal",mode.contains("native") || mode.contains("cross"));\n            document.addProperty("vss_experiment_scratch_pool",mode.contains("scratch"));')
patch(h,'                    FeatureHotspotProbe.beginRound(mode,round);','                    FeatureHotspotProbe.beginRound(mode,round);\n                    HorizontalSharing.reset(handle);')
patch(h,'                    row.put("mode",mode);','                    row.put("horizontal",HorizontalSharing.stats());\n                    row.put("mode",mode);')
d=crate/'src/density.rs'
patch(d,'mod column_plan;','mod column_plan;\nmod horizontal_experiment;')
patch(d,'    nodes: Vec<Node>,','    nodes: Vec<Node>,\n    experiment_horizontal: Option<horizontal_experiment::Shared>,')
patch(d,'            nodes: vec![],','            nodes: vec![],\n            experiment_horizontal: None,',2)
patch(d,'        builder.graph.surface_plan = surface_plan::SurfacePlan::compile(&builder.graph);','''        builder.graph.surface_plan = surface_plan::SurfacePlan::compile(&builder.graph);
        if doc["vss_experiment_horizontal"].as_bool()==Some(true) {builder.graph.experiment_horizontal=Some(horizontal_experiment::Shared::new(&builder.graph)?);}''')
patch(d,'    pub fn compute(&self, id: Id, p: [i32; 3], mode: Mode, s: &mut Scratch) -> f64 {','''    pub fn compute(&self, id: Id, p: [i32; 3], mode: Mode, s: &mut Scratch) -> f64 {
        if let Some(shared)=self.experiment_horizontal.as_ref() {if let Some(value)=shared.get(id,p,mode){return value}}''')
patch(d,'        s.memo[id] = Some((p, mode, v));','        if let Some(shared)=self.experiment_horizontal.as_ref(){shared.put(id,p,mode,v);}\n        s.memo[id] = Some((p, mode, v));')
with (crate/'src/backend.rs').open('a',encoding='utf-8') as f:f.write('''
impl World {
    pub(crate) fn horizontal_lookup(&self,points:&[(i32,i32)])->Vec<u8> {self.terrain.graph.horizontal_lookup(points)}
    pub(crate) fn horizontal_stats(&self,reset:bool)->serde_json::Value {self.terrain.graph.horizontal_stats(reset)}
}
''')
with (crate/'src/jni_worldgen.rs').open('a',encoding='utf-8') as f:f.write('''
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_horizontalLookup(
    mut e:JNIEnv,_c:JClass,id:jlong,input:JByteBuffer,out:JByteBuffer,count:jint)->jint {
    let result=guarded(|| {
        if !(0..=16).contains(&count){return Err("horizontal batch count".into());}
        let p=buffer(&mut e,&input,count as usize*8,false)?;
        buffer(&mut e,&out,count as usize*56,true)?;
        let bytes=unsafe{std::slice::from_raw_parts(p,count as usize*8)};
        let points=bytes.chunks_exact(8).map(|r|(i32::from_le_bytes(r[0..4].try_into().unwrap()),i32::from_le_bytes(r[4..8].try_into().unwrap()))).collect::<Vec<_>>();
        put(&mut e,&out,&world(id)?.horizontal_lookup(&points))?;Ok(count)
    });match result {Ok(n)=>n,Err(err)=>{fail(&mut e,err);-1}}
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_horizontalStats(
    mut e:JNIEnv,_c:JClass,id:jlong,reset:jni::sys::jboolean)->jstring {
    let result=guarded(||Ok(world(id)?.horizontal_stats(reset!=0)));output(&mut e,result)
}
''')
(out/'bench-source-manifest.json').write_text(json.dumps({'inputs':{p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['src/main/java','src/main/resources','tools/rust/vss-native-core/src'] for p in (root/folder).rglob('*') if p.is_file()},'scope':'Isolated full prediction path; bounded horizontal input reuse, original Java biome routing.'},indent=2))
print(out)
