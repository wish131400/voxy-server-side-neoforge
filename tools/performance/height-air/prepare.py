"""Add an exact relaxed dry-air proof to a frozen surface-demand experiment."""
from pathlib import Path
import argparse, shutil

p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args()
crate=a.output/'native';here=Path(__file__).parent
def patch(path,old,new,count=1):
    t=path.read_text(encoding='utf-8');assert t.count(old)==count,(path,old,t.count(old))
    path.write_text(t.replace(old,new),encoding='utf-8')
terrain=crate/'src/terrain.rs'
patch(terrain,'mod top_cache;','mod top_cache;\nmod dry_air_experiment;\nmod aquifer_nearby_experiment;')
shutil.copy2(here/'dry.rs',crate/'src/terrain/dry_air_experiment.rs')
shutil.copy2(here/'nearby.rs',crate/'src/terrain/aquifer_nearby_experiment.rs')
patch(terrain,'    ores: bool,','    ores: bool,\n    experiment_dry: bool,\n    experiment_nearby: bool,')
patch(terrain,'            ores: s["ore_veins_enabled"].as_bool().ok_or("missing ore flag")?,',
    '            ores: s["ore_veins_enabled"].as_bool().ok_or("missing ore flag")?,\n            experiment_dry: doc["vss_experiment_dry"].as_bool()==Some(true),\n            experiment_nearby: doc["vss_experiment_nearby"].as_bool()==Some(true),')
t=terrain.read_text(encoding='utf-8')
start=t.index('pub struct Job');end=t.index('pub(crate) struct SurfaceWork',start)
part=t[start:end];assert part.count('    locations:')==1
part=part.replace('    locations:','    nearby: aquifer_nearby_experiment::Nearby,\n    locations:',1)
t=t[:start]+part+t[end:]
t=t.replace('            locations: HashMap::default(),','            nearby: aquifer_nearby_experiment::Nearby::default(),\n            locations: HashMap::default(),')
t=t.replace('            locations: work.locations,','            nearby: aquifer_nearby_experiment::Nearby::default(),\n            locations: work.locations,')
start=t.index('        let grid = [',t.index('    fn substance('));end=t.index('        let a = self.status(positions[0]);',start)
original=t[start:end]
t=t[:start]+'''        let (distances,positions)=if t.experiment_nearby {
            self.nearby_nearest(p)
        }else {
'''+original+'''            (distances,positions)
        };
'''+t[end:]
for name,next_name,bottom,failure in [('proven_air_bottom','prove_air_cell','bottom','return None;'),('prove_air_cell','aquifer_location','base[1]','return false;')]:
    start=t.index('    '+('pub fn ' if name=='proven_air_bottom' else 'fn ')+name+'(')
    end=t.index('    fn '+next_name+'(',start)
    section=t[start:end]
    assert section.count('        let mut level = None;')==1
    section=section.replace('        let mut level = None;','        let mut level = None;\n        let mut dry = dry_air_experiment::DryProof::default();')
    old=f'''                    if f.at({bottom}) != Substance::Air || level.is_some_and(|l| l != f.level) {{
                        {failure}
                    }}
                    level = Some(f.level);'''
    new=f'''                    if t.experiment_dry {{
                        if !dry.include(f,{bottom}) {{{failure}}}
                    }} else {{
{old}
                    }}'''
    assert section.count(old)==1,(name,old)
    section=section.replace(old,new)
    t=t[:start]+section+t[end:]
terrain.write_text(t,encoding='utf-8')
h=a.output/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_demand",','            document.addProperty("vss_experiment_dry",mode.contains("dry"));\n            document.addProperty("vss_experiment_nearby",mode.contains("nearby"));\n            document.addProperty("vss_experiment_demand",')
# Use the prior broad ten-field matrix, isolating this new proof alone.
checks=(here.parent/'surface-demand/checks.rs').read_text(encoding='utf-8')
old='candidate["vss_experiment_demand"]=json!(true);candidate["vss_experiment_ore_reject"]=json!(true);'
assert old in checks
checks=checks.replace(old,'candidate["vss_experiment_dry"]=json!(true);candidate["vss_experiment_nearby"]=json!(true);')
(crate/'tests/height_air_checks.rs').write_text(checks,encoding='utf-8')
print(crate)
