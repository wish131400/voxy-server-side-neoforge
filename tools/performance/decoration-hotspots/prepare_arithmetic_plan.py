"""Measure compact numeric evaluation, independently and with corner sharing."""
from pathlib import Path
import argparse,subprocess,sys,shutil
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);args=parser.parse_args()
out=args.output.resolve()
subprocess.run([sys.executable,str(Path(__file__).with_name('prepare_corner_reuse.py')),str(out)],check=True)
crate=out/'native'
def patch(p,old,new,count=1):
    t=p.read_text();assert t.count(old)==count,(p,old[:70],t.count(old));p.write_text(t.replace(old,new),encoding='utf-8')
shutil.copy2(Path(__file__).with_name('arithmetic_plan_experiment.rs'),crate/'src/density/arithmetic_plan_experiment.rs')
p=crate/'src/density.rs'
patch(p,'mod pure_subtree_experiment;','mod arithmetic_plan_experiment;\nmod pure_subtree_experiment;')
patch(p,'pub struct Graph {','pub struct Graph {\n    arithmetic_enabled:bool,\n    arithmetic_plan:arithmetic_plan_experiment::Plan,')
patch(p,'            noise_ids: HashMap::new(),','            noise_ids: HashMap::new(),\n            arithmetic_enabled:false,\n            arithmetic_plan:arithmetic_plan_experiment::Plan::default(),',2)
patch(p,'        let mut builder = Builder {','''        graph.arithmetic_enabled=doc["vss_experiment_arithmetic"].as_bool()==Some(true);
        let mut builder = Builder {''')
patch(p,'        Ok(builder.graph)','        builder.graph.arithmetic_plan=arithmetic_plan_experiment::Plan::compile(&builder.graph);\n        Ok(builder.graph)')
needle='        b.graph.surface_plan = surface_plan::SurfacePlan::compile(&b.graph);'
patch(p,needle,needle+'\n        b.graph.arithmetic_plan=arithmetic_plan_experiment::Plan::compile(&b.graph);')
patch(p,'        let v = match &self.nodes[id] {','''        let v = if self.arithmetic_enabled && self.arithmetic_plan.selected(id) {
            self.arithmetic_plan.eval(id,self,p,mode,s)
        } else {match &self.nodes[id] {''')
needle='        };\n        #[cfg(feature = "profiling")]\n        if __noise {\n            crate::prof::SURFACE.noise_time'
patch(p,needle,'        }\n'+needle)
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_corner_reuse",mode.startsWith("corner")?Integer.parseInt(mode.substring(6)):0);',
'''            document.addProperty("vss_experiment_corner_reuse",mode.startsWith("corner")?Integer.parseInt(mode.substring(6)):mode.equals("arithmetic_corner")?131072:0);
            document.addProperty("vss_experiment_arithmetic",mode.startsWith("arithmetic"));''')
print(out)
