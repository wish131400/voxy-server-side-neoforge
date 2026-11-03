"""Add named-feature attribution to an already frozen 26.3 benchmark snapshot.

Run after CrossVersionBenchmark.java has been copied into main-java, before
freezing the manifest and compilation. No production source is read or changed.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil


def instrument(output: Path):
    output = output.resolve()
    source = output / 'main-java/dev/xantha/vss/client/prediction'
    if (source / 'ModernFeatureProbe.java').exists():
        raise ValueError('Snapshot is already instrumented')
    patches = []

    def patch(name, old, new, count=1):
        path = source / name
        text = path.read_text(encoding='utf-8')
        actual = text.count(old)
        if actual != count:
            raise ValueError(f'{name}: expected {count} matches, found {actual}: {old[:120]}')
        path.write_text(text.replace(old, new), encoding='utf-8')
        patches.append({'file': name, 'old': old, 'new': new, 'count': count})

    probe = Path(__file__).with_name('ModernFeatureProbe.java')
    shutil.copy2(probe, source / probe.name)
    patch('PredictionVegetation.java',
          '                    boolean reusableTree = treeModels != null && treeModels.supports(feature);',
          '                    try (var featureProbe = ModernFeatureProbe.feature(context, feature)) {\n'
          '                    boolean reusableTree = treeModels != null && treeModels.supports(feature);')
    patch('PredictionVegetation.java',
          '                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;',
          '                    ModernFeatureProbe.route("native");\n'
          '                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;\n'
          '                    ModernFeatureProbe.route(reusableTree ? "tree" : "java");')
    patch('PredictionVegetation.java',
          '                if (nativeStage != null) nativeStage.finish();',
          '                }\n                if (nativeStage != null) nativeStage.finish();')
    patch('PredictionVegetation.java',
          '                structures.place(level, chunkX, chunkZ, seed, step);',
          '                try (var structureProbe = ModernFeatureProbe.begin("@structures/" + step, "structures", "")) {\n'
          '                    structures.place(level, chunkX, chunkZ, seed, step);\n                }')
    patch('PredictionVegetation.java',
          '        Map<BlockPos, BlockState> exterior = PredictionBamboo.normalize(surfaceBlocks(level));\n'
          '        if (treeModels != null) exterior = PredictionLeafStates.settle(exterior);',
          '        Map<BlockPos, BlockState> exterior;\n'
          '        try (var extractionProbe = ModernFeatureProbe.begin("@extraction", "extraction", "")) {\n'
          '            exterior = PredictionBamboo.normalize(surfaceBlocks(level));\n        }\n'
          '        if (treeModels != null) try (var leafProbe = ModernFeatureProbe.begin("@leaf-settle", "leaf-settle", "")) {\n'
          '            exterior = PredictionLeafStates.settle(exterior);\n        }')
    patch('PredictionDecorationLevel.java',
          '        super.noteWrite(pos, state);',
          '        ModernFeatureProbe.write();\n        super.noteWrite(pos, state);')
    patch('PredictionDecorationLevel.java',
          'Holder<Biome> biome = terrain.noiseBiome(x, y, z);',
          'Holder<Biome> biome = ModernFeatureProbe.biome(terrain, x, y, z);')
    patch('RustTerrainSampler.java', 'RustWorldgenBackend.surfacePoints(', 'ModernFeatureProbe.surface(', count=2)
    patch('RustTerrainSampler.java', 'RustWorldgenBackend.decorationPoints(', 'ModernFeatureProbe.display(')
    patch('RustTerrainSampler.java', 'RustWorldgenBackend.biomeIndices(', 'ModernFeatureProbe.nativeBiome(')
    patch('CrossVersionBenchmark.java',
          '                    String before=context.climateCompilerDiagnostics();',
          '                    ModernFeatureProbe.reset();\n                    String before=context.climateCompilerDiagnostics();')
    patch('CrossVersionBenchmark.java',
          '                    rows.add(row);Files.writeString(out.resolve("results.json"),JSON.toJson(rows));',
          '                    ModernFeatureProbe.save(out, round);\n'
          '                    rows.add(row);Files.writeString(out.resolve("results.json"),JSON.toJson(rows));')
    (output / 'hotspot-instrumentation.json').write_text(json.dumps({
        'scope': 'Single-generation-thread full-path attribution, isolated snapshot only',
        'patches': patches,
        'probeSha256': hashlib.sha256(probe.read_bytes()).hexdigest(),
    }, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    instrument(parser.parse_args().output)
