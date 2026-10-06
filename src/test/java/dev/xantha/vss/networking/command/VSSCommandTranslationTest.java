package dev.xantha.vss.networking.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VSSCommandTranslationTest {
    @Test void allCommandKeysAndPlaceholdersMatchBetweenChineseAndEnglish() throws Exception {
        JsonObject english = read("en_us");
        JsonObject chinese = read("zh_cn");
        Set<String> englishKeys = commandKeys(english);
        assertEquals(englishKeys, commandKeys(chinese));
        for (String key : englishKeys) {
            String en = english.get(key).getAsString();
            String zh = chinese.get(key).getAsString();
            assertFalse(zh.isBlank(), key);
            assertEquals(en.split("%s", -1).length, zh.split("%s", -1).length, key);
            assertTrue(zh.codePoints().anyMatch(code -> code >= 0x4e00 && code <= 0x9fff), key);
        }
        for (String topic : VSSCommandHelp.SERVER_TOPICS) {
            String key = "vss.command.help." + (topic.equals("farplayers") ? "far_players" : topic);
            assertTrue(english.has(key), key);
            assertTrue(english.has(key + ".usage"), key);
            assertTrue(english.has(key + ".example"), key);
        }
        for (String topic : VSSCommandHelp.CLIENT_TOPICS) {
            String key = "vss.command.client_help." + topic;
            assertTrue(english.has(key + ".usage"), key);
            assertTrue(english.has(key + ".example"), key);
        }
    }

    private static Set<String> commandKeys(JsonObject values) {
        return values.keySet().stream().filter(key -> key.startsWith("vss.command."))
                .collect(Collectors.toSet());
    }

    private static JsonObject read(String language) throws Exception {
        try (var input = VSSCommandTranslationTest.class.getResourceAsStream("/assets/vss/lang/" + language + ".json")) {
            assertNotNull(input);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
