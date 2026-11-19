package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.*;

class PredictionFarReuseTest {
    @BeforeAll static void bootstrap(){TerrainPredictionFixtures.bootstrap();}
    @Test void javaPlanWrapsSurfaceOnceAndKeepsOriginalGeneratorStages() {
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1);
        var source=new ClientTerrainSampler(42,profile);
        var generator=new com.google.gson.JsonObject();
        var plan=new PredictionExecutionPlan(false,true,"custom surface");
        try(var wrapped=plan.javaSampler(source,generator)) {
            assertInstanceOf(PredictionSurfaceTerrainSampler.class,wrapped);
            assertSame(wrapped,plan.javaSampler(wrapped,generator));
            generator.addProperty("vss_original_stages",true);
            assertSame(source,plan.javaSampler(source,generator));
        }
    }
    @Test void surfaceWrapperPreservesFarCapabilityWithoutChangingVolumeRequirements() {
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1);
        try(var wrapper=new PredictionSurfaceTerrainSampler(new ClientTerrainSampler(42,profile){
            @Override boolean supportsFarDecorations(){return true;}
        })) {
            assertTrue(wrapper.interiorTerrain());assertTrue(PredictionFarDecorations.supported(wrapper));
        }
        try(var wrapper=new PredictionSurfaceTerrainSampler(new ClientTerrainSampler(42,profile))) {
            assertFalse(PredictionFarDecorations.supported(wrapper));
        }
    }
    @Test void candidateChoicesSurviveLodChangesAndCapturesAreCheckedAfterCacheLookup() {
        var sampler=mock(ClientTerrainSampler.class);var calls=new AtomicInteger();
        var placed=new MappedRegistry<PlacedFeature>(Registries.PLACED_FEATURE,com.mojang.serialization.Lifecycle.stable());
        when(sampler.decorationContext()).thenReturn(sampler);
        when(sampler.profile()).thenReturn(new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1));
        when(sampler.decorationAccess()).thenReturn(new RegistryAccess.ImmutableRegistryAccess(List.of(placed.freeze())));
        when(sampler.cacheBiomes()).thenReturn(List.of("minecraft:plains"));
        when(sampler.sampleDisplay(anyInt(),anyInt())).thenAnswer(call->{calls.incrementAndGet();return PredictionVoxelFixtures.sample(64);});
        var model=new PredictionFarDecoration(sampler,false,8);
        model.initialize(()->true);
        var tree=new PredictionFarDecoration.Template(List.of(new PredictionFarDecoration.Span(0,0,0,8,Blocks.OAK_LOG.defaultBlockState())),8,true,Set.of());
        model.biomeRules.put(0,List.of(new PredictionFarDecoration.Rule(123,9,List.of(new PredictionFarDecoration.Choice(null,9,List.of(tree))),true)));
        var coarse=model.session();var a=coarse.generate(-64,-64,64,4,true);int initial=calls.get();
        assertTrue(initial>0);assertFalse(a.anchors().isEmpty());
        var fine=model.session();var b=fine.generate(-64,-64,64,1,true);
        assertEquals(a.anchors(),b.anchors());assertEquals(initial,calls.get(),"LOD changes must reuse choices and ground");
        try(var ignored=fine.enter(()->true,key->true,null)) {
            assertTrue(fine.generate(-64,-64,64,1,true).boxes().isEmpty());
        }
        assertEquals(a.anchors(),model.session().generate(-64,-64,64,4,true).anchors());
        assertTrue(model.selections.statistics().get("hits")>0);
        assertTrue(model.selections.statistics().get("bytes")<=8L<<20);
    }
    @Test void intervalVotesMatchFineVoxelUnionAcrossWordBoundariesAndOverlappingStates() {
        var random=new Random(42);var states=List.of(Blocks.OAK_LEAVES.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState());
        for(var state:states)if(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state)<0)
            net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.add(state);
        assertNotEquals(net.minecraft.world.level.block.Block.getId(states.get(0)),
                net.minecraft.world.level.block.Block.getId(states.get(1)));
        for(int step:new int[]{1,2,3,4,8,16}) {
            var votes=new PredictionFarDecoration.Votes(step);int[] occupied=new int[step*step*step];Arrays.fill(occupied,-1);
            for(int i=0;i<500;i++) {
                int x=random.nextInt(step),z=random.nextInt(step),a=random.nextInt(step),b=a+1+random.nextInt(step-a),state=random.nextInt(2);
                votes.add(states.get(state),x,z,a,b);
                for(int y=a;y<b;y++){int bit=(z*step+x)*step+y;if(occupied[bit]<0)occupied[bit]=state;}
            }
            int[] counts=new int[2];for(int v:occupied)if(v>=0)counts[v]++;
            for(int i=0;i<2;i++)assertEquals(counts[i],votes.counts.get(net.minecraft.world.level.block.Block.getId(states.get(i))));
        }
    }
}
