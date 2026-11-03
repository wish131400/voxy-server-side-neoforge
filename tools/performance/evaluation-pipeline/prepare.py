"""Patch an isolated, frozen version-loading benchmark; never target production."""
from pathlib import Path
import argparse
import shutil

HERE=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();root=a.root.resolve()
assert (root/'native-baseline-snapshot.json').is_file()
native=root/'native';java=root/'baseline/main-java/dev/xantha/vss/client/prediction'

def edit(path,before,after,count=1):
    text=path.read_text(encoding='utf-8')
    assert text.count(before)==count,(path,before[:100],text.count(before),count)
    path.write_text(text.replace(before,after),encoding='utf-8')

shutil.copy2(HERE/'probe.rs',native/'src/evaluation.rs')
edit(native/'src/lib.rs','pub mod surface;','pub mod surface;\nmod evaluation;')
shutil.copy2(HERE/'query_scope.rs',native/'src/backend/query_scope.rs')
edit(native/'src/backend.rs','mod tint;','mod tint;\npub(crate) mod query_scope;')
tint=native/'src/backend/tint.rs'
edit(tint,'    pub(super) fn take_raw_work(&self) -> Result<Scratch> {','''    pub(super) fn take_raw_work(&self) -> Result<Scratch> {
        let _trace=crate::evaluation::timer(3);
        if crate::evaluation::enabled("scope") {
            if let Some(scratch)=super::query_scope::take(self) { return Ok(scratch); }
        }''')
edit(tint,'    pub(super) fn return_raw_work(&self, scratch: Scratch) -> Result<()> {','''    pub(super) fn return_raw_work(&self, scratch: Scratch) -> Result<()> {
        let _trace=crate::evaluation::timer(5);
        let scratch=if crate::evaluation::enabled("scope") {
            match super::query_scope::put(self,scratch) { None=>return Ok(()),Some(s)=>s }
        } else { scratch };''')
edit(tint,'let mut scratch = self.take_raw_work()?;','let mut scratch = {let _trace=crate::evaluation::timer(8);self.take_raw_work()?};')
edit(tint,'self.return_raw_work(scratch)?;','{let _trace=crate::evaluation::timer(9);self.return_raw_work(scratch)?;}')
edit(tint,'    pub fn noise_biome_index(&self, q: Pos) -> Result<i32> {','''    pub fn noise_biome_index(&self, q: Pos) -> Result<i32> {
        let _trace=crate::evaluation::timer(1);''')
edit(tint,'        if let Some(b) = shard.lock().map_err(|_| "biome color cache lock")?.values.get(&q).copied() {','''        let cached={let _trace=crate::evaluation::timer(2);
            shard.lock().map_err(|_| "biome color cache lock")?.values.get(&q).copied()};
        if let Some(b) = cached {''')
edit(tint,'            let name = self.source.sample(&self.terrain.graph, q, &mut scratch, &mut None);','''            let name = {let _trace=crate::evaluation::timer(4);
                self.source.sample(&self.terrain.graph, q, &mut scratch, &mut None)};''')
edit(tint,'        shard.lock().map_err(|_| "biome color cache lock")?.insert(q, biome);','''        {let _trace=crate::evaluation::timer(6);
        shard.lock().map_err(|_| "biome color cache lock")?.insert(q, biome);}''')
jni=native/'src/jni_worldgen.rs'
edit(jni,'        if !(0..=256).contains(&count) { return Err("biome batch count".into()); }','''        let _trace=crate::evaluation::timer(0);
        if !(0..=256).contains(&count) { return Err("biome batch count".into()); }''')
