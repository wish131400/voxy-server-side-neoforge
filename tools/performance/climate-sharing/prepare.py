"""Trace real Java/Rust climate requests in an isolated, full prediction runtime."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
root=Path(__file__).resolve().parents[3]
here=Path(__file__).parent
p=argparse.ArgumentParser();p.add_argument('output',type=Path);args=p.parse_args();out=args.output.resolve()
subprocess.run([sys.executable,str(root/'tools/performance/decoration-hotspots/prepare.py'),str(out)],check=True)
crate=out/'native'
shutil.copytree(root/'tools/rust/vss-native-core',crate,ignore=shutil.ignore_patterns('target','.git'))
(out/'native-source-manifest.json').write_text(json.dumps({p.relative_to(crate).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in crate.rglob('*') if p.is_file()},indent=2))
package=out/'main-java/dev/xantha/vss/client/prediction'
shutil.copy2(here/'ClimateSharingProbe.java',package/'ClimateSharingProbe.java')
shutil.copy2(here/'trace.rs',crate/'src/sharing_trace.rs')
def patch(path,old,new):
    text=path.read_text(encoding='utf-8');assert text.count(old)==1,(path,old[:80],text.count(old))
    path.write_text(text.replace(old,new),encoding='utf-8')
patch(package/'PredictionClimateSampler.java','        roots = compilation.roots();','        roots = compilation.roots();\n        for(int i=0;i<roots.length;i++)roots[i]=ClimateSharingProbe.trace(roots[i],i);')
patch(package/'RustWorldgenBackend.java','    public static native String describe(long handle);','''    public static native void sharingStage(int stage,boolean reset);
    public static native int sharingLookup(int x,int y,int z);
    public static native String sharingDump(long handle,String path);
    public static native int sharingClimate(long handle,java.nio.ByteBuffer input,java.nio.ByteBuffer output,int count);
    public static native String describe(long handle);''')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'                    FeatureHotspotProbe.beginRound(mode,round);','                    FeatureHotspotProbe.beginRound(mode,round);\n                    ClimateSharingProbe.reset(handle);')
patch(h,'                    long terrainEnd=System.nanoTime();','                    long terrainEnd=System.nanoTime();\n                    ClimateSharingProbe.stage(2);')
patch(h,'                    long decorationEnd=System.nanoTime();','                    long decorationEnd=System.nanoTime();\n                    ClimateSharingProbe.stage(3);')
patch(h,'                    String hash=fingerprint(plants,tile);','                    ClimateSharingProbe.save(output,handle,mode,round);\n                    String hash=fingerprint(plants,tile);')
patch(h,'            document.addProperty("vss_experiment_scratch_pool",mode.contains("scratch"));','            Files.writeString(output.resolve("native-document.json"),document.toString());\n            document.addProperty("vss_experiment_scratch_pool",mode.contains("scratch"));')
patch(package/'PredictionDecorationLevel.java','''    private int[] nativeColumn(RustTerrainSampler rust, int x, int z) {''','''    private int[] nativeColumn(RustTerrainSampler rust, int x, int z) {
        ClimateSharingProbe.column(x,z,displayTerrain);''')
patch(package/'RustTerrainSampler.java','''    int[] surfaceRecord(int x, int z) {''','''    int[] surfaceRecord(int x, int z) {
        ClimateSharingProbe.column(x,z,false);''')
patch(package/'FeatureHotspotProbe.java','''        long started=System.nanoTime();
        try { return RustWorldgenBackend.surfacePoints(handle,input,output,count); }''','''        ClimateSharingProbe.submitted(input,count,false);
        long started=System.nanoTime();
        try { return RustWorldgenBackend.surfacePoints(handle,input,output,count); }''')
patch(package/'FeatureHotspotProbe.java','''        try {return RustWorldgenBackend.decorationPoints(handle,input,output,count);}''','''        ClimateSharingProbe.submitted(input,count,true);
        try {return RustWorldgenBackend.decorationPoints(handle,input,output,count);}''')
patch(crate/'src/lib.rs','pub mod surface;','pub mod surface;\nmod sharing_trace;')
patch(crate/'src/climate/targets.rs','if entry.ids==*ids && entry.p==p && entry.beard==beard {return entry.target;}','if entry.ids==*ids && entry.p==p && entry.beard==beard {crate::sharing_trace::target(p,entry.target,None);return entry.target;}')
patch(crate/'src/climate/targets.rs','    for i in 0..6 {target[i]=super::quantize(g.compute(ids[i],p,Mode::Raw,scratch) as f32);}','''    let mut bits=[0;6];
    for i in 0..6 {let value=g.compute(ids[i],p,Mode::Raw,scratch);bits[i]=value.to_bits();target[i]=super::quantize(value as f32);}
    crate::sharing_trace::target(p,target,Some(bits));''')
patch(crate/'src/backend.rs','''        let b = self.biomes.get(name).ok_or("missing surface biome")?;
        let cold = self.colors.temperature(b, p) < 0.15;''','''        let b = self.biomes.get(name).ok_or("missing surface biome")?;
        let record_start=std::time::Instant::now();
        let cold = self.colors.temperature(b, p) < 0.15;
        let cold_ns=record_start.elapsed().as_nanos() as u64;''')
patch(crate/'src/backend.rs','''        Ok(SurfaceColumn {
            values: [
                floor,''','''        let color_start=std::time::Instant::now();
        let rgb=[self.colors.grass(b,x as f64,z as f64) as i32,self.colors.foliage(b) as i32,b.water as i32];
        crate::sharing_trace::record(record_start.elapsed().as_nanos() as u64,cold_ns,color_start.elapsed().as_nanos() as u64);
        Ok(SurfaceColumn {
            values: [
                floor,''')
patch(crate/'src/backend.rs','''                self.colors.grass(b, x as f64, z as f64) as i32,
                self.colors.foliage(b) as i32,
                b.water as i32,''','''                rgb[0],rgb[1],rgb[2],''')
patch(crate/'src/backend.rs','''    pub fn decoration_points(&self, points: &[(i32,i32)], display: bool) -> Result<Vec<SurfaceColumn>> {''','''    pub fn decoration_points(&self, points: &[(i32,i32)], display: bool) -> Result<Vec<SurfaceColumn>> {
        for &(x,z) in points{crate::sharing_trace::column([x,z],if display{2}else{1});}''')
patch(crate/'src/blocks.rs','''            match proxy.value(i, self.origin[1], self.size[1]) {''','''            let column=i/self.size[1];
            crate::sharing_trace::column([self.origin[0]+(column/self.size[2]) as i32,self.origin[2]+(column%self.size[2]) as i32],4);
            match proxy.value(i, self.origin[1], self.size[1]) {''')
patch(crate/'src/blocks.rs','''            match proxy.column(column) {''','''            crate::sharing_trace::column([x,z],4);
            match proxy.column(column) {''')
patch(crate/'src/blocks.rs','''        for (i, slot) in blocks.iter_mut().enumerate() {
            *slot =''','''        for (i, slot) in blocks.iter_mut().enumerate() {
            let column=i/self.size[1];
            crate::sharing_trace::column([self.origin[0]+(column/self.size[2]) as i32,self.origin[2]+(column%self.size[2]) as i32],4);
            *slot =''')
with (crate/'src/backend.rs').open('a',encoding='utf-8') as f:f.write('''
impl World {
    pub(crate) fn sharing_climate(&self,points:&[[i32;3]])->Result<Vec<u8>> {
        let g=&self.terrain.graph;
        let ids=[g.root("temperature")?,g.root("vegetation")?,g.root("continents")?,g.root("erosion")?,g.root("depth")?,g.root("ridges")?];
        let mut s=g.raw_scratch()?;let mut out=Vec::with_capacity(points.len()*96);
        for p in points {
            let raw=ids.map(|id|g.compute(id,*p,crate::density::Mode::Raw,&mut s));
            for v in raw{out.extend(v.to_bits().to_le_bytes());}
            for v in raw{out.extend(((v as f32*10000.0f32) as i64).to_le_bytes());}
        }
        Ok(out)
    }
}
''')
with (crate/'src/jni_worldgen.rs').open('a',encoding='utf-8') as f:f.write('''
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_sharingStage(
    _e:JNIEnv,_c:JClass,stage:jint,reset:jni::sys::jboolean) {crate::sharing_trace::stage(stage as u32,reset!=0);}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_sharingLookup(
    _e:JNIEnv,_c:JClass,x:jint,y:jint,z:jint)->jint {crate::sharing_trace::lookup([x,y,z])}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_sharingDump(
    mut e:JNIEnv,_c:JClass,_id:jlong,path:JString)->jstring {
    let result=guarded(||crate::sharing_trace::dump(&text(&mut e,path,32768)?));output(&mut e,result)
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_sharingClimate(
    mut e:JNIEnv,_c:JClass,id:jlong,input:JByteBuffer,out:JByteBuffer,count:jint)->jint {
    let result=guarded(|| {
        if !(0..=256).contains(&count){return Err("climate batch count".into());}
        let p=buffer(&mut e,&input,count as usize*12,false)?;
        buffer(&mut e,&out,count as usize*96,true)?;
        let bytes=unsafe{std::slice::from_raw_parts(p,count as usize*12)};
        let points=bytes.chunks_exact(12).map(|r|std::array::from_fn(|i|i32::from_le_bytes(r[i*4..i*4+4].try_into().unwrap()))).collect::<Vec<_>>();
        put(&mut e,&out,&world(id)?.sharing_climate(&points)?)?;Ok(count)
    });match result {Ok(n)=>n,Err(err)=>{fail(&mut e,err);-1}}
}
''')
(out/'sharing-manifest.json').write_text(json.dumps({
    'diagnosticFiles':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in here.iterdir() if p.suffix in ['.py','.java','.rs']},
    'generatedInputs':{p.relative_to(out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
        for folder in ['main-java','harness-java','native/src'] for p in (out/folder).rglob('*') if p.is_file()},
    'scope':'Instrumented diagnostic; timings must not be interpreted as an optimization benchmark.'},indent=2))
print(out)
