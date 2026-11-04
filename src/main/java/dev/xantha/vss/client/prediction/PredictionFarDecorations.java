package dev.xantha.vss.client.prediction;

import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

/** World-owned distant decoration context. Approximate placements never enter exact surface/disk caches. */
final class PredictionFarDecorations {
    static boolean supported(ClientTerrainSampler terrain) {
        return (terrain instanceof RustTerrainSampler || terrain instanceof FreeTerraForgedTerrainSampler)
                && !terrain.interiorTerrain();
    }
    private final ClientTerrainSampler terrain;
    private volatile PredictionVegetation vegetation;
    private volatile PredictionFarDecoration models;
    private final java.util.concurrent.atomic.LongAdder woodyAnchors=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder scatteredBoxes=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder builds=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder nanos=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder previews=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder previewNanos=new java.util.concurrent.atomic.LongAdder();
    PredictionFarDecorations(ClientTerrainSampler terrain) {
        this.terrain=terrain;
    }
    private PredictionVegetation vegetation() {
        var value=vegetation;
        if(value!=null)return value;
        synchronized(this) {
            if(vegetation==null)vegetation=new PredictionVegetation(terrain,null,true);
            return vegetation;
        }
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
        return build(base,cities,trees,captured,current,true);
    }
    /** Publish template coverage without executing the remaining exact placement graph. */
    PredictionVoxySource.Tile preview(PredictionVoxySource.Tile base,
            dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities,boolean trees,
            LongPredicate captured,BooleanSupplier current) {
        return build(base,cities,trees,captured,current,false);
    }
    private PredictionVoxySource.Tile build(PredictionVoxySource.Tile base,
            dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities,boolean trees,
            LongPredicate captured,BooleanSupplier current,boolean complete) {
        long start=System.nanoTime();
        var session=models(current).session();
        try(var scope=session.enter(current,captured,base)) {
            boolean regenerate=!base.approximateDecorations() || base.needsDecorationRefinement();
            var scattered=regenerate ? session.generate(base.baseX(),base.baseZ(),base.span(),base.step(),true) : null;
            var fallback=complete && !base.surfaceComplete() ? vegetation().tileForRendering(base.baseX(),base.baseZ(),base.span(),1,trees,captured,current)
                    : PredictionVegetation.Tile.EMPTY;
            var retained=base.withVegetation(fallback,cities,complete);
            if(scattered!=null) {
                woodyAnchors.add(scattered.anchors().stream().filter(a->a.model().woody()).count());
                scatteredBoxes.add(scattered.boxes().size());
            }
            var boxes=new PredictionVoxyBoxes.Builder();
            // City box offsets retain their documented prefix; actual fallback edits win over scattered templates.
            boxes.addRange(retained.features(),0,retained.cityFeatures());
            if(scattered!=null) {
                if(trees)for(var b:scattered.boxes())boxes.add(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),b.state());
            } else boxes.addRange(base.features(),base.cityFeatures(),base.cityFeatures()+base.templateFeatures());
            int templateFeatures=boxes.size()-retained.cityFeatures();
            // A geometric upgrade replaces templates; completed fallback placement stays immutable.
            if(base.approximateDecorations() && (base.surfaceComplete() || !complete))
                boxes.addRange(base.features(),base.cityFeatures()+base.templateFeatures(),base.features().size());
            else if(!complete)boxes.addRange(base.features(),base.cityFeatures(),base.features().size());
            else boxes.addRange(retained.features(),retained.cityFeatures(),retained.features().size());
            if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("far decoration changed");
            if(complete)builds.increment();else previews.increment();
            return new PredictionVoxySource.Tile(base.key(),base.baseX(),base.baseZ(),base.span(),base.axis(),base.step(),
                    base.columns(),boxes.build(),base.terrainBytes(),complete || base.surfaceComplete(),retained.cityFeatures(),null,true,
                    regenerate?base.step():base.decorationStep(),templateFeatures);
        } finally {
            long elapsed=System.nanoTime()-start;
            if(complete)nanos.add(elapsed);else previewNanos.add(elapsed);
        }
    }
    void invalidate(int x,int z) { var value=vegetation;if(value!=null)value.invalidate(x,z); }
    java.util.Map<String,Object> performanceSnapshot() {
        return java.util.Map.of("initialized",models!=null,"previewBuilds",previews.sum(),"previewNanos",previewNanos.sum(),
                "completionBuilds",builds.sum(),"completionNanos",nanos.sum(),"woodyAnchors",woodyAnchors.sum(),"boxes",scatteredBoxes.sum());
    }
    String diagnostics() { return "farDecoration="+performanceSnapshot(); }
}
