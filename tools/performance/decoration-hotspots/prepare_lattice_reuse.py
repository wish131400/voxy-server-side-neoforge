"""Test stable corner and initialized flat-lattice sharing across workspaces."""
from pathlib import Path
import argparse,subprocess,sys
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);args=parser.parse_args()
out=args.output.resolve()
subprocess.run([sys.executable,str(Path(__file__).with_name('prepare_corner_reuse.py')),str(out)],check=True)
crate=out/'native'
def patch(p,old,new,count=1):
    t=p.read_text();assert t.count(old)==count,(p,old[:70],t.count(old));p.write_text(t.replace(old,new),encoding='utf-8')
p=crate/'src/density.rs'
patch(p,'    corner_capacity:usize,','    corner_capacity:usize,\n    lattice_capacity:usize,')
patch(p,'            corner_capacity:0,','            corner_capacity:0,\n            lattice_capacity:0,',2)
patch(p,'        let mut builder = Builder {','''        graph.lattice_capacity=match doc["vss_experiment_lattice_reuse"].as_u64() {Some(n @ (8192|32768|131072))=>n as usize,_=>0};
        let mut builder = Builder {''')
patch(p,'                        let value = self.compute(*child, [x, 0, z], Mode::Single, scratch);','''                        let key=[(1u64<<61)|*child as u64,x as u64,0,z as u64];
                        let value=if self.lattice_capacity==0 {self.compute(*child,[x,0,z],Mode::Single,scratch)}
                        else if let Some(v)=noise_reuse_experiment::lookup(self.reuse_identity,key,self.lattice_capacity) {v}
                        else {
                            let v=self.compute(*child,[x,0,z],Mode::Single,scratch);
                            noise_reuse_experiment::store(self.reuse_identity,key,self.lattice_capacity,v);v
                        };''')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_corner_reuse",mode.startsWith("corner")?Integer.parseInt(mode.substring(6)):0);',
'''            document.addProperty("vss_experiment_corner_reuse",mode.startsWith("corner")?Integer.parseInt(mode.substring(6)):mode.startsWith("lattice")?Integer.parseInt(mode.substring(7)):0);
            document.addProperty("vss_experiment_lattice_reuse",mode.startsWith("lattice")?Integer.parseInt(mode.substring(7)):0);''')
print(out)
