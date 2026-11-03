"""Snapshot native sources and add experiment-only JNI phase attribution."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, sys

project = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('output', type=Path)
args = parser.parse_args()
out = args.output.resolve()
subprocess.run([sys.executable, str(Path(__file__).with_name('prepare.py')), str(out)], check=True)
crate = out / 'native'
shutil.copytree(project/'tools/rust/vss-native-core', crate,
                ignore=shutil.ignore_patterns('target', '.git'))
(out/'native-source-manifest.json').write_text(json.dumps({
    p.relative_to(crate).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
    for p in crate.rglob('*') if p.is_file()}, indent=2))

def patch(path, old, new):
    text = path.read_text(encoding='utf-8')
    assert text.count(old) == 1, (path, old[:80], text.count(old))
    path.write_text(text.replace(old,new), encoding='utf-8')

package = out/'main-java/dev/xantha/vss/client/prediction'
patch(package/'RustWorldgenBackend.java', '    public static native String describe(long handle);',
      '    public static native String nativeProfile(boolean reset);\n    public static native String describe(long handle);')
harness = out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(harness, '                    FeatureHotspotProbe.beginRound(mode,round);',
      '                    FeatureHotspotProbe.beginRound(mode,round);\n                    RustWorldgenBackend.nativeProfile(true);')
patch(harness, '                    long terrainEnd=System.nanoTime();',
      '                    long terrainEnd=System.nanoTime();\n                    Files.writeString(output.resolve("native-terrain-"+mode+"-"+round+".json"),RustWorldgenBackend.nativeProfile(true));')
patch(harness, '                    long decorationEnd=System.nanoTime();',
      '                    long decorationEnd=System.nanoTime();\n                    Files.writeString(output.resolve("native-decoration-"+mode+"-"+round+".json"),RustWorldgenBackend.nativeProfile(false));')
# The profiler includes deeply nested timers and atomics: use it for attribution,
# never as the uninstrumented performance result.
with (crate/'src/jni_worldgen.rs').open('a',encoding='utf-8') as f:
    f.write('''
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_nativeProfile(
    mut e: JNIEnv, _c: JClass, reset: jni::sys::jboolean,
) -> jstring {
    let p = &crate::prof::SURFACE;
    let value = json!({"sections": crate::prof::snapshot(), "block": crate::prof::block_snapshot(),
        "columns":crate::prof::columns(),"fallbacks":crate::prof::truncated_rebuilds(),
        "blockCalls":crate::prof::block_calls(),"fluidCalls":crate::prof::fluid_calls(),
        "preliminaryCalls":crate::prof::preliminary_calls(),"tops":crate::prof::surface_top_counts(),
        "noiseTime":crate::prof::noise_time(),"noiseEvals":crate::prof::noise_evals(),
        "evals":crate::prof::evals(),"display":crate::prof::display_snapshot(),
        "extra":EXTRA.iter().map(|c|c.load(Ordering::Relaxed)).collect::<Vec<_>>()});
    let _ = p;
    if reset != 0 {crate::prof::reset(); for c in &EXTRA {c.store(0,Ordering::Relaxed);}}
    output(&mut e,Ok(value))
}
pub(crate) static EXTRA: [std::sync::atomic::AtomicU64; 10] = [const {std::sync::atomic::AtomicU64::new(0)};10];
''')
patch(crate/'src/jni_worldgen.rs', '    surface_points_jni(e, id, input, out, count, 0)',
      '    let _scope=crate::prof::Scope::new(&EXTRA[0]);\n    surface_points_jni(e, id, input, out, count, 0)')
patch(crate/'src/jni_worldgen.rs', '        let (placed, after) = owner.placed(',
      '        let _scope=crate::prof::Scope::new(&EXTRA[1]);\n        let (placed, after) = owner.placed(')
patch(crate/'src/backend.rs', '    fn prepare_exterior_columns(&self, job: &mut Job<\'_>, points: &[(i32, i32)]) {',
      '    fn prepare_exterior_columns(&self, job: &mut Job<\'_>, points: &[(i32, i32)]) {\n        let _scope=crate::prof::Scope::new(&crate::jni_worldgen::EXTRA[2]);')
patch(crate/'src/backend.rs', '            let mut scratch = t\n                .graph\n                .scratch(x & !15, z & !15, t.cell_width, t.cell_height)\n                .ok()?;',
      '            let _scope=crate::prof::Scope::new(&crate::jni_worldgen::EXTRA[3]);\n            let mut scratch = t\n                .graph\n                .scratch(x & !15, z & !15, t.cell_width, t.cell_height)\n                .ok()?;')
patch(crate/'src/climate.rs', '    ) -> &\'a str {\n        let p = quart.map(|v| v.wrapping_mul(4));',
      '    ) -> &\'a str {\n        let _scope=crate::prof::Scope::new(&crate::jni_worldgen::EXTRA[4]);\n        crate::prof::hit(&crate::jni_worldgen::EXTRA[5]);\n        let p = quart.map(|v| v.wrapping_mul(4));')
p=crate/'src/climate.rs'
t=p.read_text()
old='''                for i in 0..6 {
                    target[i] = quantize(g.compute(ids[i], p, Mode::Raw, scratch) as f32);
                }'''
if t.count(old)==2:
    t=t.replace(old,'''                {
                let _scope=crate::prof::Scope::new(&crate::jni_worldgen::EXTRA[6]);
'''+old+'''
                }''')
else:
    old='                let target = targets::sample(g, ids, p, scratch);'
    assert t.count(old)==3
    t=t.replace(old,'''                let target = {
                    let _scope=crate::prof::Scope::new(&crate::jni_worldgen::EXTRA[6]);
                    targets::sample(g, ids, p, scratch)
                };''')
p.write_text(t,encoding='utf-8')
patch(crate/'src/surface.rs',
      '    fn summary_safe_for_biome(&self, palette:&Palette,biome:Option<&str>,dry:bool,hole:Option<bool>)->bool {',
      '    fn summary_safe_for_biome(&self, palette:&Palette,biome:Option<&str>,dry:bool,hole:Option<bool>)->bool {\n        let _scope=crate::prof::Scope::new(&crate::jni_worldgen::EXTRA[7]);')
print(out)
