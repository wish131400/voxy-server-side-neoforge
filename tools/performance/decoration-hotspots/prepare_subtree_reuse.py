"""Extend the isolated primitive experiment to whole pure subgraph boundaries."""
from pathlib import Path
import argparse,shutil,subprocess,sys
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);args=parser.parse_args()
out=args.output.resolve()
subprocess.run([sys.executable,str(Path(__file__).with_name('prepare_noise_reuse.py')),str(out)],check=True)
crate=out/'native'
def patch(p,old,new,count=1):
    t=p.read_text();assert t.count(old)==count,(p,old[:70],t.count(old));p.write_text(t.replace(old,new),encoding='utf-8')
shutil.copy2(Path(__file__).with_name('pure_subtree_experiment.rs'),crate/'src/density/pure_subtree_experiment.rs')
p=crate/'src/density.rs'
patch(p,'mod noise_reuse_experiment;','mod pure_subtree_experiment;\nmod noise_reuse_experiment;')
patch(p,'    reuse_capacity:usize,','    reuse_capacity:usize,\n    subtree_capacity:usize,\n    subtree_entries:Vec<bool>,')
patch(p,'            reuse_capacity:0,','            reuse_capacity:0,\n            subtree_capacity:0,\n            subtree_entries:vec![],',2)
patch(p,'        let mut builder = Builder {','''        graph.subtree_capacity=match doc["vss_experiment_subtree_reuse"].as_u64() {Some(n @ (8192|32768|131072))=>n as usize,_=>0};
        let mut builder = Builder {''')
patch(p,'        Ok(builder.graph)','        builder.graph.subtree_entries=pure_subtree_experiment::compile(&builder.graph);\n        Ok(builder.graph)')
# add_root may grow the graph while the runtime builds surface-rule routing.
needle='        b.graph.surface_plan = surface_plan::SurfacePlan::compile(&b.graph);'
patch(p,needle,needle+'\n        b.graph.subtree_entries=pure_subtree_experiment::compile(&b.graph);')
patch(p,'    pub(crate) climate_targets:', '    subtree_suspended:bool,\n    pub(crate) climate_targets:')
patch(p,'            climate_targets:', '            subtree_suspended:false,\n            climate_targets:')
patch(p,'        let v = match &self.nodes[id] {','''        let reuse=self.subtree_capacity!=0 && !s.subtree_suspended && self.subtree_entries[id];
        let key=[(1u64<<62)|id as u64,p[0] as u64,p[1] as u64,p[2] as u64];
        if reuse {
            if let Some(v)=noise_reuse_experiment::lookup(self.reuse_identity,key,self.subtree_capacity) {return v;}
            s.subtree_suspended=true;
        }
        let v = match &self.nodes[id] {''')
patch(p,'        s.memo[id] = Some((p, mode, v));','''        if reuse {
            s.subtree_suspended=false;
            noise_reuse_experiment::store(self.reuse_identity,key,self.subtree_capacity,v);
        }
        s.memo[id] = Some((p, mode, v));''')
p=crate/'src/density/noise_reuse_experiment.rs'
with p.open('a',encoding='utf-8') as f:f.write('''
fn index(key:[u64;4],capacity:usize)->usize {
    let mut h=key[0].wrapping_mul(0x9e3779b97f4a7c15)^key[1]^key[2].rotate_left(21)^key[3].rotate_left(43);
    h=(h^(h>>30)).wrapping_mul(0xbf58476d1ce4e5b9);
    h=(h^(h>>27)).wrapping_mul(0x94d049bb133111eb);
    ((h^(h>>31)) as usize)&(capacity-1)
}
pub(super) fn lookup(owner:u64,key:[u64;4],capacity:usize)->Option<f64> {CACHE.with(|c| {
    let mut c=c.borrow_mut();if c.entries.len()!=capacity {c.entries=vec![Entry::default();capacity];}
    let entry=c.entries[index(key,capacity)];
    if entry.owner==owner && entry.key==key {c.hits+=1;Some(entry.value)} else {c.misses+=1;None}
})}
pub(super) fn store(owner:u64,key:[u64;4],capacity:usize,value:f64) {CACHE.with(|c| {
    c.borrow_mut().entries[index(key,capacity)]=Entry{owner,key,value};
})}
''')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_noise_reuse",',
      '            document.addProperty("vss_experiment_subtree_reuse",mode.startsWith("subtree")?Integer.parseInt(mode.substring(7)):0);\n            document.addProperty("vss_experiment_noise_reuse",')
print(out)
