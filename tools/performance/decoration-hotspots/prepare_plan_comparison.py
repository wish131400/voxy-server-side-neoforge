"""Enable the old recursive analysis only inside an isolated comparison DLL."""
from pathlib import Path
import argparse, shutil
project=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
args=parser.parse_args()
out=args.output.resolve()
crate=out/'native'
shutil.copytree(project/'tools/rust/vss-native-core',crate,ignore=shutil.ignore_patterns('target','.git'))
p=crate/'src/surface.rs'
t=p.read_text(); needle='    pub fn depth(&self, graph: &Graph, x: i32, z: i32) -> i32 {'
assert t.count(needle)==1
t=t.replace(needle,'''    pub(crate) fn experiment_recursive_analysis(&mut self) {
        self.experiment_recursive=true;
    }
'''+needle)
t=t.replace('    rule: Rule,','    rule: Rule,\n    experiment_recursive: bool,')
t=t.replace('            rule,','            rule,\n            experiment_recursive: false,')
needle='    fn summary_safe_for_biome(&self, palette:&Palette,biome:Option<&str>,dry:bool,hole:Option<bool>)->bool {'
assert t.count(needle)==1
t=t.replace(needle,needle+'\n        if self.experiment_recursive {return self.analyse_summary(palette,biome,dry,hole);}')
needle='    pub fn exterior_padding(&self, graph: &Graph, x: i32, z: i32) -> Option<i32> {'
assert t.count(needle)==1
t=t.replace(needle,needle+'\n        if self.experiment_recursive {return self.exterior_padding_recursive(graph,x,z);}')
t=t.replace('    #[cfg(test)]\n    fn exterior_padding_recursive','    fn exterior_padding_recursive')
p.write_text(t)
p=crate/'src/backend.rs';t=p.read_text()
old='        let surface = Surface::new(&mut terrain, &document, &mut palette)?;'
assert t.count(old)==1
t=t.replace(old,'''        let mut surface = Surface::new(&mut terrain, &document, &mut palette)?;
        if document["vss_experiment_recursive_analysis"].as_bool()==Some(true) {
            surface.experiment_recursive_analysis();
        }''')
needle='        let mut terrain = Terrain::from_document(seed, &document)?;'
t=t.replace(needle,'''        crate::climate::targets::EXPERIMENT_ENABLED.store(
            document["vss_experiment_combined"].as_bool()==Some(true),std::sync::atomic::Ordering::Relaxed);
'''+needle)
if 'let mut scratch = self.take_raw_work()?;' in t[t.index('    pub fn placed('):t.index('    pub fn state_table')]:
    a=t.index('    pub fn placed(');b=t.index('    pub fn state_table');part=t[a:b]
    part=part.replace('let mut scratch = self.take_raw_work()?;',
        '''let experiment_pool=self.document["vss_experiment_combined"].as_bool()==Some(true);
        let mut scratch = if experiment_pool {self.take_raw_work()?} else {
            t.graph.scratch(chunk_x*16,chunk_z*16,t.cell_width,t.cell_height)?};''')
    part=part.replace('        self.return_raw_work(scratch)?;',
        '        if experiment_pool {self.return_raw_work(scratch)?;}')
    t=t[:a]+part+t[b:]
p.write_text(t)
p=crate/'src/climate/targets.rs'
if p.exists():
    t=p.read_text()
    t+='\npub(crate) static EXPERIMENT_ENABLED: std::sync::atomic::AtomicBool=std::sync::atomic::AtomicBool::new(true);\n'
    needle='    let h=(p[0] as u32)'
    assert t.count(needle)==1
    t=t.replace(needle,'''    if !EXPERIMENT_ENABLED.load(std::sync::atomic::Ordering::Relaxed) {
        let mut target=[0;7];
        for i in 0..6 {target[i]=super::quantize(g.compute(ids[i],p,Mode::Raw,scratch) as f32);}
        return target;
    }
'''+needle)
    p.write_text(t)
p=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
t=p.read_text();needle='            JsonObject document=RustWorldgenDocument.snapshot(generator,registriesJson,context);'
t=t.replace(needle,needle+'\n            document.addProperty("vss_experiment_recursive_analysis",mode.startsWith("baseline"));')
t=t.replace(needle,needle+'\n            document.addProperty("vss_experiment_combined",mode.equals("combined"));')
p.write_text(t)
print(crate)
