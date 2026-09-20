package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.junit.jupiter.api.*;

class PredictionNativeNetherFeatureTest {
    @BeforeAll static void bootstrap() { PredictionNetherDecorationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionNetherDecorationTest.restore(); }

    @Test void originalNetherFeaturesMatchEveryJavaWriteAndRandomContinuation() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var context=PredictionNetherDecorationTest.sampler(Biomes.CRIMSON_FOREST,true);
        var doc=PredictionNetherDecorationTest.nativeDocument("minecraft:crimson_forest");
        var states=new JsonArray();
        for(var block:BuiltInRegistries.BLOCK) for(var state:block.getStateDefinition().getPossibleStates()) states.add(RustWorldgenDocument.encodeState(state));
        doc.add("input_states",states);
        var features=JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                "tools/rust/vss-native-core/tests/fixtures/worldgen/features.json"))).getAsJsonObject();
        var ops=PredictionNetherDecorationTest.lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        try(var sampler=new RustTerrainSampler(RustWorldgenBackend.create(42,0,doc.toString()),context.profile(),context)) {
            for(String name:List.of("crimson_fungus","warped_fungus","crimson_forest_vegetation","warped_forest_vegetation",
                    "nether_sprouts","glowstone_extra","weeping_vines","twisting_vines","spring_nether_closed","patch_fire","patch_soul_fire")) {
                var json=features.getAsJsonObject("configured_features").get("minecraft:"+name);
                var feature=ConfiguredFeature.DIRECT_CODEC.parse(ops,json).getOrThrow();
                var ground=name.contains("warped")||name.equals("twisting_vines")?Blocks.WARPED_NYLIUM:
                        name.equals("patch_fire")?Blocks.NETHERRACK:name.equals("patch_soul_fire")?Blocks.SOUL_SOIL:Blocks.CRIMSON_NYLIUM;
                var volume=new PredictionColumnVolume(new int[]{0,55,PredictionNetherDecorationTest.id(Blocks.NETHERRACK),0,
                        55,56,PredictionNetherDecorationTest.id(ground),0,80,128,PredictionNetherDecorationTest.id(Blocks.NETHERRACK),0});
                var terrain=new ClientTerrainSampler(42,context.profile()) {
                    @Override ClientColumnSample sampleInterior(int x,int z) { return volume.asSample(); }
                };
                var input=ByteBuffer.allocateDirect(80*80*128*4).order(ByteOrder.LITTLE_ENDIAN);
                for(int x=0;x<80;x++) for(int z=0;z<80;z++) for(int y=0;y<128;y++) {
                    int block=volume.blockAt(y);var state=block==ClientColumnSample.NO_BLOCK?Blocks.AIR.defaultBlockState():BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
                    input.putInt(sampler.stateId(state));
                }
                int writes=0;
                for(int seed:new int[]{1,7,19,42,128,731}) {
                    int y=name.equals("glowstone_extra")||name.equals("weeping_vines")?79:name.startsWith("spring_")?20:56;
                    var pos=new BlockPos(8,y,8);
                    var level=new PredictionDecorationLevel(terrain,context,PredictionNetherDecorationTest.access,0,0);
                    var random=new WorldgenRandom(new XoroshiroRandomSource(seed));
                    boolean expected=feature.place(level,context.generatorContext(),random,pos);
                    long after=random.nextLong();
                    long handle=RustWorldgenBackend.createVolume(sampler.handle(),-32,0,-32,80,128,80,input);
                    try {
                        var result=JsonParser.parseString(RustWorldgenBackend.feature(handle,json.toString(),seed,8,y,8)).getAsJsonObject();
                        assertEquals(expected,result.get("placed").getAsBoolean(),name+" seed="+seed);
                        assertEquals(after,Long.parseLong(result.get("after").getAsString()),name+" random seed="+seed);
                        var output=ByteBuffer.allocateDirect(65536*16).order(ByteOrder.LITTLE_ENDIAN);
                        int count=RustWorldgenBackend.readEdits(handle,output);
                        var nativeWrites=new HashMap<BlockPos,BlockState>();
                        for(int i=0;i<count;i++) nativeWrites.put(new BlockPos(output.getInt(i*16),output.getInt(i*16+4),output.getInt(i*16+8)),sampler.states()[output.getInt(i*16+12)]);
                        var positions=new HashSet<>(level.placed().keySet());positions.addAll(nativeWrites.keySet());
                        for(var at:positions) assertEquals(level.placed().get(at),nativeWrites.get(at),name+" writes seed="+seed+" at="+at);
                        writes+=count;
                    } finally { RustWorldgenBackend.close(handle); }
                }
                if(!name.startsWith("spring_")) assertTrue(writes>0,name+" must exercise actual placement");
                System.out.println("NETHER_FEATURE_PARITY "+name+" seeds=6 writes="+writes);
            }
        }
    }
}
