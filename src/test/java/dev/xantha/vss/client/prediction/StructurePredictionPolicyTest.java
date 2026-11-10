package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StructurePredictionPolicyTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static JsonObject root() {
        JsonObject root = new JsonObject();
        root.add("noises", new JsonObject());
        root.add("density_functions", new JsonObject());
        return root;
    }

    @Test void missingPolicyLeavesExistingPredictionEnabled() {
        assertNull(ClientWorldgenRegistries.decode(root(), RegistryAccess.EMPTY, java.util.List.of())
                .structurePredictionRejection());
    }

    @Test void serverPolicyRejectsOnlyUnverifiedStarts() {
        JsonObject root = root();
        JsonObject rule = new JsonObject();
        rule.addProperty("provider", "structurify");
        rule.addProperty("prediction_supported", false);
        rule.addProperty("reason", "runtime overlap claims cannot be replayed");
        root.add("vss_structure_policy", rule);
        assertEquals("runtime overlap claims cannot be replayed",
                ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, java.util.List.of())
                        .structurePredictionRejection());
    }
}
