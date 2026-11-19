package dev.xantha.vss.client.prediction;

import net.minecraft.core.BlockPos;

/** Keeps full surface hooks when native density is unavailable or a custom density requires Java. */
final class PredictionSurfaceTerrainSampler extends ClientTerrainSampler {
    private final ClientTerrainSampler source;
    private final PredictionSurfaceStage surfaces;
    private final PredictionChunkTerrain terrain;
    PredictionSurfaceTerrainSampler(ClientTerrainSampler source) {
        super(source,null);this.source=source;
        terrain=new PredictionChunkTerrain(source);
        surfaces=new PredictionSurfaceStage(source,terrain::fill);
    }
    @Override public ClientColumnSample sample(int x,int z){return surfaces.sample(x,z);}
    @Override public ClientColumnSample sampleSurface(int x,int z){return sample(x,z);}
    @Override public ClientColumnSample samplePreview(int x,int z){return PredictionSurfaceRefinement.basic(source.samplePreview(x,z));}
    @Override public ClientColumnSample samplePreview(int x,int z,int step){return PredictionSurfaceRefinement.basic(source.samplePreview(x,z,step));}
    @Override ClientColumnSample sampleInterior(int x,int z){return sample(x,z);}
    @Override PredictionColumnVolume exteriorColumn(int x,int z){return surfaces.column(x,z);}
    @Override public int surfaceY(int x,int z){return sample(x,z).surfaceY();}
    @Override public int groundY(int x,int z){return surfaceY(x,z);}
    @Override int previewSurfaceY(int x,int z){return surfaceY(x,z);}
    @Override public ClientColumnSample sampleForLod(int x,int z,int step){return PredictionSurfaceRefinement.basic(source.sampleForLod(x,z,step));}
    @Override ClientColumnSample sampleDisplay(int x,int z){return PredictionSurfaceRefinement.basic(source.sampleDisplay(x,z));}
    @Override boolean supportsLodGrid(){return source.supportsLodGrid();}
    @Override boolean sampleLodGridRequest(int x,int z,int step,int size,ClientColumnSample[] target,
            boolean preview,java.util.function.BooleanSupplier valid) {
        // Protect completed surface/capture samples from being marked pending again.
        var before=target.clone();
        boolean handled=source.sampleLodGridRequest(x,z,step,size,target,preview,valid);
        markBasic(target,before,0,size,size,size);
        return handled;
    }
    @Override boolean sampleLodGrid(int x,int z,int step,int width,int height,ClientColumnSample[] target,
            int offset,int stride,boolean preview) {
        var before=target.clone();
        boolean handled=source.sampleLodGrid(x,z,step,width,height,target,offset,stride,preview);
        markBasic(target,before,offset,stride,width,height);
        return handled;
    }
    private static void markBasic(ClientColumnSample[] target,ClientColumnSample[] before,
            int offset,int stride,int width,int height) {
        for(int z=0;z<height;z++)for(int x=0;x<width;x++) {
            int i=offset+z*stride+x;
            if(before[i]==null && target[i]!=null)target[i]=PredictionSurfaceRefinement.basic(target[i]);
        }
    }
    @Override int initialTerrainCellAxis(int lod){return PredictionWorkOrder.initialCellAxis(lod);}
    @Override boolean stagedSurface(){return true;}
    @Override boolean supportsFarDecorations(){return source.supportsFarDecorations();}
    @Override void refineSurfaceChunk(int baseX,int baseZ,int step,int grid,ClientColumnSample[] samples,
            int fromX,int toX,int fromZ,int toZ,java.util.function.BooleanSupplier current) {
        surfaces.refineChunk(baseX,baseZ,step,grid,samples,fromX,toX,fromZ,toZ,current);
    }
    @Override String densityCompilerDiagnostics(){return source.densityCompilerDiagnostics()+",surfaceChunks={"+surfaces.diagnostics()+"},surfaceTerrain={"+terrain.diagnostics()+"}";}
    @Override boolean basicInteriorTerrain(){return source.interiorTerrain();}
    @Override ClientColumnSample sampleBasicInterior(int x,int z){return PredictionSurfaceRefinement.basic(source.sampleInterior(x,z));}
    @Override SamplingScope terrainRequest(){return source.terrainRequest();}
    @Override boolean interiorTerrain(){return true;}
    @Override ClientTerrainSampler decorationContext(){return source;}
    @Override public void close(){surfaces.close();source.close();}
}
