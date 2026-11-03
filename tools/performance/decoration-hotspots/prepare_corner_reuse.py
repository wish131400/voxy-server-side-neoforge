"""Share completed density corners only inside the existing covered-flat proof."""
from pathlib import Path
import argparse,subprocess,sys
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);args=parser.parse_args()
out=args.output.resolve()
subprocess.run([sys.executable,str(Path(__file__).with_name('prepare_subtree_reuse.py')),str(out)],check=True)
crate=out/'native'
def patch(p,old,new,count=1):
    t=p.read_text();assert t.count(old)==count,(p,old[:70],t.count(old));p.write_text(t.replace(old,new),encoding='utf-8')
p=crate/'src/density.rs'
patch(p,'    subtree_capacity:usize,','    subtree_capacity:usize,\n    corner_capacity:usize,\n    corner_safe:Vec<bool>,')
patch(p,'            subtree_capacity:0,','            subtree_capacity:0,\n            corner_capacity:0,\n            corner_safe:vec![],',2)
patch(p,'        let mut builder = Builder {','''        graph.corner_capacity=match doc["vss_experiment_corner_reuse"].as_u64() {Some(n @ (8192|32768|131072))=>n as usize,_=>0};
        let mut builder = Builder {''')
needle='        self.exposed_column_order = cache_order::exposed(self, &dependencies);'
patch(p,needle,needle+'\n        self.corner_safe=cache_order::experiment_safe_corners(self,&dependencies);')
patch(p,'        let reuse=self.subtree_capacity!=0 && !s.subtree_suspended && self.subtree_entries[id];',
'''        let corner=self.corner_capacity!=0 && mode==Mode::Single && !s.subtree_suspended
            && !self.exposed_column_order && self.corner_safe[id]
            && self.column_plan.corner_slots[id]!=column_plan::NONE
            && s.origin_x%4==0 && s.origin_z%4==0 && s.span>=4
            && p[0]>=s.origin_x && p[0]<=s.origin_x+s.span
            && p[2]>=s.origin_z && p[2]<=s.origin_z+s.span;
        let reuse_capacity=if corner {self.corner_capacity} else {self.subtree_capacity};
        let reuse=corner || (self.subtree_capacity!=0 && !s.subtree_suspended && self.subtree_entries[id]);''')
patch(p,'noise_reuse_experiment::lookup(self.reuse_identity,key,self.subtree_capacity)',
      'noise_reuse_experiment::lookup(self.reuse_identity,key,reuse_capacity)')
patch(p,'noise_reuse_experiment::store(self.reuse_identity,key,self.subtree_capacity,v)',
      'noise_reuse_experiment::store(self.reuse_identity,key,reuse_capacity,v)')
p=crate/'src/density/cache_order.rs'
with p.open('a',encoding='utf-8') as f:f.write('''
pub(super) fn experiment_safe_corners(g:&Graph,vertical:&[bool])->Vec<bool> {
    let exposed=dependencies(g,vertical);
    let beard=super::pure_subtree_experiment::beard_dependencies(g);
    exposed.into_iter().zip(beard).map(|(a,b)|!a&&!b).collect()
}
''')
p=crate/'src/density/pure_subtree_experiment.rs'
with p.open('a',encoding='utf-8') as f:f.write('''
pub(super) fn beard_dependencies(g:&Graph)->Vec<bool> {
    let mut beard=Vec::with_capacity(g.nodes.len());
    for n in &g.nodes {beard.push(matches!(n,Node::Beard)||children(n).iter().any(|&i|beard[i]));}
    beard
}
''')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_subtree_reuse",',
      '            document.addProperty("vss_experiment_corner_reuse",mode.startsWith("corner")?Integer.parseInt(mode.substring(6)):0);\n            document.addProperty("vss_experiment_subtree_reuse",')
print(out)
