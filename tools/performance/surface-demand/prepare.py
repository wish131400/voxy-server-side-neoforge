"""Add dependency-driven surface evaluation to an already isolated climate benchmark snapshot."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--unsafe-neighbour-deferral',action='store_true',help='Reproduce the rejected, output-changing prototype. Never use for a speed claim.');a=p.parse_args();out=a.output.resolve();here=Path(__file__).parent
crate=out/'native'
def patch(path,old,new,count=1):
    t=path.read_text(encoding='utf-8');assert t.count(old)==count,(path,old[:90],t.count(old));path.write_text(t.replace(old,new),encoding='utf-8')
sf=crate/'src/surface.rs'
patch(sf,'mod exterior;','mod exterior;\nmod demand_experiment;')
patch(sf,'    rule: Rule,','    rule: Rule,\n    demand: demand_experiment::Demand,')
patch(sf,'        let mut surface = Self {','        let demand=demand_experiment::Demand::compile(&rule,doc);\n        let mut surface = Self {')
patch(sf,'            rule,\n            summaries:','            rule,\n            demand,\n            summaries:')
shutil.copy2(here/'demand.rs',crate/'src/surface/demand_experiment.rs')
# Add a dependency-aware retained pass. Each original expression and rule order stays intact.
original=(crate/'src/surface/exterior.rs').read_text(encoding='utf-8')
start=original.index('    pub(crate) fn apply_retained')
method=original[start:original.rfind('\n}')]
method=method.replace('pub(crate) fn apply_retained','pub(crate) fn apply_demand')
method=method.replace('x: i32, z: i32, neighbours: [i32; 4], colors: &ClimateColors,','x: i32, z: i32, colors: &ClimateColors,')
method=method.replace('mut biome: impl FnMut(Pos) -> Result<(&\'b str, &\'b Biome)>, exterior: bool,','''mut biome: impl FnMut(Pos) -> Result<(&'b str, &'b Biome)>,
        mut neighbours: impl FnMut(&mut Job<'_>, &mut dyn FnMut(Pos)->Result<(&'b str,&'b Biome)>)->Result<Option<[i32;4]>>,''')
method=method.replace(') -> Result<()> {',') -> Result<bool> {',1)
old='''        let secondary = graph.noise(self.secondary, x as f64, 0., z as f64);
        let min_surface = self.min_surface(job, x, z, depth);
        let [north, south, west, east] = neighbours;
        let steep = south >= north + 4 || west >= east + 4;'''
new='''        let mut secondary = None;
        let mut min_surface = None;
        let mut steep = None;'''
assert old in method;method=method.replace(old,new)
old='''                    let c = Context {'''
new='''                    let needed=self.demand.for_biome(name);
                    let secondary=if needed&SECONDARY!=0 {
                        *secondary.get_or_insert_with(||graph.noise(self.secondary,x as f64,0.,z as f64))
                    }else{0.};
                    let min_surface=if needed&ABOVE!=0 {
                        *min_surface.get_or_insert_with(||self.min_surface(job,x,z,depth))
                    }else{0};
                    let steep=if needed&STEEP!=0 {
                        if steep.is_none() {
                            let Some([north,south,west,east])=neighbours(job,&mut biome)? else{return Ok(false)};
                            steep=Some(south>=north+4 || west>=east+4);
                        }
                        steep.unwrap()
                    }else{false};
                    let c = Context {'''
assert old in method;method=method.replace(old,new)
method=method.replace('cold: colors.temperature(data, p) < 0.15,','cold: needed&COLD!=0 && colors.temperature(data,p)<0.15,')
method=method.replace('if exterior && output_floor.is_some_and','if output_floor.is_some_and')
method=method.replace('        Ok(())','        Ok(true)')
with (crate/'src/surface/demand_experiment.rs').open('a',encoding='utf-8') as f:f.write('\nimpl Surface {\n'+method+'\n}\n')
# Move unchanged neighbour preparation behind the rule dependency at the actual column biome.
be=crate/'src/backend/exterior.rs';text=be.read_text()
start=text.index('        let positions = [');end=text.index('        let _record_scope',start)
old=text[start:end]
pos=old.index('        let neighbour_scope');end_neigh=old.index('        neighbour_scope.finish();')
prep=old[:pos]+old[pos:end_neigh].replace('        let neighbour_scope = crate::prof::Scope::new(&crate::prof::SURFACE.neighbour_geometry);\n','')
prep=prep.replace('return Ok(None);','return Ok(None);')
new='''        let rules_scope = crate::prof::Scope::new(&crate::prof::SURFACE.apply_rules);
        if self.surface.demand_enabled() {
            let success=self.surface.apply_demand(job,&mut column,&self.palette,x,z,&self.colors,&mut biome,
                |job,biome| {
'''+prep+'''
                    Ok(Some(heights))
                })?;
            if !success{return Ok(None)}
        }else {
'''+old.replace('        let rules_scope = crate::prof::Scope::new(&crate::prof::SURFACE.apply_rules);\n','').replace('        rules_scope.finish();\n','')+'''
        }
        rules_scope.finish();
'''
patch(be,old,new)
with (crate/'src/surface/demand_experiment.rs').open('a',encoding='utf-8') as f:f.write('\nimpl Surface {pub(crate) fn demand_enabled(&self)->bool{self.demand.enabled}}\n')
h=out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
patch(h,'            document.addProperty("vss_experiment_horizontal",','            document.addProperty("vss_experiment_demand",mode.contains("demand"));\n            document.addProperty("vss_experiment_horizontal",')
if not a.unsafe_neighbour_deferral:
    subprocess.run([sys.executable,str(here/'order.py'),str(out)],check=True)
(out/'surface-demand-manifest.json').write_text(json.dumps({'experimentFiles':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in here.iterdir() if p.is_file()},'generatedRust':{p.relative_to(crate).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (crate/'src').rglob('*.rs')}},indent=2))
print(out)
