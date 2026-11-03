package dev.xantha.vss.client.prediction;

import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

/** World-owned experimental context. Approximate placements never enter exact surface/disk caches. */
final class PredictionFarDecorations {
    static boolean supported(ClientTerrainSampler terrain) {
        return terrain instanceof RustTerrainSampler && !terrain.interiorTerrain();
    }
    private final RustTerrainSampler terrain;
    private final PredictionVegetation vegetation;
    private volatile PredictionFarDecoration models;
    private final java.util.concurrent.atomic.LongAdder woodyAnchors=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder scatteredBoxes=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder builds=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder nanos=new java.util.concurrent.atomic.LongAdder();
    PredictionFarDecorations(ClientTerrainSampler terrain) {
        this.terrain=(RustTerrainSampler)terrain;
        vegetation=new PredictionVegetation(terrain,null,true);
    }
    private PredictionFarDecoration models(BooleanSupplier current) {
        var value=models;
        if(value!=null)return value;
        synchronized(this) {
            if(models==null)models=new PredictionFarDecoration(terrain,true,8,current);
            return models;
        }
    }
    PredictionVoxySource.Tile build(PredictionVoxySource.Tile base,
            dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities,boolean trees,
            LongPredicate captured,BooleanSupplier current) {
        long start=System.nanoTime();
        var session=models(current).session();
        try(var scope=session.enter(current,captured,base)) {
            var fallback=vegetation.tileForRendering(base.baseX(),base.baseZ(),base.span(),1,trees,captured,current);
            var retained=base.withVegetation(fallback,cities,true);
            var scattered=session.generate(base.baseX(),base.baseZ(),base.span(),base.step(),true);
            woodyAnchors.add(scattered.anchors().stream().filter(a->a.model().woody()).count());
            scatteredBoxes.add(scattered.boxes().size());
            var boxes=new PredictionVoxyBoxes.Builder();
            // City box offsets retain their documented prefix; actual fallback edits win over scattered templates.
            boxes.addRange(retained.features(),0,retained.cityFeatures());
            if(trees)for(var b:scattered.boxes())boxes.add(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),b.state());
            boxes.addRange(retained.features(),retained.cityFeatures(),retained.features().size());
            if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("far decoration changed");
            builds.increment();
            return new PredictionVoxySource.Tile(base.key(),base.baseX(),base.baseZ(),base.span(),base.axis(),base.step(),
                    base.columns(),boxes.build(),base.terrainBytes(),true,retained.cityFeatures(),null,true);
        } finally { nanos.add(System.nanoTime()-start); }
    }
    void invalidate(int x,int z) { vegetation.invalidate(x,z); }
    String diagnostics() { return "farDecoration={builds="+builds.sum()+",ms="+nanos.sum()/1_000_000+",woodyAnchors="+woodyAnchors.sum()+",boxes="+scatteredBoxes.sum()+"}"; }
}
