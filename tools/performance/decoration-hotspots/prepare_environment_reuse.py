"""Share exact root results inside covered cells, retaining stateful fallbacks."""
from pathlib import Path
import argparse,subprocess,sys
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);args=parser.parse_args()
out=args.output.resolve()
subprocess.run([sys.executable,str(Path(__file__).with_name('prepare_lattice_reuse.py')),str(out)],check=True)
crate=out/'native'
def patch(p,old,new,count=1):
    t=p.read_text();assert t.count(old)==count,(p,old[:70],t.count(old));p.write_text(t.replace(old,new),encoding='utf-8')
p=crate/'src/density.rs'
patch(p,'    lattice_capacity:usize,','    lattice_capacity:usize,\n    environment_capacity:usize,')
patch(p,'            lattice_capacity:0,','            lattice_capacity:0,\n            environment_capacity:0,',2)
patch(p,'        let mut builder = Builder {','''        graph.environment_capacity=match doc["vss_experiment_environment_reuse"].as_u64() {Some(n @ (8192|32768|131072))=>n as usize,_=>0};
        let mut builder = Builder {''')
patch(p,'        let reuse_capacity=if corner {self.corner_capacity} else {self.subtree_capacity};',
'''        let environment=self.environment_capacity!=0 && !s.subtree_suspended
            && !self.exposed_column_order && self.corner_safe[id]
            && (self.roots.values().any(|&root|root==id))
            && (mode==Mode::Raw || (matches!(mode,Mode::Cell|Mode::Block)
                && p[0].div_euclid(s.width)*s.width>=s.origin_x
                && p[2].div_euclid(s.width)*s.width>=s.origin_z
                && p[0].div_euclid(s.width)*s.width+s.width<=s.origin_x+s.span
                && p[2].div_euclid(s.width)*s.width+s.width<=s.origin_z+s.span));
        let reuse_capacity=if environment {self.environment_capacity} else if corner {self.corner_capacity} else {self.subtree_capacity};''')
patch(p,'        let reuse=corner || (self.subtree_capacity', '        let reuse=environment || corner || (self.subtree_capacity')
patch(p,'let key=[(1u64<<62)|id as u64,p[0] as u64,p[1] as u64,p[2] as u64];',
      'let key=[(1u64<<62)|((match mode {Mode::Raw=>0,Mode::Single=>1,Mode::Block=>2,Mode::Cell=>3,Mode::Slice=>4})<<48)|id as u64,p[0] as u64,p[1] as u64,p[2] as u64];')
# Cache root eligibility in graph initialization instead of looking up a string map on every node.
patch(p,'    corner_safe:Vec<bool>,','    corner_safe:Vec<bool>,\n    environment_roots:Vec<bool>,')
patch(p,'            corner_safe:vec![],','            corner_safe:vec![],\n            environment_roots:vec![],',2)
patch(p,'        Ok(builder.graph)','''        builder.graph.environment_roots=(0..builder.graph.nodes.len()).map(|i|builder.graph.roots.values().any(|&id|i==id)).collect();
        Ok(builder.graph)''')
needle='        b.graph.subtree_entries=pure_subtree_experiment::compile(&b.graph);'
patch(p,needle,needle+'\n        b.graph.environment_roots=(0..b.graph.nodes.len()).map(|i|b.graph.roots.values().any(|&id|i==id)).collect();')
patch(p,'(self.roots.values().any(|&root|root==id))','self.environment_roots[id]')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_lattice_reuse",',
'''            document.addProperty("vss_experiment_environment_reuse",mode.startsWith("environment")?Integer.parseInt(mode.substring(11)):0);
            if(mode.startsWith("environment"))document.addProperty("vss_experiment_corner_reuse",Integer.parseInt(mode.substring(11)));
            document.addProperty("vss_experiment_lattice_reuse",''')
print(out)
