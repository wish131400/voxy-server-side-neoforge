package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonObject;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;

class StructurifyPredictionSnapshotTest {
    @Test void absentModDoesNotPublishPolicyOrTouchOptionalClasses() {
        try (var list = mockStatic(ModList.class)) {
            ModList mods = mock(ModList.class);
            list.when(ModList::get).thenReturn(mods);
            when(mods.isLoaded("structurify")).thenReturn(false);
            assertNull(StructurifyPredictionSnapshot.capture());
            verify(mods).isLoaded("structurify");
            verifyNoMoreInteractions(mods);
        }
    }

    @Test void installedPolicyTracksLiveConfigWithoutMutatingIt() throws Exception {
        String owner = FakeOwner.class.getName(), serializer = FakeSerializer.class.getName();
        FakeOwner.config = new JsonObject();
        FakeOwner.config.addProperty("global_spacing_and_separation_modifier", 1.5);
        FakeOwner.config.addProperty("config_datetime", "first");
        var original = FakeOwner.config.deepCopy();
        var first = StructurifyPredictionSnapshot.captureFrom(owner, serializer);
        assertFalse(first.get("prediction_supported").getAsBoolean());
        assertEquals("structurify", first.get("provider").getAsString());
        assertEquals(original, FakeOwner.config);
        FakeOwner.config.addProperty("config_datetime", "later");
        assertEquals(first.get("config_sha256"), StructurifyPredictionSnapshot.captureFrom(owner, serializer).get("config_sha256"));
        FakeOwner.config.addProperty("global_spacing_and_separation_modifier", 2.0);
        assertNotEquals(first.get("config_sha256"), StructurifyPredictionSnapshot.captureFrom(owner, serializer).get("config_sha256"));
    }

    public static final class FakeOwner {
        static JsonObject config;
        public static JsonObject getConfig() { return config; }
    }
    public static final class FakeSerializer {
        public static JsonObject save(JsonObject config, boolean ignored) { return config; }
    }
}
