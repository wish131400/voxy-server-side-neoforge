"""Create a hash-recorded source overlay and fresh runtime for the decoration experiment."""
import argparse
import hashlib
import json
import pathlib
import shutil

PROJECT = pathlib.Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('output', type=pathlib.Path)
parser.add_argument('--inputs', type=pathlib.Path, default=PROJECT / 'build/generation-comparison-20261031')
parser.add_argument('--implementation', action='store_true')
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
main = output / 'main-java'
shutil.copytree(PROJECT / 'src/main/java', main)
shutil.copytree(PROJECT / 'src/main/resources', output / 'main-resources')
package = main / 'dev/xantha/vss/client/prediction'
harness = output / 'harness-java'
shutil.copytree(PROJECT / 'tools/performance/generation-comparison/java', harness)
shutil.copytree(pathlib.Path(__file__).parent / 'harness', harness, dirs_exist_ok=True)
manifest = {'sources': {}, 'resources': {}, 'inputs': {}, 'patches': []}
for directory, group in [(PROJECT / 'src/main/java', 'sources'), (PROJECT / 'src/main/resources', 'resources')]:
    for p in sorted(directory.rglob('*')):
        if p.is_file():
            manifest[group][p.relative_to(PROJECT).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()

def patch(name, old, new):
    p = package / name
    text = p.read_text(encoding='utf-8')
    assert text.count(old) == 1, (name, old[:100], text.count(old))
    p.write_text(text.replace(old, new), encoding='utf-8')
    manifest['patches'].append({'file': name, 'old': old, 'new': new})

helper = pathlib.Path(__file__).parent / 'overlay/DecorationExperimentSettings.java'
shutil.copyfile(helper, package / helper.name)
if not args.implementation:
    patch('PredictionDecorationLevel.java', 'record = rust.surfaceRecord(x, z);',
          '''if (DecorationExperimentSettings.page > 1) {
                    int n = DecorationExperimentSettings.page;
                    int ox = Math.floorDiv(x,n)*n, oz = Math.floorDiv(z,n)*n;
                    int[][] rows = rust.experimentExactPage(ox,oz,n);
                    for (int i=0;i<n*n;i++) nativeColumns[columnSlot(ox+i/n,oz+i%n)] = rows[i];
                    record = nativeColumns[slot];
                } else record = rust.surfaceRecord(x, z);''')
    patch('PredictionDecorationLevel.java', 'Holder<Biome> biome = context.noiseBiome(x, y, z);',
          '''DecorationExperimentSettings.biomeQueries++;
            Holder<Biome> biome = DecorationExperimentSettings.nativeBiomes && terrain instanceof RustTerrainSampler rust
                    ? rust.experimentNoiseBiome(x,y,z) : context.noiseBiome(x,y,z);
            if (DecorationExperimentSettings.verifyBiomes && DecorationExperimentSettings.nativeBiomes) {
                Holder<Biome> oracle = context.noiseBiome(x,y,z);
                DecorationExperimentSettings.biomeVerified++;
                if (!biome.unwrapKey().equals(oracle.unwrapKey()))
                    throw new AssertionError("Native biome mismatch at "+x+","+y+","+z+": "+biome+" / "+oracle);
            }''')
    sampler_methods = (pathlib.Path(__file__).parent / 'overlay/sampler-methods.txt').read_text(encoding='utf-8')
    patch('RustTerrainSampler.java', '    private int[] point(int x, int z) {', sampler_methods + '\n    private int[] point(int x, int z) {')
    patch('RustTerrainSampler.java', '        if (RustWorldgenBackend.surfacePoints(handle(),input,output,1) != 1)',
          '''        DecorationExperimentSettings.surfaceCalls++;
            DecorationExperimentSettings.surfacePoints++;
            if (RustWorldgenBackend.surfacePoints(handle(),input,output,1) != 1)''')
    patch('PredictionPlacementExecutor.java', '        // CountOnEveryLayer eagerly scans every floor before any terminal runs.',
          (pathlib.Path(__file__).parent / 'overlay/scan-operation.txt').read_text(encoding='utf-8')
          + '\n        // CountOnEveryLayer eagerly scans every floor before any terminal runs.')
# Hold external shuffle entropy constant in every variant; this does not consume Feature's random stream.
patch('RustVegetationStage.java', 'java.util.concurrent.ThreadLocalRandom.current().nextLong()',
      'sampler.profile().seed() ^ (long)x * 341873128712L ^ (long)z * 132897987541L ^ step')
for name in ['worldgen-profile.bin', 'structure-templates.json']:
    p = args.inputs / name
    shutil.copyfile(p, output / name)
    manifest['inputs'][name] = hashlib.sha256(p.read_bytes()).hexdigest()
server = output / 'server'
server.mkdir()
(server / 'eula.txt').write_text('eula=true\n')
(server / 'server.properties').write_text('''server-ip=127.0.0.1
server-port=0
online-mode=false
level-seed=42
level-name=benchmark-world
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=1
sync-chunk-writes=false
max-tick-time=-1
allow-nether=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
''')
(output / 'input-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(output)
