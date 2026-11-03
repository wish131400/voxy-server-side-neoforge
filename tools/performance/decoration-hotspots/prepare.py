"""Attribute complete prediction costs to named features without changing release sources."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
root=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
parser.add_argument('--source-overlay',type=Path)
args=parser.parse_args()
out=args.output.resolve()
subprocess.run([sys.executable,str(root/'tools/performance/decoration-experiment/prepare.py'),str(out),'--implementation'],check=True)
implementation=[sys.executable,str(root/'tools/performance/decoration-experiment/prepare_implementation.py'),str(out)]
if args.source_overlay: implementation+=['--source-overlay',str(args.source_overlay)]
subprocess.run(implementation,check=True)
package=Path('dev/xantha/vss/client/prediction')
patches=[]
def patch(name,old,new):
    p=out/'main-java'/package/name
    t=p.read_text(encoding='utf-8')
    assert t.count(old)==1,(name,old[:100],t.count(old))
    p.write_text(t.replace(old,new),encoding='utf-8')
    patches.append({'file':name,'old':old,'new':new})
shutil.copy2(Path(__file__).parent/'FeatureHotspotProbe.java',out/'main-java'/package/'FeatureHotspotProbe.java')
# Archived demand overlays expose an extra JNI method. The production baseline
# intentionally does not; keep its unused probe from referencing that API.
if 'surfaceHeights(' not in (out/'main-java'/package/'RustWorldgenBackend.java').read_text():
    probe=out/'main-java'/package/'FeatureHotspotProbe.java'
    t=probe.read_text()
    start=t.index('    static int heights(')
    end=t.index('    static Holder<Biome> biome(',start)
    probe.write_text(t[:start]+t[end:],encoding='utf-8')
patch('DecorationExperimentSettings.java','        scan = ','        scan = true || ')
patch('DecorationExperimentSettings.java','        page = ','        page = true ? 4 : ')
patch('RustTerrainSampler.java','RustWorldgenBackend.surfacePoints(handle(),input,output,pending)',
      'FeatureHotspotProbe.surface(handle(),input,output,pending)')
patch('RustTerrainSampler.java','RustWorldgenBackend.decorationPoints(id,input,output,16)',
      'FeatureHotspotProbe.display(id,input,output,16)')
patch('PredictionDecorationLevel.java','Holder<Biome> biome = context.noiseBiome(x, y, z);',
      'Holder<Biome> biome = FeatureHotspotProbe.biome(context,x,y,z);')
patch('PredictionBiomeCache.java','return source.getNoiseBiome(x, y, z, climate);',
      'return FeatureHotspotProbe.load(source,climate,x,y,z);')
patch('PredictionDecorationLevel.java','        super.noteWrite(pos, state);',
      '        FeatureHotspotProbe.write();\n        super.noteWrite(pos, state);')
patch('PredictionVegetation.java','''                    boolean reusableTree = treeModels != null && treeModels.supports(feature);''',
      '''                    if(FeatureHotspotProbe.skip(context,feature,chunkX,chunkZ))continue;
                    try(var featureProbe=FeatureHotspotProbe.feature(context,feature)) {
                    FeatureHotspotProbe.source(context,feature,chunkX,chunkZ);
                    boolean reusableTree = treeModels != null && treeModels.supports(feature);''')
patch('PredictionVegetation.java','''                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;''',
      '''                    FeatureHotspotProbe.route("native");
                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;
                    FeatureHotspotProbe.route(reusableTree ? "tree" : "java");''')
patch('PredictionVegetation.java','''                if (nativeStage != null) nativeStage.finish();''',
      '''                }
                if (nativeStage != null) nativeStage.finish();''')
patch('PredictionVegetation.java','''                structures.place(level, chunkX, chunkZ, seed, step);''',
      '''                try(var structureProbe=FeatureHotspotProbe.begin("@structures/"+step,"structures","")) {
                    structures.place(level, chunkX, chunkZ, seed, step);
                }''')
patch('PredictionVegetation.java','''        Map<BlockPos, BlockState> exterior = PredictionBamboo.normalize(surfaceBlocks(level));
        if (treeModels != null) exterior = PredictionLeafStates.settle(exterior);''',
      '''        Map<BlockPos, BlockState> exterior;
        try(var probe=FeatureHotspotProbe.begin("@extraction","extraction","")) {
            exterior=PredictionBamboo.normalize(surfaceBlocks(level));
        }
        if (treeModels != null) try(var probe=FeatureHotspotProbe.begin("@leaf-settle","leaf-settle","")) {
            exterior = PredictionLeafStates.settle(exterior);
        }''')
patch('PredictionPlacementExecutor.java','''                if (filter(filter, modifier, run.context, run.random, pos)) run.apply(next, pos);''',
      '''                boolean accepted=filter(filter, modifier, run.context, run.random, pos);
                if(modifier.getClass()==BiomeFilter.class)FeatureHotspotProbe.biomeFilter(accepted);
                if (accepted) run.apply(next, pos);''')
p=out/'harness-java'/package/'PredictionGenerationComparison.java'
t=p.read_text().replace('                    DecorationExperimentSettings.reset();',
    '                    DecorationExperimentSettings.reset();\n                    FeatureHotspotProbe.beginRound(mode,round);')
t=t.replace('                    String hash=fingerprint(plants,tile);',
    '                    FeatureHotspotProbe.save(output,mode,round);\n                    String hash=fingerprint(plants,tile);')
p.write_text(t,encoding='utf-8')
manifest={'patches':patches,'probeSha256':hashlib.sha256((Path(__file__).parent/'FeatureHotspotProbe.java').read_bytes()).hexdigest(),
          'sources':{p.relative_to(out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
              for d in ['main-java','harness-java'] for p in (out/d).rglob('*.java')}}
(out/'hotspot-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(out)
