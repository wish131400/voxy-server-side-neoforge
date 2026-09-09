package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionSurfaceStructuresTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void vanillaDimensionStructuresUseTheirActualDecorationStages() {
        var registry = VanillaRegistries.createLookup().lookupOrThrow(Registries.STRUCTURE);
        var fortress = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.FORTRESS).value();
        var bastion = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.BASTION_REMNANT).value();
        var city = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.END_CITY).value();
        var ancient = registry.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.ANCIENT_CITY).value();
        assertTrue(PredictionSurfaceStructures.supported(fortress,true));
        assertTrue(PredictionSurfaceStructures.supported(bastion,true),"Mixed fortress/bastion sets must not be dropped");
        assertTrue(PredictionSurfaceStructures.supported(city,false));
        assertFalse(PredictionSurfaceStructures.supported(ancient,false),"Overworld underground structures remain excluded");
    }

    @Test void endCityEntityMarkersCannotAbortTheBuildingButChestMarkersRemain() {
        var tag = new net.minecraft.nbt.CompoundTag();
        var blocks = new net.minecraft.nbt.ListTag();
        for (String marker : List.of("Sentry","Elytra","Chest")) {
            var block = new net.minecraft.nbt.CompoundTag();
            var data = new net.minecraft.nbt.CompoundTag(); data.putString("metadata",marker);
            block.put("nbt",data); blocks.add(block);
        }
        tag.put("blocks",blocks);
        var untouched = tag.copy();
        PredictionStructureTemplates.stripEndCityEntityMarkers(ResourceLocation.parse("mod:end_city/ship"),untouched);
        assertEquals(tag,untouched);
        PredictionStructureTemplates.stripEndCityEntityMarkers(ResourceLocation.withDefaultNamespace("end_city/ship"),tag);
        assertEquals("",blocks.getCompound(0).getCompound("nbt").getString("metadata"));
        assertEquals("",blocks.getCompound(1).getCompound("nbt").getString("metadata"));
        assertEquals("Chest",blocks.getCompound(2).getCompound("nbt").getString("metadata"));
    }

    @Test void syncedVanillaHouseIsPlacedAsBlocksAndMissingTemplateProducesNothing() throws Exception {
        JsonObject root = JsonParser.parseString("""
                {"noises":{},"density_functions":{},
                 "biomes":{"vss:plain":{"has_precipitation":false,"temperature":0.7,"downfall":0.0,
                    "effects":{"fog_color":0,"water_color":0,"water_fog_color":0,"sky_color":0},
                    "spawners":{},"spawn_costs":{},"carvers":{},"features":[]}},
                 "processor_lists":{"vss:empty":{"processors":[]}},
                 "template_pools":{
                    "minecraft:empty":{"fallback":"minecraft:empty","elements":[]},
                    "vss:house":{"fallback":"minecraft:empty","elements":[{"weight":1,"element":{
                        "element_type":"minecraft:single_pool_element",
                        "location":"minecraft:village/plains/houses/plains_small_house_1",
                        "processors":"vss:empty","projection":"rigid"}}]}},
                 "structures":{"vss:house":{"type":"minecraft:jigsaw","biomes":["vss:plain"],
                    "step":"surface_structures","spawn_overrides":{},"terrain_adaptation":"none",
                    "start_pool":"vss:house","size":1,"start_height":{"absolute":64},
                    "use_expansion_hack":false,"max_distance_from_center":80}},
                 "structure_sets":{"vss:houses":{"structures":[{"structure":"vss:house","weight":1}],
                    "placement":{"type":"minecraft:random_spread","spacing":32,"separation":8,"salt":10387312}}}}
                """).getAsJsonObject();
        JsonObject templates = new JsonObject();
        String id = "minecraft:village/plains/houses/plains_small_house_1";
        try (var input = getClass().getResourceAsStream("/data/minecraft/structure/village/plains/houses/plains_small_house_1.nbt")) {
            assertNotNull(input, "vanilla Minecraft dependency must supply the original village template");
            templates.addProperty(id, java.util.Base64.getEncoder().encodeToString(input.readAllBytes()));
        }
        root.add("structure_templates", templates);
        var registries = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY);
        var access = registries.access();
        assertEquals(1, access.registryOrThrow(Registries.STRUCTURE).size());
        assertEquals(2, access.registryOrThrow(Registries.TEMPLATE_POOL).size());
        var sampler = sampler(access, templates);
        var structures = new PredictionSurfaceStructures(sampler);
        assertTrue(structures.available(), structures.diagnostics());
        var spread = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                access.registryOrThrow(Registries.STRUCTURE_SET).iterator().next().placement();
        var candidate = spread.getPotentialStructureChunk(42, 0, 0);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> placed = new java.util.HashMap<>();
        for (int z = candidate.z - 1; z <= candidate.z + 1; z++) for (int x = candidate.x - 1; x <= candidate.x + 1; x++) {
            var level = new PredictionDecorationLevel(sampler, sampler, access, x, z);
            structures.place(level, x, z, 0);
            placed.putAll(level.placed());
        }
        assertTrue(placed.size() > 50, structures.diagnostics());
        assertTrue(placed.values().stream().anyMatch(state -> state.is(Blocks.OAK_STAIRS)), "original house roof/stair states");
        assertTrue(placed.values().stream().anyMatch(state -> state.is(Blocks.COBBLESTONE)), "original house foundation");
        assertTrue(placed.keySet().stream().allMatch(pos -> Math.abs(pos.getX() - candidate.getMinBlockX()) < 32));

        JsonObject missing = new JsonObject(); missing.addProperty("vss:unrelated", templates.get(id).getAsString());
        var missingSampler = sampler(access, missing);
        var unsupported = new PredictionSurfaceStructures(missingSampler);
        var emptyLevel = new PredictionDecorationLevel(missingSampler, missingSampler, access, candidate.x, candidate.z);
        unsupported.place(emptyLevel, candidate.x, candidate.z, 0);
        assertTrue(emptyLevel.placed().isEmpty(), "missing templates must not create bounding boxes or partial buildings");
    }

    private static ClientTerrainSampler sampler(RegistryAccess access, JsonObject templates) {
        var lookup = VanillaRegistries.createLookup();
        var source = new FixedBiomeSource(access.registryOrThrow(Registries.BIOME).holders().findFirst().orElseThrow());
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator = new NoiseBasedChunkGenerator(source, settings);
        var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), 42);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", 1);
        return new ClientTerrainSampler(42, profile) {
            @Override public ClientColumnSample sample(int x, int z) {
                return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(), 0,
                        0, 0, 0, 0, 0, 0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
            @Override RandomState randomStateContext() { return random; }
            @Override net.minecraft.world.level.biome.BiomeSource biomeSourceContext() { return source; }
            @Override RegistryAccess decorationAccess() { return access; }
            @Override JsonObject structureTemplates() { return templates; }
        };
    }
}