edit(jni,'Ok(json!({"pages":s[0],','Ok(json!({"evaluationProbe":crate::evaluation::snapshot(),"pages":s[0],')
with jni.open('a',encoding='utf-8') as f:f.write('''
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_beginRawQueries(
    mut e:JNIEnv,_c:JClass,id:jlong)->jlong {
    if !crate::evaluation::enabled("scope") { return 0; }
    let result=guarded(|| { let token=IDS.fetch_add(1,Ordering::Relaxed);
        crate::backend::query_scope::begin(world(id)?,token)?;Ok(token) });
    match result {Ok(token)=>token,Err(err)=>{fail(&mut e,err);0}}
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_endRawQueries(
    mut e:JNIEnv,_c:JClass,token:jlong) {
    if token==0{return;}
    if let Err(err)=guarded(||crate::backend::query_scope::end(token)){fail(&mut e,err);}
}
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_placedFeatureStatus(
    mut e:JNIEnv,_c:JClass,id:jlong,name:JString,x:jint,z:jint,index:jint,step:jint)->jint {
    let result=guarded(||{let name=text(&mut e,name,32768)?;let v=volume(id)?;
        let mut v=v.lock().map_err(|_|"volume lock")?;let owner=v.owner.clone();
        let (placed,_)=owner.placed(&mut v.volume,&name,x,z,index,step)?;Ok(i32::from(placed))});
    match result{Ok(status)=>status,Err(err)=>{fail(&mut e,err);-1}}
}
''')
surface=native/'src/surface.rs';exterior=native/'src/surface/exterior.rs'
shutil.copy2(HERE/'tests.rs',native/'src/surface/evaluation_tests.rs')
edit(surface,'mod exterior;','mod exterior;\n#[cfg(test)]\nmod evaluation_tests;')
shutil.copy2(HERE/'linear.rs',native/'src/surface/linear.rs')
shutil.copy2(HERE/'noise_memo.rs',native/'src/surface/noise_memo.rs')
edit(surface,'mod exterior;','mod exterior;\nmod linear;\nmod noise_memo;\nuse noise_memo::NoiseMemo;\nuse std::cell::RefCell;')
edit(surface,"struct Context<'a> {","struct Context<'a> {\n    noise: &'a RefCell<NoiseMemo>,")
edit(surface,'    biome_rules: std::collections::HashMap<String, BiomeRule>,','''    biome_rules: std::collections::HashMap<String, BiomeRule>,
    programs:std::collections::HashMap<String,linear::Program>,
    fallback_program:linear::Program,''')
edit(surface,'            biome_rules: std::collections::HashMap::new(),','''            biome_rules: std::collections::HashMap::new(),
            programs:std::collections::HashMap::new(),
            fallback_program:linear::Program::compile(&rule),''')
edit(surface,'        let mut predicates = Vec::new();','''        for (name,plan) in &surface.biome_rules {
            surface.programs.insert(name.clone(),linear::Program::compile(&plan.rule));
        }
        let mut predicates = Vec::new();''')
edit(surface,'    fn rule(&self, rule: &Rule, graph:', '''    fn evaluate(&self,rule:&Rule,graph:&Graph,c:&Context<'_>,scratch:&mut Option<&mut Scratch>)->Option<StateId> {
        if crate::evaluation::enabled("linear") {
            self.programs.get(c.biome).unwrap_or(&self.fallback_program).eval(self,graph,c,scratch)
        } else { self.rule(rule,graph,c,scratch) }
    }
    fn rule(&self, rule: &Rule, graph:''')
edit(surface,'        let original_top = world.height(x, z, false);','''        let noise=RefCell::new(NoiseMemo::new());
        let original_top = world.height(x, z, false);''')
edit(surface,'        let (rule, needs_cold, needs_secondary) = self.biome_rule(name);','''        let noise=RefCell::new(NoiseMemo::new());
        let (rule, needs_cold, needs_secondary) = self.biome_rule(name);''')
edit(surface,'                    let context = Context {','                    let context = Context { noise:&noise,')
edit(surface,'            let c = Context { p, biome:', '            let c = Context { noise:&noise,p, biome:')
edit(surface,'self.rule(rule, graph, &context,','self.evaluate(rule, graph, &context,')
edit(surface,'self.rule(rule, &t.graph, &c,','self.evaluate(rule, &t.graph, &c,')
edit(surface,'                let v = g.noise(*id, c.p[0] as f64, if *is_3d { c.p[1] as f64 } else { 0. }, c.p[2] as f64);','''                let _trace=crate::evaluation::timer(7);
                let v = c.noise.borrow_mut().sample(g,*id,c.p[0] as f64,if *is_3d {c.p[1] as f64}else{0.},c.p[2] as f64);''')
