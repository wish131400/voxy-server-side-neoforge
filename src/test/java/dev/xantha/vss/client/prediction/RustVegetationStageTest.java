package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.Test;

class RustVegetationStageTest {
    @Test
    void reusesVolumeAcrossStepsAndUploadsInterveningJavaEdits() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        var doc = LithostitchedNativeTest.document();
        var first = JsonParser.parseString("""
                {"feature":{"type":"minecraft:simple_block","config":{"to_place":{
                  "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:short_grass"}}}},
                 "placement":[{"type":"minecraft:heightmap","heightmap":"WORLD_SURFACE_WG"}]}
                """);
        var second = JsonParser.parseString("""
                {"feature":{"type":"minecraft:simple_block","config":{"to_place":{
                  "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:dandelion"}}}},
                 "placement":[{"type":"minecraft:heightmap","heightmap":"WORLD_SURFACE_WG"},
                  {"type":"minecraft:block_predicate_filter","predicate":{
                    "type":"minecraft:all_of","predicates":[
                      {"type":"minecraft:matching_blocks","blocks":["minecraft:air"]},
                      {"type":"minecraft:matching_blocks","offset":[1,0,0],"blocks":["minecraft:dirt"]}]}}]}
                """);
        doc.add("placed_features", new JsonObject());
        doc.getAsJsonObject("placed_features").add("test:first", first);
        doc.getAsJsonObject("placed_features").add("test:second", second);
        doc.add("possible_biomes", JsonParser.parseString("[\"minecraft:plains\"]"));
        doc.getAsJsonObject("biomes").getAsJsonObject("minecraft:plains")
                .add("features", JsonParser.parseString("[[\"test:first\"],[\"test:second\"]]"));
        doc.add("input_states", JsonParser.parseString("""
                [{"Name":"minecraft:air"},{"Name":"minecraft:short_grass"},
                 {"Name":"minecraft:dirt"},{"Name":"minecraft:dandelion"}]
                """));
        var ops = net.minecraft.data.registries.VanillaRegistries.createLookup().createSerializationContext(JsonOps.INSTANCE);
        var firstFeature = PlacedFeature.DIRECT_CODEC.parse(ops,first).getOrThrow();
        var secondFeature = PlacedFeature.DIRECT_CODEC.parse(ops,second).getOrThrow();
        var registry = new MappedRegistry<PlacedFeature>(Registries.PLACED_FEATURE,Lifecycle.stable());
        Registry.register(registry,ResourceLocation.parse("test:first"),firstFeature);
        Registry.register(registry,ResourceLocation.parse("test:second"),secondFeature);
        registry.freeze();
        var access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                -64,384,"noise","minecraft:overworld",1L);
        var context = new ClientTerrainSampler(1,profile) {
            @Override RegistryAccess decorationAccess() { return access; }
        };
        long world = RustWorldgenBackend.create(1,0,doc.toString());
        try (var sampler = new RustTerrainSampler(world,profile,context)) {
            assertTrue(sampler.supports("test:first"),RustWorldgenBackend.support(world,"\"test:first\"",1));
            assertTrue(sampler.supports("test:second"),RustWorldgenBackend.support(world,"\"test:second\"",1));
            var sample = sampler.sampleSurface(0,0);
            int targetY = Math.max(sample.surfaceY(),sample.fluidY());
            var level = new PredictionDecorationLevel(sampler,context,access,0,0);
            level.setBlock(new BlockPos(0,targetY-1,0),Blocks.DIRT.defaultBlockState(),19,0);
            var volumeField = RustVegetationStage.class.getDeclaredField("volume");
            volumeField.setAccessible(true);
            var stage = new RustVegetationStage(sampler,level,0,0);
            long volume;
            try (stage) {
                stage.selectStep(List.of(firstFeature),0);
                assertEquals(ResourceLocation.parse("test:first"),registry.getKey(firstFeature));
                assertArrayEquals(new String[]{"test:first"},sampler.featureOrder()[0]);
                assertTrue(stage.place(0));
                stage.finish();
                volume = volumeField.getLong(stage);
                assertNotEquals(0,volume);
                assertEquals(Blocks.SHORT_GRASS.defaultBlockState(),level.placed().get(new BlockPos(0,targetY,0)));
                // Structures execute between steps without afterJava(). The
                // next step must upload those edits, including explicit air.
                level.beginStructure();
                level.setBlock(new BlockPos(0,targetY,0),Blocks.AIR.defaultBlockState(),19,0);
                level.setBlock(new BlockPos(1,targetY,0),Blocks.DIRT.defaultBlockState(),19,0);
                level.endFeature(true);
                stage.selectStep(List.of(secondFeature),1);
                assertTrue(stage.place(0));
                stage.finish();
                assertEquals(volume,volumeField.getLong(stage),"reuse the original neighbourhood");
                assertEquals(Blocks.DANDELION.defaultBlockState(),level.placed().get(new BlockPos(0,targetY,0)));
                assertEquals(Blocks.DIRT.defaultBlockState(),level.placed().get(new BlockPos(1,targetY,0)));
            }
            assertEquals(0,volumeField.getLong(stage));
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.describe(volume));
        }
    }
}
