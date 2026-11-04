package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.*;

class PredictionFarPlacementCountTest {
    @BeforeAll static void bootstrap() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }

    @Test void nominalDensityMatchesOriginalRulesAndRandomConsumption() throws Exception {
        var modifiers=List.of(CountPlacement.of(7),CountPlacement.of(UniformInt.of(1,12)),
                NoiseBasedCountPlacement.of(20,80,0),NoiseThresholdCountPlacement.of(-0.5,2,12),
                RarityFilter.onAverageOnceEvery(5));
        for(var modifier:modifiers) for(int seed=0;seed<256;seed++) {
            var expected=RandomSource.create(seed);var actual=RandomSource.create(seed);
            var pos=new BlockPos(seed*31-4096,64,seed*79-1024);
            assertEquals(original(modifier,expected,pos),PredictionFarPlacementCount.sample(modifier,actual,pos),
                    modifier.getClass().getSimpleName()+" seed="+seed);
            assertEquals(expected.nextLong(),actual.nextLong(),"Density sampling must consume the same random values");
        }
    }

    private static int original(PlacementModifier modifier,RandomSource random,BlockPos pos) throws Exception {
        // A signature probe keeps this reference test independent of version-specific method names.
        for(var method:PlacementModifier.class.getMethods()) {
            var p=method.getParameterTypes();
            if(p.length==3 && p[0]==PlacementContext.class && p[1]==RandomSource.class && p[2]==BlockPos.class
                    && java.util.stream.Stream.class.isAssignableFrom(method.getReturnType()))
                return (int)((java.util.stream.Stream<?>)method.invoke(modifier,null,random,pos)).count();
            if(p.length==4 && p[0]==PlacementContext.class && p[1]==RandomSource.class && p[2]==BlockPos.class
                    && p[3]==java.util.function.Consumer.class) {
                var positions=new ArrayList<BlockPos>();
                method.invoke(modifier,null,random,pos,(java.util.function.Consumer<BlockPos>)positions::add);
                return positions.size();
            }
        }
        throw new AssertionError("Original placement API missing");
    }

    @Test void unsupportedModifiersCannotBecomeAnIncorrectTemplateDensity() {
        assertThrows(UnsupportedOperationException.class,()->PredictionFarPlacementCount.sample(
                InSquarePlacement.spread(),RandomSource.create(42),BlockPos.ZERO));
    }
}
