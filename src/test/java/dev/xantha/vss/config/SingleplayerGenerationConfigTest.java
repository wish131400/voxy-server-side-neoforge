package dev.xantha.vss.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class SingleplayerGenerationConfigTest {
    @Test void defaultsSeparateIntegratedAndDedicatedGeneration() {
        var config=new VSSServerConfig();
        assertEquals(2,config.effectiveGenerationConcurrencyLimitPerPlayer(true));
        assertEquals(4,config.effectiveGenerationConcurrencyLimitPerPlayer(false));
        assertEquals(0,config.chunkyQueueLimit,"explicit pregeneration stays unlimited");
    }
    @Test void oldConfigsKeepTheirDedicatedOverrideAndReceiveTheLocalDefault() {
        var config=new Gson().fromJson("{\"generationConcurrencyLimitPerPlayer\":11}",VSSServerConfig.class);
        assertEquals(2,config.effectiveGenerationConcurrencyLimitPerPlayer(true));
        assertEquals(11,config.effectiveGenerationConcurrencyLimitPerPlayer(false));
        config.singleplayerGenerationConcurrencyLimit=3;
        assertEquals(3,config.effectiveGenerationConcurrencyLimitPerPlayer(true));
        assertEquals(11,config.effectiveGenerationConcurrencyLimitPerPlayer(false));
    }
    @Test void independentValuesAreClampedAndSavedWithAccurateHelp() throws Exception {
        var config=new VSSServerConfig();config.configVersion=VSSServerConfig.CURRENT_CONFIG_VERSION;
        config.singleplayerGenerationConcurrencyLimit=0;config.generationConcurrencyLimitPerPlayer=9;
        var validate=VSSServerConfig.class.getDeclaredMethod("validate");validate.setAccessible(true);validate.invoke(config);
        assertEquals(1,config.singleplayerGenerationConcurrencyLimit);assertEquals(9,config.generationConcurrencyLimitPerPlayer);
        config.singleplayerGenerationConcurrencyLimit=Integer.MAX_VALUE;validate.invoke(config);
        assertEquals(VSSServerConfig.MAX_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER,config.singleplayerGenerationConcurrencyLimit);
        var directory=java.nio.file.Files.createTempDirectory("vss-singleplayer-config-");
        var store=new JsonConfigStore(directory);store.save(config);
        var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                directory.resolve("vss-server-config.json"))).getAsJsonObject();
        assertEquals(config.singleplayerGenerationConcurrencyLimit,json.get("singleplayerGenerationConcurrencyLimit").getAsInt());
        assertTrue(json.getAsJsonObject("_help").get("singleplayerGenerationConcurrencyLimit").getAsString().contains("默认 2"));
        assertTrue(json.getAsJsonObject("_help").get("generationConcurrencyLimitPerPlayer").getAsString().contains("独立服务器"));
        assertEquals(9,new Gson().fromJson(json,VSSServerConfig.class).generationConcurrencyLimitPerPlayer);
    }
}
