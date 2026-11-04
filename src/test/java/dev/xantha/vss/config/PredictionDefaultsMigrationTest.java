package dev.xantha.vss.config;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionDefaultsMigrationTest {
    private static final String FILE = "vss-client-config.json";
    @TempDir Path directory;

    private boolean supported() { return true; }

    private VSSClientConfig load(String json) throws Exception {
        Files.writeString(directory.resolve(FILE), json);
        return new JsonConfigStore(directory).load(VSSClientConfig.class, FILE);
    }

    private JsonObject saved() throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve(FILE))).getAsJsonObject();
    }

    @Test void newFileHasAnOuterRegionAndAccurateSavedHelpOnSupportedTargets() throws Exception {
        var config = new JsonConfigStore(directory).load(VSSClientConfig.class, FILE);
        assertEquals(supported(), config.predictionFarDecorations);
        assertEquals(supported() ? 8192 : 4096, config.predictionDistanceBlocks);
        assertEquals(supported() ? 1024 : 4096, config.predictionExactDistanceBlocks);
        var json = saved();
        assertEquals(supported(), json.get("predictionFarDecorations").getAsBoolean());
        assertEquals(config.predictionDistanceBlocks, json.get("predictionDistanceBlocks").getAsInt());
        assertTrue(json.getAsJsonObject("_help").get("predictionFarDecorations").getAsString()
                .contains("默认 " + supported()));
        assertTrue(json.getAsJsonObject("_help").get("predictionDistanceBlocks").getAsString()
                .contains("默认 " + config.getPredictionDistanceChunks()));
        assertTrue(json.getAsJsonObject("_help").get("predictionExactDistanceBlocks").getAsString()
                .contains("默认 " + (config.predictionExactDistanceBlocks / 16) + " 区块"));
    }

    @Test void previousDefaultMigratesWithoutResettingPredictionOrXaeroChoices() throws Exception {
        var config = load("""
                {"configVersion":"v0.2.16","predictionFarDecorations":false,
                 "predictionDistanceBlocks":4096,"predictionExactDistanceBlocks":4096,
                 "enablePrediction":false,"enableXaeroMapBridge":false}
                """);
        assertEquals(supported(), config.predictionFarDecorations);
        assertEquals(supported() ? 8192 : 4096, config.predictionDistanceBlocks);
        assertEquals(supported() ? 1024 : 4096, config.predictionExactDistanceBlocks);
        assertFalse(config.enablePrediction);
        assertFalse(config.enableXaeroMapBridge);
        assertEquals(VSSClientConfig.CURRENT_CONFIG_VERSION, saved().get("configVersion").getAsString());
    }

    @Test void previousExperimentalDefaultOnlyMigratesTheExactRadius() throws Exception {
        var config = load("""
                {"configVersion":"v0.2.17","predictionFarDecorations":false,
                 "predictionDistanceBlocks":4096,"predictionExactDistanceBlocks":4096,
                 "enablePrediction":false,"enableXaeroMapBridge":false}
                """);
        assertEquals(supported() ? 1024 : 4096, config.predictionExactDistanceBlocks);
        assertFalse(config.predictionFarDecorations);
        assertEquals(4096, config.predictionDistanceBlocks);
        assertFalse(config.enablePrediction);
        assertFalse(config.enableXaeroMapBridge);
        // A deliberate return to 256 chunks after this migration must survive reloads.
        config.predictionExactDistanceBlocks = 4096;
        var store = new JsonConfigStore(directory);
        store.save(config);
        for (int i = 0; i < 3; i++) {
            var reloaded = store.load(VSSClientConfig.class, FILE);
            assertEquals(4096, reloaded.predictionExactDistanceBlocks);
            assertFalse(reloaded.predictionFarDecorations);
            assertFalse(reloaded.enableXaeroMapBridge);
            assertEquals(4096, reloaded.predictionDistanceBlocks);
        }
    }

    @Test void previousExperimentalCustomRadiusSurvivesTheUpgrade() throws Exception {
        var config = load("""
                {"configVersion":"v0.2.17","predictionFarDecorations":true,
                 "predictionDistanceBlocks":16384,"predictionExactDistanceBlocks":6144}
                """);
        assertTrue(config.predictionFarDecorations);
        assertEquals(16384, config.predictionDistanceBlocks);
        assertEquals(6144, config.predictionExactDistanceBlocks);
    }

    @Test void customDistancesAndExactRadiusSurviveTheUpgrade() throws Exception {
        for (int distance : new int[] {2048, 16384}) {
            var config = load("{\"configVersion\":\"v0.2.16\",\"predictionFarDecorations\":false,"
                    + "\"predictionDistanceBlocks\":" + distance + ",\"predictionExactDistanceBlocks\":6144}");
            assertEquals(supported(), config.predictionFarDecorations);
            assertEquals(distance, config.predictionDistanceBlocks);
            assertEquals(6144, config.predictionExactDistanceBlocks);
        }
    }

    @Test void switchingOffAfterMigrationSurvivesSaveAndRepeatedReloads() throws Exception {
        var config = load("{\"configVersion\":\"v0.2.16\",\"predictionDistanceBlocks\":4096}");
        config.predictionFarDecorations = false;
        config.predictionDistanceBlocks = 4096;
        var store = new JsonConfigStore(directory);
        store.save(config);
        for (int i = 0; i < 3; i++) {
            var reloaded = store.load(VSSClientConfig.class, FILE);
            assertFalse(reloaded.predictionFarDecorations);
            assertEquals(4096, reloaded.predictionDistanceBlocks);
        }
    }

    @Test void currentSavedChoicesAreRespected() throws Exception {
        var config = load("{\"configVersion\":\"" + VSSClientConfig.CURRENT_CONFIG_VERSION
                + "\",\"predictionFarDecorations\":false,\"predictionDistanceBlocks\":3072}");
        assertFalse(config.predictionFarDecorations);
        assertEquals(3072, config.predictionDistanceBlocks);
    }

    @Test void configWithoutVersionOrNewFieldsReceivesTheTargetDefaults() throws Exception {
        var config = load("{\"predictionDistanceBlocks\":4096}");
        assertEquals(supported(), config.predictionFarDecorations);
        assertEquals(supported() ? 8192 : 4096, config.predictionDistanceBlocks);
        assertEquals(supported() ? 1024 : 4096, config.predictionExactDistanceBlocks);
    }

    @Test void migratedExperimentalChoicesSurviveFormalIntegration() throws Exception {
        var config = load("{\"configVersion\":\"v0.2.18\",\"predictionFarDecorations\":false,"
                + "\"predictionDistanceBlocks\":4096,\"predictionExactDistanceBlocks\":4096,\"enableXaeroMapBridge\":false}");
        assertFalse(config.predictionFarDecorations);
        assertEquals(4096, config.predictionDistanceBlocks);
        assertEquals(4096, config.predictionExactDistanceBlocks);
        assertFalse(config.enableXaeroMapBridge);
        assertEquals(VSSClientConfig.CURRENT_CONFIG_VERSION, saved().get("configVersion").getAsString());
    }
}