edit(exterior,'        let graph = &job.terrain.graph;','''        let noise=RefCell::new(NoiseMemo::new());
        let graph = &job.terrain.graph;''')
edit(exterior,'                    let c = Context {','                    let c = Context { noise:&noise,')
edit(exterior,'self.rule(rule, graph, &c,','self.evaluate(rule, graph, &c,')
# Recompile tests may replace the rule; rebuild both experimental plans as well.
edit(surface,'        self.rule = rule;','''        self.fallback_program=linear::Program::compile(&rule);
        self.rule = rule;''')
edit(surface,'            *plan = BiomeRule {','''            self.programs.insert(name.clone(),linear::Program::compile(&rule));
            *plan = BiomeRule {''')
# Any direct Context test fixture needs an explicit empty local memo.
for test in [*(native/'src/surface').glob('*tests.rs'),native/'src/surface/material.rs']:
    if test.name=='evaluation_tests.rs':continue
    text=test.read_text(encoding='utf-8')
    text=text.replace('Context {','Context { noise:&RefCell::new(NoiseMemo::new()),')
    test.write_text(text,encoding='utf-8')
backend=java/'RustWorldgenBackend.java'
edit(backend,'    public static native int abi();','''    public static native int abi();
    public static native long beginRawQueries(long world);
    public static native void endRawQueries(long token);
    public static native int placedFeatureStatus(long result,String name,int x,int z,int index,int step);''')
edit(java/'PredictionVegetation.java','try (var nativeStage = terrain instanceof RustTerrainSampler rust','try (var queries=new EvaluationQueryScope(terrain); var nativeStage = terrain instanceof RustTerrainSampler rust')
(java/'EvaluationQueryScope.java').write_text('''package dev.xantha.vss.client.prediction;
final class EvaluationQueryScope implements AutoCloseable {
 private long token;
 EvaluationQueryScope(ClientTerrainSampler sampler) {
  if (System.getenv("VSS_EVAL_MODE")!=null && java.util.Arrays.asList(System.getenv("VSS_EVAL_MODE").split(",")).contains("scope") && sampler instanceof RustTerrainSampler rust)
   token=RustWorldgenBackend.beginRawQueries(rust.handle());
 }
 public void close(){if(token!=0){long old=token;token=0;RustWorldgenBackend.endRawQueries(old);}}
}
''',encoding='utf-8')
edit(java/'RustVegetationStage.java','    private static final int MAX_EDITS =', '''    private static final boolean STATUS=java.util.Arrays.asList(java.util.Objects.toString(System.getenv("VSS_EVAL_MODE"),"").split(",")).contains("json");
    private static final int MAX_EDITS =''')
edit(java/'RustVegetationStage.java','            RustWorldgenBackend.placedFeature(volume, features.name(index), x, z, index, step);','''            if(STATUS) RustWorldgenBackend.placedFeatureStatus(volume, features.name(index), x, z, index, step);
            else RustWorldgenBackend.placedFeature(volume, features.name(index), x, z, index, step);''')
sampler=java/'RustTerrainSampler.java'
edit(sampler,'    @Override net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiome(int x, int y, int z) {','''    @Override net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiome(int x,int y,int z) {
        if(!EvaluationJavaProbe.ENABLED)return noiseBiomeUntraced(x,y,z);
        long start=System.nanoTime();try{return noiseBiomeUntraced(x,y,z);}finally{EvaluationJavaProbe.nanos+=System.nanoTime()-start;EvaluationJavaProbe.calls++;}
    }
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiomeUntraced(int x, int y, int z) {''')
(java/'EvaluationJavaProbe.java').write_text('''package dev.xantha.vss.client.prediction;
final class EvaluationJavaProbe {static final boolean ENABLED=System.getenv("VSS_EVAL_TRACE")!=null;static long nanos,calls;}
''',encoding='utf-8')
edit(java/'CrossVersionBenchmark.java','row.put("round",round);','row.put("biomeJavaProbe",Map.of("calls",EvaluationJavaProbe.calls,"nanos",EvaluationJavaProbe.nanos));row.put("round",round);')
print('Prepared isolated candidates:',root)
