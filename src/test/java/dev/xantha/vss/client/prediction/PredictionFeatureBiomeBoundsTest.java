package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionFeatureBiomeBoundsTest {
    @BeforeAll static void bootstrap() { PredictionDecorationQueryTest.bootstrap(); }

    @Test void offsetsAndVerticalScansCoverEveryCandidateBeforeBiomeFiltering() {
        var feature=PredictionPlacementExecutorTest.feature(List.of(CountPlacement.of(7),InSquarePlacement.spread(),
                HeightRangePlacement.uniform(VerticalAnchor.absolute(90),VerticalAnchor.absolute(105)),
                RandomOffsetPlacement.of(UniformInt.of(-3,7),UniformInt.of(-2,6)),
                EnvironmentScanPlacement.scanningFor(Direction.UP,BlockPredicate.alwaysTrue(),8),BiomeFilter.biome()));
        var fixture=new PredictionPlacementExecutorTest.Fixture();
        var plan=PredictionFeatureBiomeBounds.compile(feature,c->true);
        assertTrue(plan.known());
        var bounds=plan.bounds(new BlockPos(-32,-64,48),new WorldGenerationContext(fixture.generator,fixture.level()),-64,319);
        assertEquals(new PredictionFeatureBiomeBounds.Bounds(-35,88,45,-10,119,70),bounds);
    }

    @Test void analysedRangesContainActualVanillaPlacementOutputsWithoutConsumingTheirRandomStream() {
        var modifiers=List.<PlacementModifier>of(CountPlacement.of(UniformInt.of(2,8)),InSquarePlacement.spread(),
                HeightRangePlacement.uniform(VerticalAnchor.absolute(91),VerticalAnchor.absolute(102)),
                RandomOffsetPlacement.of(UniformInt.of(-4,5),UniformInt.of(-3,2)));
        var withBiome=new java.util.ArrayList<>(modifiers);withBiome.add(BiomeFilter.biome());
        var feature=PredictionPlacementExecutorTest.feature(withBiome);
        var fixture=new PredictionPlacementExecutorTest.Fixture();var origin=new BlockPos(-32,-64,48);
        var bounds=PredictionFeatureBiomeBounds.compile(feature,c->true).bounds(origin,
                new WorldGenerationContext(fixture.generator,fixture.level()),-64,319);
        assertNotNull(bounds);
        var original=PredictionPlacementExecutorTest.feature(modifiers);
        for(int seed=0;seed<64;seed++) {
            var random=net.minecraft.util.RandomSource.create(seed);
            PredictionPlacementExecutor.stream(original,new PlacementContext(fixture.level(),fixture.generator,
                    java.util.Optional.of(original)),random,origin,p->{
                assertTrue(p.getX()>=bounds.minX() && p.getX()<=bounds.maxX());
                assertTrue(p.getY()>=bounds.minY() && p.getY()<=bounds.maxY());
                assertTrue(p.getZ()>=bounds.minZ() && p.getZ()<=bounds.maxZ());
            });
        }
    }

    @Test void holderPredicatesDoNotNeedRegistrySerializationAndAllScanDirectionsRemainBounded() {
        for(var direction:Direction.values()) {
            var feature=PredictionPlacementExecutorTest.feature(List.of(BlockPredicateFilter.forPredicate(
                    BlockPredicate.matchesTag(net.minecraft.tags.BlockTags.DIRT)),
                    EnvironmentScanPlacement.scanningFor(direction,BlockPredicate.alwaysTrue(),8),BiomeFilter.biome()));
            var fixture=new PredictionPlacementExecutorTest.Fixture();
            var plan=PredictionFeatureBiomeBounds.compile(feature,c->true);assertTrue(plan.known());
            var bounds=plan.bounds(BlockPos.ZERO,new WorldGenerationContext(fixture.generator,fixture.level()),-64,319);
            assertNotNull(bounds);
            assertEquals(Math.min(0,direction.getStepX()*8),bounds.minX());
            assertEquals(Math.max(0,direction.getStepX()*8),bounds.maxX());
            assertEquals(Math.min(0,direction.getStepY()*8),bounds.minY());
            assertEquals(Math.max(0,direction.getStepY()*8),bounds.maxY());
            assertEquals(Math.min(0,direction.getStepZ()*8),bounds.minZ());
            assertEquals(Math.max(0,direction.getStepZ()*8),bounds.maxZ());
        }
    }

    @Test void modifiedProvidersUnknownHeightAndAbsentBiomeFilterKeepOriginalPath() {
        var feature=PredictionPlacementExecutorTest.feature(List.of(CountPlacement.of(2),BiomeFilter.biome()));
        assertFalse(PredictionFeatureBiomeBounds.compile(feature,c->c!=CountPlacement.class).known());
        assertFalse(PredictionFeatureBiomeBounds.compile(feature,c->c!=ConstantInt.class).known());
        assertFalse(PredictionFeatureBiomeBounds.compile(PredictionPlacementExecutorTest.feature(List.of(
                HeightRangePlacement.of(TrapezoidHeight.of(VerticalAnchor.bottom(),VerticalAnchor.top(),0)),
                BiomeFilter.biome())),c->true).known());
        assertFalse(PredictionFeatureBiomeBounds.compile(PredictionPlacementExecutorTest.feature(List.of(
                InSquarePlacement.spread())),c->true).known());
    }

    @Test void firstBiomeFilterTerminatesBoundsAndOverflowReturnsUnknown() {
        var feature=PredictionPlacementExecutorTest.feature(List.of(InSquarePlacement.spread(),BiomeFilter.biome(),
                RandomOffsetPlacement.vertical(UniformInt.of(-100,100))));
        var fixture=new PredictionPlacementExecutorTest.Fixture();
        var plan=PredictionFeatureBiomeBounds.compile(feature,c->true);assertTrue(plan.known());
        var context=new WorldGenerationContext(fixture.generator,fixture.level());
        assertEquals(new PredictionFeatureBiomeBounds.Bounds(0,0,0,15,0,15),plan.bounds(BlockPos.ZERO,context,-64,319));
        assertNull(plan.bounds(new BlockPos(Integer.MAX_VALUE,0,0),context,-64,319));
    }

    @Test void quartEnvelopeCoversAllEightCornersAtNegativeBoundaries() {
        for(int min=-19;min<=19;min++)for(int max=min;max<=Math.min(19,min+7);max++) {
            var q=PredictionFeatureBiomeBounds.quartEnvelope(new PredictionFeatureBiomeBounds.Bounds(min,min,min,max,max,max));
            for(int block=min;block<=max;block++)for(int corner=0;corner<2;corner++) {
                int possible=Math.floorDiv(block-2,4)+corner;
                assertTrue(possible>=q.minX() && possible<=q.maxX());
                assertTrue(possible>=q.minY() && possible<=q.maxY());
                assertTrue(possible>=q.minZ() && possible<=q.maxZ());
            }
        }
    }
}
