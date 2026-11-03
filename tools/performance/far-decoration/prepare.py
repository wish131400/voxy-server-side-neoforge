"""Freeze production sources for a disposable far-decoration experiment; never publishes JARs."""
from pathlib import Path
import argparse, hashlib, json, shutil

root = Path(__file__).resolve().parents[3]
here = Path(__file__).parent
p = argparse.ArgumentParser()
p.add_argument('output', type=Path)
p.add_argument('--snapshot', type=Path)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
if a.snapshot:
    for name in ['main-java', 'main-resources', 'harness-java']:
        shutil.copytree(a.snapshot / name, out / name)
else:
    (out/'production-input-manifest.json').write_text(json.dumps({str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest()
        for folder in ['src/main/java','src/main/resources'] for f in (root/folder).rglob('*') if f.is_file()},indent=2),encoding='utf-8')
    shutil.copytree(root / 'src/main/java', out / 'main-java')
    shutil.copytree(root / 'src/main/resources', out / 'main-resources')
    shutil.copytree(root / 'tools/performance/generation-comparison/java', out / 'harness-java')
    package = out / 'main-java/dev/xantha/vss/client/prediction'
    changes = [
        ('PredictionVegetation.java', '                    if (missingRegistryFailures.contains(feature)',
         '                    if (FarDecorationPrototype.replace(feature)) { FarProbe.replaced++; continue; }\n                    FarProbe.featureAttempts++;\n                    if (missingRegistryFailures.contains(feature)'),
        ('PredictionVegetation.java', '    private Map<BlockPos, BlockState> generate(int chunkX, int chunkZ, java.util.function.BooleanSupplier current) {',
         '    private Map<BlockPos, BlockState> generate(int chunkX, int chunkZ, java.util.function.BooleanSupplier current) {\n        FarProbe.sourceChunks++;'),
        ('RustTerrainSampler.java', 'if(RustWorldgenBackend.surfacePoints(handle(),input,output,pending)!=pending)',
         'if(FarProbe.surface(handle(),input,output,pending)!=pending)'),
        ('PredictionDecorationLevel.java', 'Holder<Biome> biome = context.noiseBiome(x, y, z);',
         'FarProbe.biomeQueries++;\n            Holder<Biome> biome = context.noiseBiome(x, y, z);'),
        ('RustVegetationStage.java', 'java.util.concurrent.ThreadLocalRandom.current().nextLong()',
         'sampler.profile().seed() ^ (long)x * 341873128712L ^ (long)z * 132897987541L ^ step'),
        ('PredictionVegetation.java', '                structures.place(level, chunkX, chunkZ, seed, step);',
         '                FarProbe.structures(structures, level, chunkX, chunkZ, seed, step);'),
        ('PredictionDecorationLevel.java', '    void useDisplayTerrain(boolean display) {',
         '    void useDisplayTerrain(boolean display) {\n        if (FarProbe.proxy() && !FarProbe.structure) display=true;'),
        ('RustVegetationStage.java', 'boolean display = !sampler.interiorTerrain()',
         'boolean display = FarProbe.proxy() || !sampler.interiorTerrain()'),
        ('RustVegetationStage.java', 'volume=RustWorldgenBackend.decorationProxy(', 'volume=FarProbe.proxy('),
        ('RustTerrainSampler.java', 'RustWorldgenBackend.decorationPoints(id,input,output,16)', 'FarProbe.display(id,input,output,16)'),
        ('PredictionVegetation.java', '                    level.beginFeature();\n                    boolean success = false;',
         '                    level.beginFeature();\n                    FarProbe.javaFeatures++;\n                    boolean success = false;'),
        ('PredictionDecorationLevel.java', '    private int[] nativeColumn(RustTerrainSampler rust, int x, int z) {',
         '    private int[] nativeColumn(RustTerrainSampler rust, int x, int z) {\n        if(displayTerrain && FarProbe.proxy() && FarDecorationPrototype.active.environmentStep>1) {\n            checkColumnBounds(x,z);return FarDecorationPrototype.active.proxyRecord(x,z);\n        }'),
    ]
    for name, old, new in changes:
        f = package / name
        t = f.read_text(encoding='utf-8')
        assert t.count(old) == 1, (name, t.count(old))
        f.write_text(t.replace(old, new), encoding='utf-8')
    shutil.copy2(here / 'FarProbe.java', package / 'FarProbe.java')
    shutil.copy2(here / 'FarDecorationPrototype.java', package / 'FarDecorationPrototype.java')
    shutil.copy2(here / 'PredictionGenerationComparison.java', out / 'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java')
    shutil.copy2(root / 'tools/performance/lod-decoration/MappedChecks.java', out / 'harness-java/dev/xantha/vss/client/prediction/MappedChecks.java')
inputs = a.snapshot or root / 'external/performance/20261102-height-production/full-run'
for name in ['worldgen-profile.bin', 'structure-templates.json']:
    shutil.copy2(inputs / name, out / name)
if a.snapshot and (a.snapshot/'production-input-manifest.json').exists():
    shutil.copy2(a.snapshot/'production-input-manifest.json',out/'production-input-manifest.json')
(out / 'server').mkdir()
(out / 'server/eula.txt').write_text('eula=true\n')
(out / 'server/server.properties').write_text('server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-seed=42\nlevel-name=benchmark-world\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=1\nsync-chunk-writes=false\nmax-tick-time=-1\nallow-nether=false\nspawn-monsters=false\nspawn-animals=false\nspawn-npcs=false\n')
manifest = {str(f.relative_to(out)): hashlib.sha256(f.read_bytes()).hexdigest()
            for folder in ['main-java', 'main-resources', 'harness-java'] for f in (out / folder).rglob('*') if f.is_file()}
for name in ['worldgen-profile.bin', 'structure-templates.json']:
    manifest[name] = hashlib.sha256((out / name).read_bytes()).hexdigest()
(out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(out)
