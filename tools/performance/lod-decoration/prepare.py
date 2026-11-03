"""Freeze a verified baseline and install opt-in exact column/output-demand experiments."""
from pathlib import Path
import argparse, hashlib, json, shutil

project=Path(__file__).resolve().parents[3]
here=Path(__file__).parent
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--baseline',type=Path,required=True)
a=p.parse_args();out=a.output.resolve();base=a.baseline.resolve();out.mkdir(exist_ok=False)
for name in ['main-java','main-resources','harness-java']:
    shutil.copytree(base/name,out/name)
for name in ['worldgen-profile.bin','structure-templates.json']:
    shutil.copy2(base/name,out/name)
(out/'server').mkdir()
for name in ['eula.txt','server.properties']:shutil.copy2(base/'server'/name,out/'server'/name)
package=out/'main-java/dev/xantha/vss/client/prediction'
for name in ['ColumnBlocks.java','LodExperiment.java','SpanTile.java']:
    shutil.copy2(here/name,package/name)
patches=[]
def patch(path,old,new):
    t=path.read_text(encoding='utf-8');assert t.count(old)==1,(path,old[:90],t.count(old))
    path.write_text(t.replace(old,new),encoding='utf-8');patches.append({'path':path.relative_to(out).as_posix(),'old':old,'new':new})

# Keep the ordered overlay, transaction undo and pending native uploads. Only the primary
# representation changes; Java/mod features continue to see the Map contract.
f=package/'feature/FeatureStampLevel.java'
patch(f,'private final Map<BlockPos, BlockState> placed = new HashMap<>();',
      'private final Map<BlockPos, BlockState> placed = dev.xantha.vss.client.prediction.ColumnBlocks.enabledMap();')
f=package/'PredictionVegetation.java'
patch(f,'    static final class Tile {','    static class Tile {')
patch(f,'        private Tile(PredictionVegetationBlocks blocks,','        protected Tile(PredictionVegetationBlocks blocks,')
patch(f,'        @FunctionalInterface interface Visitor {',
'''        @FunctionalInterface interface SpanVisitor {void accept(int x,int y,int z,int maxX,int maxY,int maxZ,BlockState state);}
        void forEachSpan(SpanVisitor visitor){forEachVoxel((x,y,z,size,s)->visitor.accept(x,y,z,x+size,y+size,z+size,s));}
        @FunctionalInterface interface Visitor {''')
patch(f,'    static Map<BlockPos, BlockState> surfaceBlocks(PredictionDecorationLevel level) {',
'''    static Map<BlockPos, BlockState> surfaceBlocks(PredictionDecorationLevel level) {
        if(LodExperiment.spans)return LodExperiment.exterior(level);''')
# Demand affects final publication, never the placement reads or random chain. Keep six
# blocks of leaf-distance halo plus the existing one-block publication border.
patch(f,'        level.placed().forEach((pos, state) -> {\n            if (state.isAir()',
'''        level.placed().forEach((pos, state) -> {
            if(!LodExperiment.needed(pos.getX(),pos.getZ())){LodExperiment.omittedColumns++;return;}
            if (state.isAir()''')
patch(f,'    private Map<BlockPos, BlockState> generate(int chunkX, int chunkZ, java.util.function.BooleanSupplier current) {',
      '    private Map<BlockPos, BlockState> generate(int chunkX, int chunkZ, java.util.function.BooleanSupplier current) {\n        LodExperiment.sourceChunks++;')
patch(f,'        try (var build = displayCache.begin(x, z, span, spacing, trees)) {',
'''        var previousDemand=LodExperiment.CURRENT.get();
        LodExperiment.CURRENT.set(new LodExperiment.Demand(x,z,span,spacing));
        try (var build = displayCache.begin(x, z, span, spacing, trees)) {''')
