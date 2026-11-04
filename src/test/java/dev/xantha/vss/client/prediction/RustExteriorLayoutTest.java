package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Exercises the sampler and real JNI with independent dimension and noise bounds. */
class RustExteriorLayoutTest {
    private static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
    }

    private static JsonObject document(int min, int height) throws Exception {
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new com.google.gson.JsonArray());
        var settings = doc.getAsJsonObject("settings");
        settings.getAsJsonObject("noise").addProperty("min_y", min);
        settings.getAsJsonObject("noise").addProperty("height", height);
        settings.addProperty("aquifers_enabled", false);
        settings.addProperty("ore_veins_enabled", false);
        settings.getAsJsonObject("noise_router").add("final_density", JsonParser.parseString(
                "{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":-64,\"to_y\":128,\"from_value\":1,\"to_value\":-1}"));
        return doc;
    }

    @Test void batchMatchesScalarForDifferentWorldAndNoiseHeights() throws Exception {
        bootstrap();
        // EpicTerrain's 576/512 combination, shifted minima, a generator taller
        // than the dimension, and the ordinary equal-height case.
        for (boolean compact : new boolean[]{false, true})
        for (int[] bounds : new int[][]{{-64,576,-64,512},{-128,512,-64,384},
                {-64,384,-128,512},{-64,384,-64,384}})
        try (var shared = new RustWorldgenDocument.SharedInputs()) {
            var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                    net.minecraft.resources.ResourceLocation.tryParse("minecraft:overworld"),
                    bounds[0], bounds[1], "noise", "minecraft:overworld", 917L);
            var doc = document(bounds[2], bounds[3]);
            if (compact) {
                doc.remove("block_definitions"); doc.remove("input_states");
                doc.addProperty("vss_shared_palette", shared.palette());
            }
            long world = RustWorldgenBackend.create(917, 0, doc.toString());
            var context = new ClientTerrainSampler(917, profile);
            try (var sampler = compact ? new RustTerrainSampler(world, profile, context, shared.canonicalStates())
                    : new RustTerrainSampler(world, profile, context)) {
                for (int step : new int[]{1,2,4}) for (int count : new int[]{2,8}) {
                    int[] xs = new int[count], zs = new int[count], bottoms = new int[count], tops = new int[count];
                    for (int i=0;i<count;i++) {
                        xs[i] = -8 + i; zs[i] = -8;
                        bottoms[i] = -32 + i; tops[i] = i == count-1 ? 100 : 16 + i;
                    }
                    var actual = sampler.exteriorFootprints(xs,zs,step,bottoms,tops,count,()->true);
                    for (int i=0;i<count;i++)
                        assertArrayEquals(sampler.exteriorFootprint(xs[i],zs[i],step,bottoms[i],tops[i],()->true),
                                actual[i], "world="+bounds[1]+" noise="+bounds[3]+" step="+step+" row="+i);
                }
                var column = sampler.exteriorColumn(-8,-8);
                assertEquals(bounds[2], column.minY());
                var interior = sampler.sampleInterior(-8,-8);
                assertNotNull(interior.volume());
                assertEquals(bounds[2], interior.volume().minY());
                sampler.wallEvidence(-8,-8,sampler.sample(-8,-8));
                // Enter the production cliff enrichment path, which requests
                // multiple JNI rows before publishing the tile.
                var surface = sampler.sample(-8, -8);
                var samples = new ClientColumnSample[9];
                java.util.Arrays.fill(samples, surface);
                samples[0] = new ClientColumnSample(surface.surfaceY()-16, surface.fluidY(),
                        surface.biomeIndex(), surface.topBlockIndex(), 0, 0, 0, 0, surface.fluid(),
                        surface.flags(), 0, surface.underBlockIndex(), surface.deepBlockIndex(),
                        surface.surfaceBottom(), surface.lowerTop(), surface.lowerBottom(), surface.spanFloor());
                assertTrue(PredictionExteriorColumns.enrich(samples, 3, 1, -8, -8, sampler, () -> true) > 1,
                        "Cliff enrichment must complete with mismatched native bounds");
                assertTrue((samples[1].flags() & PredictionExteriorColumns.CHECKED) != 0);
                assertThrows(java.util.concurrent.CancellationException.class, () ->
                        sampler.exteriorFootprints(new int[]{-8,-7},new int[]{-8,-8},1,
                                new int[]{-32,-32},new int[]{16,17},2,()->false));
            }
        }
    }

    @Test void compactAndFullDescriptionsAgreeOnNativeBounds() throws Exception {
        bootstrap();
        long world=RustWorldgenBackend.create(917,0,document(-128,512).toString());
        try {
            var full=JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject();
            var compact=JsonParser.parseString(RustWorldgenBackend.describeCompact(world)).getAsJsonObject();
            assertEquals(-128, full.get("min_y").getAsInt());
            assertEquals(512, full.get("height").getAsInt());
            assertEquals(full.get("min_y"),compact.get("min_y"));
            assertEquals(full.get("height"),compact.get("height"));
        } finally { RustWorldgenBackend.close(world); }
    }
}
