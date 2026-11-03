package dev.xantha.vss.client.prediction;

import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Excludes only features whose complete candidate envelope cannot reach an
 * allowed biome. A missing proof executes the unchanged placement path. */
final class PredictionBiomePruning {
    private final ClientTerrainSampler terrain;
    private final ClientTerrainSampler context;
    private final Map<PlacedFeature, Candidate> candidates = new IdentityHashMap<>();
    private final LongAdder checks = new LongAdder(), skipped = new LongAdder(), queries = new LongAdder();
    private record Candidate(PredictionFeatureBiomeBounds.Plan plan, BitSet allowed) { }
    private static final Candidate UNKNOWN = new Candidate(null,null);

    PredictionBiomePruning(ClientTerrainSampler terrain, ClientTerrainSampler context) {
        this.terrain=terrain; this.context=context;
    }

    Job job() { return new Job(); }

    final class Job {
        private final Map<Rectangle, BitSet> proofs = new HashMap<>();
        boolean excludes(PlacedFeature feature, PredictionDecorationLevel level, BlockPos origin) {
            if (!(terrain instanceof RustTerrainSampler rust)) return false;
            Candidate candidate;
            synchronized (candidates) { candidate=candidates.computeIfAbsent(feature,PredictionBiomePruning.this::compile); }
            if (candidate==UNKNOWN) return false;
            checks.increment();
            var bounds=candidate.plan.bounds(origin,new WorldGenerationContext(context.generatorContext(),level),
                    terrain.profile().minY(),Math.addExact(terrain.profile().minY(),terrain.profile().height()-1));
            if (bounds==null) return false;
            var q=PredictionFeatureBiomeBounds.quartEnvelope(bounds);
            if (q.minY() < -500_000 || q.maxY() > 500_000) return false;
            var key=new Rectangle(q.minX(),q.minZ(),q.maxX(),q.maxZ());
            BitSet possible;
            if (proofs.containsKey(key)) possible=proofs.get(key);
            else {
                queries.increment();
                possible=rust.possibleBiomes(key.minX,key.minZ,key.maxX,key.maxZ);
                proofs.put(key,possible);
            }
            if (possible==null || possible.intersects(candidate.allowed)) return false;
            skipped.increment(); return true;
        }
    }

    private Candidate compile(PlacedFeature feature) {
        var plan=PredictionFeatureBiomeBounds.compile(feature);
        if (!plan.known() || context.generatorContext()==null) return UNKNOWN;
        var allowed=new BitSet();
        for (int i=0;i<context.cacheBiomes().size();i++) {
            var biome=context.cacheBiomeHolder(i);
            if (biome==null) return UNKNOWN;
            if (context.generatorContext().getBiomeGenerationSettings(biome).hasFeature(feature)) allowed.set(i);
        }
        return new Candidate(plan,allowed);
    }

    Map<String,Object> snapshot() {
        return Map.of("checks",checks.sum(),"skipped",skipped.sum(),"queries",queries.sum());
    }
    private record Rectangle(int minX,int minZ,int maxX,int maxZ) { }
}
