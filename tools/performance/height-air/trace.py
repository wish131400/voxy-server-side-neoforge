"""Add counters to a separate diagnostic copy; do not compare its timings."""
from pathlib import Path
import argparse

p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();crate=a.output/'native'
def patch(path,old,new,count=1):
    t=path.read_text(encoding='utf-8');assert t.count(old)==count,(path,old[:100],t.count(old))
    path.write_text(t.replace(old,new),encoding='utf-8')
module=crate/'src/terrain/dry_air_experiment.rs'
patch(module,'    unequal: bool,','    pub(super) unequal: bool,')
with module.open('a',encoding='utf-8') as f:f.write('''
static COUNTS:[std::sync::atomic::AtomicU64;10]=[const{std::sync::atomic::AtomicU64::new(0)};10];
pub(crate) fn event(index:usize,amount:u64){COUNTS[index].fetch_add(amount,std::sync::atomic::Ordering::Relaxed);}
pub(crate) fn stats(reset:bool)->serde_json::Value {
    let names=["topScans","topBlockQueries","topAirBlocksSkipped","wholeCellSolves","wholeDryMarginAccepted","columnDryMarginAccepted","baseBlockQueries","airProofEntries","nearbyQueries","nearbyRebuilds"];
    let mut out=serde_json::Map::new();for (i,name) in names.iter().enumerate(){
        let value=if reset{COUNTS[i].swap(0,std::sync::atomic::Ordering::Relaxed)}else{COUNTS[i].load(std::sync::atomic::Ordering::Relaxed)};
        out.insert((*name).into(),value.into());
    }serde_json::Value::Object(out)
}
''')
terrain=crate/'src/terrain.rs'
patch(terrain,'mod dry_air_experiment;','pub(crate) mod dry_air_experiment;')
patch(terrain,'        crate::prof::hit(&crate::prof::SURFACE.top_scans);','        dry_air_experiment::event(0,1);\n        crate::prof::hit(&crate::prof::SURFACE.top_scans);')
t=terrain.read_text(encoding='utf-8')
start=t.index('    pub fn surface_top(');end=t.index('    fn display_top(',start)
part=t[start:end].replace('                y = bottom - 1;','                dry_air_experiment::event(2,(y-bottom+1) as u64);\n                y = bottom - 1;')
part=part.replace('            let block = self.block([x, y, z]);','            dry_air_experiment::event(1,1);\n            let block = self.block([x, y, z]);')
t=t[:start]+part+t[end:]
start=t.index('    pub fn proven_air_bottom(');end=t.index('    fn prove_air_cell(',start)
part=t[start:end].replace('        let base = [','        dry_air_experiment::event(7,1);\n        let base = [',1)
part=part.replace('        Some(bottom)','        if t.experiment_dry && dry.unequal {dry_air_experiment::event(5,1);}\n        Some(bottom)')
t=t[:start]+part+t[end:]
start=t.index('    fn prove_air_cell(');end=t.index('    fn aquifer_location(',start)
part=t[start:end].replace('        let t = self.terrain;','        dry_air_experiment::event(3,1);\n        let t = self.terrain;',1)
part=part.replace('        true\n','        if t.experiment_dry && dry.unequal {dry_air_experiment::event(4,1);}\n        true\n')
t=t[:start]+part+t[end:]
t=t.replace('    fn base_substance(&mut self, p: [i32; 3]) -> Substance {','    fn base_substance(&mut self, p: [i32; 3]) -> Substance {\n        dry_air_experiment::event(6,1);',1)
terrain.write_text(t,encoding='utf-8')
patch(crate/'src/terrain/aquifer_nearby_experiment.rs','        let [x,y,z]=p;','        super::dry_air_experiment::event(8,1);\n        let [x,y,z]=p;')
patch(crate/'src/terrain/aquifer_nearby_experiment.rs','        if self.nearby.key!=Some(key) {','        if self.nearby.key!=Some(key) {\n            super::dry_air_experiment::event(9,1);')
patch(crate/'src/backend.rs','    pub(crate) fn horizontal_stats(&self,reset:bool)->serde_json::Value {self.terrain.graph.horizontal_stats(reset)}','''    pub(crate) fn horizontal_stats(&self,reset:bool)->serde_json::Value {
        let mut result=self.terrain.graph.horizontal_stats(reset);
        result["heightTrace"]=crate::terrain::dry_air_experiment::stats(reset);result
    }''')
print(crate)
