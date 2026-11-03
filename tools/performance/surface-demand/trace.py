"""Entry counters in a separate diagnostic DLL; never use its timings as speed evidence."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();crate=a.output/'native'
def patch(path,old,new,count=1):
    t=path.read_text();assert t.count(old)==count,(path,old[:80],t.count(old));path.write_text(t.replace(old,new),encoding='utf-8')
module=crate/'src/surface/demand_experiment.rs'
with module.open('a',encoding='utf-8') as f:f.write('''
static COUNTS:[std::sync::atomic::AtomicU64;10]=[const{std::sync::atomic::AtomicU64::new(0)};10];
pub(crate) fn event(index:usize) {COUNTS[index].fetch_add(1,std::sync::atomic::Ordering::Relaxed);}
impl Surface {
    pub(crate) fn demand_trace(&self,reset:bool)->serde_json::Value {
        let names=["demandColumns","eagerColumns","demandSteepColumns","demandSecondaryColumns","demandColdValues","surfaceTopCalls","surfaceTopScans","oreCalls","oreNoiseCalls","eagerColdValues"];
        let mut out=serde_json::Map::new();for (i,name) in names.iter().enumerate() {
            let value=if reset{COUNTS[i].swap(0,std::sync::atomic::Ordering::Relaxed)}else{COUNTS[i].load(std::sync::atomic::Ordering::Relaxed)};out.insert((*name).into(),value.into());
        }serde_json::Value::Object(out)
    }
}
''')
patch(crate/'src/surface.rs','mod demand_experiment;','pub(crate) mod demand_experiment;')
patch(module,'        let mut secondary = None;','        event(0);\n        let mut secondary = None;')
patch(module,'                        if steep.is_none() {','                        if steep.is_none() {\n                            event(2);')
patch(module,'*secondary.get_or_insert_with(||graph.noise(self.secondary,x as f64,0.,z as f64))','*secondary.get_or_insert_with(||{event(3);graph.noise(self.secondary,x as f64,0.,z as f64)})')
patch(module,'cold: needed&COLD!=0 && colors.temperature(data,p)<0.15,','cold: needed&COLD!=0 && {event(4);colors.temperature(data,p)<0.15},')
patch(crate/'src/surface/exterior.rs','        let secondary = graph.noise(self.secondary, x as f64, 0., z as f64);','        super::demand_experiment::event(1);\n        let secondary = graph.noise(self.secondary, x as f64, 0., z as f64);')
patch(crate/'src/surface/exterior.rs','cold: colors.temperature(data, p) < 0.15,','cold: {super::demand_experiment::event(9);colors.temperature(data,p)<0.15},')
patch(crate/'src/terrain.rs','    pub fn surface_top(&mut self, x: i32, z: i32) -> Option<(i32, Substance)> {','    pub fn surface_top(&mut self, x: i32, z: i32) -> Option<(i32, Substance)> {\n        crate::surface::demand_experiment::event(5);')
patch(crate/'src/terrain.rs','        crate::prof::hit(&crate::prof::SURFACE.top_scans);','        crate::surface::demand_experiment::event(6);\n        crate::prof::hit(&crate::prof::SURFACE.top_scans);')
patch(crate/'src/terrain.rs','    fn ore(&mut self, p: [i32; 3]) -> Option<Substance> {','    fn ore(&mut self, p: [i32; 3]) -> Option<Substance> {\n        crate::surface::demand_experiment::event(7);')
patch(crate/'src/terrain.rs','        let v = self.compute(t.vein_toggle, p, Mode::Block);','        crate::surface::demand_experiment::event(8);\n        let v = self.compute(t.vein_toggle, p, Mode::Block);')
patch(crate/'src/backend.rs','    pub(crate) fn horizontal_stats(&self,reset:bool)->serde_json::Value {self.terrain.graph.horizontal_stats(reset)}','''    pub(crate) fn horizontal_stats(&self,reset:bool)->serde_json::Value {
        let mut result=self.terrain.graph.horizontal_stats(reset);result["surfaceTrace"]=self.surface.demand_trace(reset);result
    }''')
print(crate)
