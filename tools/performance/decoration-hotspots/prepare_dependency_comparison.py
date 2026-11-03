"""Compare production corner sharing/arithmetic against their recursive path.

The switch exists only in this isolated DLL. Run repeated controls without
parallel builds/tests; validate the shipped DLL separately against these hashes.
"""
from pathlib import Path
import argparse
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('output', type=Path)
args = parser.parse_args()
out = args.output.resolve()
subprocess.run([sys.executable, str(Path(__file__).with_name('prepare.py')), str(out)], check=True)
crate = out / 'native'
shutil.copytree(root / 'tools/rust/vss-native-core', crate, ignore=shutil.ignore_patterns('target', '.git'))
p = crate / 'src/density.rs'
t = p.read_text()
t = t.replace('pub struct Graph {', 'pub struct Graph {\n    dependency_enabled: bool,')
t = t.replace('            nodes: vec![],', '            nodes: vec![],\n            dependency_enabled: true,')
old = '        Self::build_document(seed, doc, true)'
assert t.count(old) == 1
t = t.replace(old, '''        let mut g=Self::build_document(seed, doc, true)?;
        g.dependency_enabled=doc["vss_comparison_dependency"].as_bool()==Some(true);
        Ok(g)''')
for old, new in [
    ('let shared = mode ==', 'let shared = self.dependency_enabled && mode =='),
    ('if s.use_column_plan && self.arithmetic_plan.selected(id)',
     'if self.dependency_enabled && s.use_column_plan && self.arithmetic_plan.selected(id)'),
]:
    assert t.count(old) == 1
    t = t.replace(old, new)
p.write_text(t)
p = out / 'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
t = p.read_text()
old = '            JsonObject document=RustWorldgenDocument.snapshot(generator,registriesJson,context);'
assert t.count(old) == 1
p.write_text(t.replace(old, old + '\n            document.addProperty("vss_comparison_dependency",mode.equals("candidate"));'))
print(out)
