package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.xantha.vss.config.JsonConfig;
import dev.xantha.vss.config.VSSServerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChunkyQueueLimitConfigTest {
    @BeforeAll
    static void initializeConfigDirectory() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "generation-tests"));
    }

    @Test
    void missingSettingKeepsOldAndNewConfigsUnlimited() {
        assertEquals(0, new VSSServerConfig().chunkyQueueLimit);
        var old = new Gson().fromJson("{\"enableChunkGeneration\":true}", VSSServerConfig.class);
        assertEquals(0, old.chunkyQueueLimit);
    }

    @Test
    void queueLimitIsValidatedAndSavedWithHelp() throws Exception {
        var config = new Gson().fromJson("{\"chunkyQueueLimit\":32}", VSSServerConfig.class);
        var validate = VSSServerConfig.class.getDeclaredMethod("validate");
        validate.setAccessible(true);
        validate.invoke(config);
        assertEquals(32, config.chunkyQueueLimit);
        var storeClass = Class.forName("dev.xantha.vss.config.JsonConfigStore");
        var constructor = storeClass.getDeclaredConstructor(java.nio.file.Path.class);
        constructor.setAccessible(true);
        var store = constructor.newInstance(java.nio.file.Path.of("build", "tmp", "generation-tests"));
        var serialize = storeClass.getDeclaredMethod("configWithHelp", JsonConfig.class);
        serialize.setAccessible(true);
        var saved = (JsonObject) serialize.invoke(store, config);
        assertEquals(32, saved.get("chunkyQueueLimit").getAsInt());
        assertTrue(saved.getAsJsonObject("_help").get("chunkyQueueLimit").getAsString().contains("默认 0"));
        assertEquals(32, new Gson().fromJson(saved, VSSServerConfig.class).chunkyQueueLimit);
        config.chunkyQueueLimit = -1;
        validate.invoke(config);
        assertEquals(0, config.chunkyQueueLimit);
        config.chunkyQueueLimit = Integer.MAX_VALUE;
        validate.invoke(config);
        assertEquals(Integer.MAX_VALUE, config.chunkyQueueLimit);
    }
}
