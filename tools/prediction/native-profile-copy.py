"""Build an isolated, instrumented copy. Never packages probes into the mod."""
import pathlib, shutil, sys, re
src = pathlib.Path('tools/rust/vss-native-core')
dst = pathlib.Path(sys.argv[1])
dst.mkdir(parents=True, exist_ok=True)
shutil.copytree(src/'src', dst/'src', dirs_exist_ok=True)
for name in ('Cargo.toml', 'Cargo.lock'):
    shutil.copy2(src/name, dst/name)
with (dst/'src/lib.rs').open('a') as f:
    f.write('\npub mod perf_probe;\n')
(dst/'src/perf_probe.rs').write_text(r'''
use std::{cell::RefCell, collections::BTreeMap, time::Instant};
#[derive(Default)] struct Data { stack: Vec<u128>, metrics: BTreeMap<&'static str,(u64,u128,u128)> }
thread_local! { static DATA: RefCell<Data> = RefCell::new(Data::default()); }
pub struct Span { name: &'static str, start: Instant }
pub fn enter(name: &'static str) -> Span {
    DATA.with(|d|d.borrow_mut().stack.push(0)); Span {name, start:Instant::now()}
}
impl Drop for Span { fn drop(&mut self) {
    let elapsed=self.start.elapsed().as_nanos();
    DATA.with(|d| {let mut d=d.borrow_mut();let child=d.stack.pop().unwrap();
        if let Some(parent)=d.stack.last_mut(){*parent+=elapsed;}
        let entry=d.metrics.entry(self.name).or_default();entry.0+=1;entry.1+=elapsed;entry.2+=elapsed.saturating_sub(child);
    });
}}
pub fn reset(){DATA.with(|d|*d.borrow_mut()=Data::default());}
pub fn count(name: &'static str,n:u64){DATA.with(|d|d.borrow_mut().metrics.entry(name).or_default().0+=n);}
pub fn report()->serde_json::Value{DATA.with(|d|serde_json::Value::Object(d.borrow().metrics.iter().map(|(k,v)|
    (k.to_string(),serde_json::json!({"calls":v.0,"inclusive_ms":v.1 as f64/1e6,"exclusive_ms":v.2 as f64/1e6}))).collect()))}
''', encoding='utf-8')
# Each span subtracts instrumented children: exclusive columns can be summed.
targets = {
    'terrain.rs': {'job':'job_setup', 'block':'density_cell', 'substance':'aquifer', 'ore':'ore_veins', 'preliminary':'preliminary_height'},
    'surface.rs': {'apply_chunk':'surface_chunk', 'apply_column':'surface_rules'},
    'climate.rs': {'sample':'biome_source'},
    'backend.rs': {'surface_region':'region_assembly','column_record':'column_record','surface_columns':'surface_columns',
                   'generate_surface_point_depth':'sparse_column','surface_proxy':'proxy_assembly'}
}
for filename, methods in targets.items():
    p=dst/'src'/filename
    text=p.read_text(encoding='utf-8')
    for name, label in methods.items():
        # Function signatures contain no braces before their body.
        pattern=rf'(\b(?:pub )?fn {name}(?:<[^\n]*>)?\([^{{]*?\{{)'
        text,n=re.subn(pattern, lambda m:m[0]+f'\n        let _perf_span = crate::perf_probe::enter("{label}");',text)
        if n!=1: raise RuntimeError(f'{filename}/{name}: {n} matches')
    p.write_text(text,encoding='utf-8')
# Count actual cell rebuilds after the cache-hit return, not all block reads.
p=dst/'src/density.rs'; text=p.read_text(encoding='utf-8')
needle='''        if s.prepared_x != Some(base[0]) {'''
assert text.count(needle)==1
text=text.replace(needle,'''        let _perf_cell = crate::perf_probe::enter("cell_rebuild");
        crate::perf_probe::count("cell_output_values", (s.width * s.width * s.height) as u64);
'''+needle)
p.write_text(text,encoding='utf-8')
# Primitive noise time is separate from density graph/cell bookkeeping.
p=dst/'src/noise.rs'; text=p.read_text(encoding='utf-8')
needle='''        const SCALE: f64 = 1.0181268882175227;'''
assert text.count(needle)==1
text=text.replace(needle,'''        let _perf_noise = crate::perf_probe::enter("normal_noise");
'''+needle)
p.write_text(text,encoding='utf-8')
p=dst/'src/blended_noise.rs'; text=p.read_text(encoding='utf-8')
needle='''    pub fn sample(&self, x: i32, y: i32, z: i32) -> f64 {'''
assert text.count(needle)==1
text=text.replace(needle,needle+'\n        let _perf_noise = crate::perf_probe::enter("blended_noise");')
p.write_text(text,encoding='utf-8')
(dst/'examples').mkdir(exist_ok=True)
shutil.copy2('tools/prediction/native_deep_bench.rs',dst/'examples/native_deep_bench.rs')
print(dst)
