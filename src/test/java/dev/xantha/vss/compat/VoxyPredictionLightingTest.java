package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class VoxyPredictionLightingTest {
    @BeforeAll static void bootstrap() throws Exception {
        var method=Class.forName("dev.xantha.vss.client.prediction.ClientTerrainSamplerTest")
                .getDeclaredMethod("bootstrapMinecraft");method.setAccessible(true);method.invoke(null);
    }
    @Test void rendererMappingSeparatesSkyAndEmissionForEveryDimensionType() throws Exception {
        for(int sky:new int[]{0,15}) {
            Class<?> type=Class.forName(VoxyPredictionBridge.class.getName()+"$Context");
            Object context=mock(type,CALLS_REAL_METHODS);
            set(context,"skyLight",sky);
            var blocks=new HashMap<BlockState,Integer>();
            blocks.put(Blocks.NETHERRACK.defaultBlockState(),3);
            blocks.put(Blocks.LAVA.defaultBlockState(),4);
            blocks.put(Blocks.GLOWSTONE.defaultBlockState(),5);
            set(context,"blocks",blocks);set(context,"biomes",Map.of("minecraft:nether_wastes",7));
            var mapper=(VoxyPredictionBridge.Mapper)context;
            assertEquals(sky!=0,mapper.hasSkyLight());
            assertEquals((long)sky<<56,mapper.map(Blocks.AIR.defaultBlockState(),"minecraft:nether_wastes"));
            assertEquals((long)sky<<56,mapper.map(null,"minecraft:nether_wastes"));
            for(var entry:blocks.entrySet()) {
                long value=mapper.map(entry.getKey(),"minecraft:nether_wastes");
                assertEquals(sky,value>>>56&15);assertEquals(entry.getKey().getLightEmission(),value>>>60);
                assertEquals(entry.getValue().intValue(),value>>>27&0xfffff);assertEquals(7,value>>>47&511);
            }
        }
    }
    @Test void missingAuthoritativeAirUsesDimensionDefaultButStoredLightIsNeverRewritten() {
        for(int lod=0;lod<=4;lod++) {
            long[] predicted=new long[32768];Arrays.fill(predicted,1L<<27);
            VoxyPredictionBridge.mergeRealVoxels(predicted,null,lod,0,0,0,(x,z)->x==0&&z==0,0);
            assertEquals(0,predicted[0]);assertEquals(1L<<27,predicted[16>>lod]);
            var real=new long[32768];real[0]=0x93L<<56;
            VoxyPredictionBridge.mergeRealVoxels(predicted,new VoxyRenderAccess.Voxels(real,0),lod,0,0,0,(x,z)->true,0);
            assertEquals(0x93L<<56,predicted[0],"Actual light from Voxy must win byte for byte");
        }
    }
    private static void set(Object context,String name,Object value) throws Exception {
        Field field=context.getClass().getDeclaredField(name);field.setAccessible(true);field.set(context,value);
    }
}
