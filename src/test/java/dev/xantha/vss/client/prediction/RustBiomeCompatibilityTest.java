package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;

/** Run in a separate test JVM with either the current or the previous ABI 6 DLL. */
class RustBiomeCompatibilityTest {
    @BeforeAll static void bootstrap() { RustBiomeIdentityTest.bootstrap(); }
    @Test void currentAndLegacyLibrariesPublishTheSamePersistedIdentity() throws Exception {
        long world=RustWorldgenBackend.create(1,13,RustBiomeIdentityTest.fixedDocument().toString());
        boolean nativeIdentity=JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject().has("biome_names");
        if (Boolean.getBoolean("vss.expectLegacyBiome")) assertFalse(nativeIdentity,"fixture must use the previous DLL");
        var context=new RustBiomeIdentityTest.Context(!nativeIdentity);
        try (var sampler=new RustTerrainSampler(world,RustBiomeIdentityTest.PROFILE,context)) {
            var samples=RustBiomeIdentityTest.samples(3,17);
            assertEquals(16,sampler.resolveBiomeIndices(samples,4,-8,-8,1));
            for (var sample:samples) assertEquals(0,sample.biomeIndex());
            assertEquals(nativeIdentity?0:4,context.queries);
            assertEquals(0,sampler.resolveBiomeIndices(samples,4,-8,-8,1));
            assertEquals(nativeIdentity?0:4,context.queries);
            if (!nativeIdentity) assertTrue(sampler.diagnostics().contains("nativeBatches=0,nativePoints=0,javaFallbacks=4"));
        }
    }
}