patch(f,'            return result;\n        }\n    }\n\n    record CachedTile',
'''            return result;
        } finally {if(previousDemand==null)LodExperiment.CURRENT.remove();else LodExperiment.CURRENT.set(previousDemand);}
    }

    record CachedTile''')
# This isolated run does not cache demand-trimmed source chunks across independent requests.
# Mixed demands must therefore regenerate; source memory reuse must not hide missing data.
patch(f,'        requireCurrent(current);\n        if (spacing > 8 || !available()) return Tile.EMPTY;\n        var previousDemand=',
'''        requireCurrent(current);
        if(LodExperiment.demand){synchronized(chunks){chunks.clear();cachedBlocks=0;}}
        if (spacing > 8 || !available()) return Tile.EMPTY;
        var previousDemand=''')
# Preserve compact maps through postprocessing, rather than unpacking them into HashMaps.
for name in ['PredictionVegetation.java','PredictionLeafStates.java','PredictionBamboo.java']:
    path=package/name;t=path.read_text(encoding='utf-8')
    t=t.replace('Map.copyOf(exterior)','ColumnBlocks.snapshot(exterior)').replace('Map.copyOf(result)','ColumnBlocks.snapshot(result)')
    t=t.replace('new HashMap<>(source)','ColumnBlocks.mutableCopy(source)')
    path.write_text(t,encoding='utf-8')
f=package/'PredictionVoxySource.java'
patch(f,'        plants.forEachVoxel((x,y,z,size,state)->{','        plants.forEachSpan((x,y,z,maxX,maxY,maxZ,state)->{')
patch(f,'features.add(x,y,z,x+size,y+size,z+size,state);','features.add(x,y,z,maxX,maxY,maxZ,state);')
f=package/'PredictionVegetationDisplayCache.java'
patch(f,'        long expected=0;for(var source:sources)expected+=source.blocks().size();',
'''        if(LodExperiment.spans){
            var result=new ColumnBlocks();
            for(var source:sources){
                if(!(source.blocks() instanceof ColumnBlocks columns))throw new AssertionError("unpacked source");
                columns.runs((px,pz,b,t,s)->{
                    if(px<x-1||px>=x+span+1||pz<z-1||pz>=z+span+1)return;
                    if(PredictionVegetation.vegetation(s)&&!trees)return;
                    for(int y=b;y<t;y++)if(!captured.test(new BlockPos(px,y,pz)))result.put(px,y,pz,s);
                });
            }
            return new SpanTile((ColumnBlocks)ColumnBlocks.snapshot(result),x,z,span,spacing);
        }
        long expected=0;for(var source:sources)expected+=source.blocks().size();''')
f=package/'PredictionTreeModels.java'
patch(f,'        BlockPos[] accepted = new BlockPos[model.size()];',
      '        LodExperiment.modelCalls++;LodExperiment.modelCells+=model.size();\n        BlockPos[] accepted = new BlockPos[model.size()];')
# The harness is replaced by a clean full-runtime driver, avoiding previous experimental switches.
shutil.copy2(here/'PredictionGenerationComparison.java',out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java')
shutil.copy2(here/'ExperimentChecks.java',out/'harness-java/dev/xantha/vss/client/prediction/ExperimentChecks.java')
shutil.copy2(here/'MappedChecks.java',out/'harness-java/dev/xantha/vss/client/prediction/MappedChecks.java')
# Avoid unrelated command hooks while running this headless harness (same entry as previous experiments).
manifest={'baseline':str(base),'scope':'Exact ordered column overlay, direct span output, bounded terminal demand; no renderer/disk/queues',
          'inputs':{n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ['worldgen-profile.bin','structure-templates.json']},
          'patches':patches,'baselineSources':{str(p.relative_to(base)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['main-java','main-resources','harness-java'] for p in (base/folder).rglob('*') if p.is_file()},
          'sources':{str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['main-java','main-resources','harness-java'] for p in (out/folder).rglob('*') if p.is_file()}}
(out/'experiment-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(out)
