"""Exact vertical rejection before ore noise, only for graphs without runtime state."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args()
def patch(path,old,new):
    t=path.read_text();assert t.count(old)==1,(path,old[:80],t.count(old));path.write_text(t.replace(old,new),encoding='utf-8')
t=a.output/'native/src/terrain.rs'
patch(t,'    ores: bool,','    ores: bool,\n    experiment_ore_reject: bool,')
patch(t,'            ores: s["ore_veins_enabled"].as_bool().ok_or("missing ore flag")?,','''            ores: s["ore_veins_enabled"].as_bool().ok_or("missing ore flag")?,
            experiment_ore_reject: doc["vss_experiment_ore_reject"].as_bool()==Some(true) && !graph.has_stateful_queries(),''')
patch(t,'    fn ore(&mut self, p: [i32; 3]) -> Option<Substance> {','''    fn ore(&mut self, p: [i32; 3]) -> Option<Substance> {
        // Both existing ore branches have fixed support: copper [0,50], iron [-60,-8].
        if self.terrain.experiment_ore_reject && !( -60..=50 ).contains(&p[1]) {return None}''')
h=a.output/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_demand",','            document.addProperty("vss_experiment_ore_reject",mode.contains("ore"));\n            document.addProperty("vss_experiment_demand",')
print(t)
