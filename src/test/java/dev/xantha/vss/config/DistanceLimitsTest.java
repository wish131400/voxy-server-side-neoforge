package dev.xantha.vss.config;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistanceLimitsTest {
    @TempDir Path directory;

    private VSSClientConfig client(int chunks, int blocks) throws Exception {
        String file = "vss-client-config.json";
        Files.writeString(directory.resolve(file), "{\"configVersion\":\""
                + VSSClientConfig.CURRENT_CONFIG_VERSION + "\",\"lodDistanceChunks\":" + chunks
                + ",\"predictionDistanceBlocks\":" + blocks + "}");
        return new JsonConfigStore(directory).load(VSSClientConfig.class, file);
    }

    private JsonObject saved(String file) throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve(file))).getAsJsonObject();
    }

    @Test void oldClientDistancesAreClampedAndPersistedWithTheNewUnits() throws Exception {
        for (int chunks : new int[] {4096, 8192, Integer.MAX_VALUE}) {
            var config = client(chunks, 65536);
            assertEquals(2048, config.lodDistanceChunks);
            assertEquals(32768, config.predictionDistanceBlocks);
            var json = saved("vss-client-config.json");
            assertEquals(2048, json.get("lodDistanceChunks").getAsInt());
            assertEquals(32768, json.get("predictionDistanceBlocks").getAsInt());
            assertTrue(json.getAsJsonObject("_help").get("lodDistanceChunks").getAsString().contains("0-2048"));
            assertTrue(json.getAsJsonObject("_help").get("predictionDistanceBlocks").getAsString().contains("64-2048"));
        }
    }

    @Test void automaticServerDistanceAndValidClientChoicesSurviveReload() throws Exception {
        for (int chunks : new int[] {0, 1024, 2048}) {
            var config = client(chunks, 32768);
            assertEquals(chunks, config.lodDistanceChunks);
            assertEquals(32768, config.predictionDistanceBlocks);
            var reloaded = new JsonConfigStore(directory).load(VSSClientConfig.class, "vss-client-config.json");
            assertEquals(chunks, reloaded.lodDistanceChunks);
            assertEquals(32768, reloaded.predictionDistanceBlocks);
        }
    }

    @Test void chunkSettingsRoundTripThroughTheExistingSavedRepresentation() throws Exception {
        var config = client(0, 8192);
        assertEquals(512, config.getPredictionDistanceChunks());
        for (int chunks : new int[] {64, 256, 512, 2048}) {
            config.setPredictionDistanceChunks(chunks);
            new JsonConfigStore(directory).save(config);
            assertEquals(chunks * 16, saved("vss-client-config.json").get("predictionDistanceBlocks").getAsInt());
            var reloaded = new JsonConfigStore(directory).load(VSSClientConfig.class, "vss-client-config.json");
            assertEquals(chunks, reloaded.getPredictionDistanceChunks());
        }
    }

    @Test void oldServerDistanceIsClampedBeforeSessionSyncAndSaved() throws Exception {
        String file = "vss-server-config.json";
        for (int chunks : new int[] {4096, 8192, Integer.MAX_VALUE}) {
            Files.writeString(directory.resolve(file), "{\"lodDistanceChunks\":" + chunks + "}");
            var config = new JsonConfigStore(directory).load(VSSServerConfig.class, file);
            assertEquals(2048, config.lodDistanceChunks);
            assertEquals(2048, config.effectiveColumnSyncDistanceChunks());
            assertEquals(2048, saved(file).get("lodDistanceChunks").getAsInt());
        }
    }

    @Test void validServerDistancesKeepTheirSavedValues() throws Exception {
        String file = "vss-server-config.json";
        for (int chunks : new int[] {1, 1024, 2048}) {
            Files.writeString(directory.resolve(file), "{\"lodDistanceChunks\":" + chunks + "}");
            var config = new JsonConfigStore(directory).load(VSSServerConfig.class, file);
            assertEquals(chunks, config.lodDistanceChunks);
            assertEquals(chunks, config.effectiveColumnSyncDistanceChunks());
            assertEquals(chunks, saved(file).get("lodDistanceChunks").getAsInt());
        }
    }

    @Test void exactDecorationRadiusUsesTheSameBlockLimit() throws Exception {
        String file = "vss-client-config.json";
        Files.writeString(directory.resolve(file), "{\"predictionExactDistanceBlocks\":65536}");
        var config = new JsonConfigStore(directory).load(VSSClientConfig.class, file);
        assertEquals(32768, config.predictionExactDistanceBlocks);
        assertEquals(32768, saved(file).get("predictionExactDistanceBlocks").getAsInt());
    }
}
