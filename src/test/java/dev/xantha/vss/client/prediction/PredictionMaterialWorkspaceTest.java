package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionMaterialWorkspaceTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}

    @Test void pairIdentityEqualityZeroAndMapperReplacementRemainCorrect() throws Exception {
        var calls=new AtomicInteger();
        var palette=new PredictionVoxyMaterials((s,b)->{calls.incrementAndGet();return 0;});
        var stone=Blocks.STONE.defaultBlockState();var dirt=Blocks.DIRT.defaultBlockState();
        assertEquals(0,palette.map(stone,new String("Aa")));
        assertEquals(0,palette.map(dirt,"BB"));
        assertEquals(0,palette.map(stone,new String("Aa")));
        assertEquals(2,calls.get());
        assertEquals(0,palette.map(stone,"BB")); // Equal hash is not an equal biome.
        assertEquals(3,calls.get());
        palette.map(null,null);palette.map(dirt,"BB");palette.map(null,null);
        assertEquals(4,calls.get());
        palette.reset((s,b)->87);
        assertEquals(87,palette.map(stone,"Aa"));
        palette.clear();
        for(String name:List.of("states","biomes")) {
            var field=PredictionVoxyMaterials.class.getDeclaredField(name);field.setAccessible(true);
            for(Object value:(Object[])field.get(palette))assertNull(value,"No world/biome references retained after release");
        }
        var mapper=PredictionVoxyMaterials.class.getDeclaredField("mapper");mapper.setAccessible(true);
        assertNull(mapper.get(palette));
    }

    @Test void oversizedPalettesRemainExactAndReleaseExcessCapacity() throws Exception {
        var palette=new PredictionVoxyMaterials((s,b)->Long.parseLong(b));
        var stone=Blocks.STONE.defaultBlockState();
        for(int pass=0;pass<2;pass++)for(int i=0;i<12000;i++)assertEquals(i,palette.map(stone,Integer.toString(i)));
        palette.clear();
        var states=PredictionVoxyMaterials.class.getDeclaredField("states");states.setAccessible(true);
        assertTrue(((Object[])states.get(palette)).length<=256);
        palette.reset((s,b)->-4);assertEquals(-4,palette.map(stone,"100"));
    }

    @Test void nestedFailedAndParallelConversionsCannotReuseAnotherCallsMaterials() throws Exception {
        var tile=PredictionVoxyPreparationTest.fixture(0,0,64,47);
        var pos=VoxyPredictionBridge.position(0,0,1,0);
        var build=PredictionMaterialReplayTest.buildMethod();
        try(var source=new PredictionVoxySource()) {
            var mapper=PredictionVoxyPreparationTest.MAPPER;
            var expected=new long[32768];build.invoke(source,pos,mapper,List.of(tile),expected,false);
            var entered=new AtomicBoolean();
            VoxyPredictionBridge.Mapper nested=(state,biome)->{
                if(entered.compareAndSet(false,true)) {
                    try {
                        var inner=new long[32768];build.invoke(source,pos,mapper,List.of(tile),inner,false);
                        assertArrayEquals(expected,inner);
                    } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
                }
                return mapper.map(state,biome);
            };
            var actual=new long[32768];build.invoke(source,pos,nested,List.of(tile),actual,false);
            assertTrue(entered.get());assertArrayEquals(expected,actual);
            assertThrows(InvocationTargetException.class,()->build.invoke(source,pos,(VoxyPredictionBridge.Mapper)(s,b)->{throw new IllegalStateException("fixture");},List.of(tile),actual,false));
            build.invoke(source,pos,mapper,List.of(tile),actual,false);assertArrayEquals(expected,actual);
            var pool=Executors.newFixedThreadPool(4);
            try {
                var work=new ArrayList<Callable<long[]>>();
                for(int i=0;i<16;i++)work.add(()->{var data=new long[32768];build.invoke(source,pos,mapper,List.of(tile),data,false);return data;});
                for(var result:pool.invokeAll(work))assertArrayEquals(expected,result.get());
            } finally {pool.shutdownNow();}
        }
    }
}
