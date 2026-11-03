"""Reuse only the Raw workspace needed by exterior biome routing; keep density traversal in Job."""
import argparse
import json
import pathlib

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=pathlib.Path)
root = parser.parse_args().directory.resolve()
p = root/'native-source/src/backend.rs'
text = p.read_text(encoding='utf-8')
patches = []

def patch(old, new):
    global text
    assert text.count(old) == 1, (old[:100], text.count(old))
    text = text.replace(old, new)
    patches.append({'old': old, 'new': new})

patch('    experiment_scratch_pool: bool,', '    experiment_scratch_pool: bool,\n    experiment_raw_workspace: bool,')
patch('            experiment_single_fast: document["vss_experiment_single_fast"].as_bool().unwrap_or(false),',
      '            experiment_single_fast: document["vss_experiment_single_fast"].as_bool().unwrap_or(false),\n'
      '            experiment_raw_workspace: document["vss_experiment_raw_workspace"].as_bool().unwrap_or(false),')
patch('''        if self.experiment_scratch_pool {
            let cached=self.experiment_scratch.lock().map_err(|_|"experiment scratch lock")?.pop();''',
      '''        if self.experiment_scratch_pool || self.experiment_raw_workspace {
            let cached=self.experiment_scratch.lock().map_err(|_|"experiment scratch lock")?.pop();''')
patch('                scratch.reset_chunk(x,z);', '                if !self.experiment_raw_workspace { scratch.reset_chunk(x,z); }')
patch('        t.graph.scratch(x,z,t.cell_width,t.cell_height)',
      '        if self.experiment_raw_workspace { t.graph.raw_scratch() } else { t.graph.scratch(x,z,t.cell_width,t.cell_height) }')
patch('''        if self.experiment_scratch_pool {
            let bytes=scratch.retained_bytes();''',
      '''        if self.experiment_scratch_pool || self.experiment_raw_workspace {
            let bytes=scratch.retained_bytes();''')
p.write_text(text, encoding='utf-8')
(root/'raw-patches.json').write_text(json.dumps(patches, ensure_ascii=False, indent=2), encoding='utf-8')
print('Applied Raw workspace experiment:', root)
