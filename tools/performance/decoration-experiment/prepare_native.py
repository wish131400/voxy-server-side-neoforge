"""Optional native experiment: reuse cleared workspace allocations and specialize singleton batches."""
import argparse
import hashlib
import json
import pathlib
import shutil

PROJECT = pathlib.Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('directory', type=pathlib.Path)
args = parser.parse_args()
root = args.directory.resolve()
source = PROJECT/'tools/rust/vss-native-core'
crate = root/'native-source'
shutil.copytree(source, crate, ignore=shutil.ignore_patterns('target', '.git'))
manifest = {'originalSources': {}, 'patches': []}
for p in sorted(crate.rglob('*')):
    if p.is_file():
        manifest['originalSources'][p.relative_to(crate).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
p = crate/'src/backend.rs'
text = p.read_text(encoding='utf-8')

def patch(old, new):
    global text
    assert text.count(old) == 1, (old[:100], text.count(old))
    text = text.replace(old, new)
    manifest['patches'].append({'old': old, 'new': new})

patch('    surface_work: Mutex<std::collections::VecDeque<((i32, i32), crate::terrain::SurfaceWork)>>,',
      '''    surface_work: Mutex<std::collections::VecDeque<((i32, i32), crate::terrain::SurfaceWork)>>,
    experiment_scratch_pool: bool,
    experiment_single_fast: bool,
    experiment_scratch: Mutex<Vec<crate::density::Scratch>>,''')
patch('            document,\n            source,',
      '''            experiment_scratch_pool: document["vss_experiment_scratch_pool"].as_bool().unwrap_or(false),
            experiment_single_fast: document["vss_experiment_single_fast"].as_bool().unwrap_or(false),
            experiment_scratch: Mutex::new(Vec::new()),
            document,
            source,''')
patch('    pub fn surface_point(&self, x: i32, z: i32) -> Result<SurfaceColumn> {',
      '''    fn experiment_take_scratch(&self,x:i32,z:i32)->Result<crate::density::Scratch> {
        if self.experiment_scratch_pool {
            let cached=self.experiment_scratch.lock().map_err(|_|"experiment scratch lock")?.pop();
            if let Some(mut scratch)=cached {
                // Fresh logical state; only allocations survive. No additional queries or cache warm-up.
                scratch.reset_chunk(x,z);
                return Ok(scratch);
            }
        }
        let t=&self.terrain;
        t.graph.scratch(x,z,t.cell_width,t.cell_height)
    }
    fn experiment_return_scratch(&self,scratch:crate::density::Scratch)->Result<()> {
        if self.experiment_scratch_pool {
            let bytes=scratch.retained_bytes();
            let mut pool=self.experiment_scratch.lock().map_err(|_|"experiment scratch lock")?;
            let used:usize=pool.iter().map(|s|s.retained_bytes()).sum();
            if pool.len()<8 && bytes<=16*1024*1024usize-used.min(16*1024*1024) { pool.push(scratch); }
        }
        Ok(())
    }
    pub fn surface_point(&self, x: i32, z: i32) -> Result<SurfaceColumn> {''')
patch('''        let mut scratch = t
            .graph
            .scratch(x & !15, z & !15, t.cell_width, t.cell_height)?;
        let result = self.generate_surface_point(x, z, &mut job, &mut scratch);''',
      '''        let mut scratch = self.experiment_take_scratch(x & !15,z & !15)?;
        let result = self.generate_surface_point(x, z, &mut job, &mut scratch);
        self.experiment_return_scratch(scratch)?;''')
patch('''    pub fn surface_points(&self, points: &[(i32, i32)]) -> Result<Vec<SurfaceColumn>> {
        self.check_active()?;''',
      '''    pub fn surface_points(&self, points: &[(i32, i32)]) -> Result<Vec<SurfaceColumn>> {
        self.check_active()?;
        if self.experiment_single_fast && points.len()==1 {
            return Ok(vec![self.surface_point(points[0].0,points[0].1)?]);
        }''')
patch('''            let mut scratch = t
                .graph
                .scratch(cx * 16, cz * 16, t.cell_width, t.cell_height)?;''',
      '''            let mut scratch = self.experiment_take_scratch(cx*16,cz*16)?;''')
patch('''            if reusable {
                let mut pool = self.surface_work.lock().map_err(|_| "surface work lock")?;
                // A small working set across JNI batches,''',
      '''            self.experiment_return_scratch(scratch)?;
            if reusable {
                let mut pool = self.surface_work.lock().map_err(|_| "surface work lock")?;
                // A small working set across JNI batches,''')
p.write_text(text, encoding='utf-8')
(root/'native-input-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(crate)
