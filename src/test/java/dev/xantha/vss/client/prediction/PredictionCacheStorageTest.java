package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.*;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionCacheStorageTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    @TempDir Path game;
    private boolean remember;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void enable() { remember = VSSClientConfig.CONFIG.rememberTerrain; VSSClientConfig.CONFIG.rememberTerrain = true; }
    @AfterEach void restore() { VSSClientConfig.CONFIG.rememberTerrain = remember; }

    @Test void localCacheFollowsRenamedSaveAndIgnoresServerIdentity() throws Exception {
        Path save = game.resolve("saves/世界 A");
        var storage = PredictionCacheStorage.forWorld(game, save, "vss:7k4m9pxa", "localhost:12345");
        var sampler = sampler();
        assertTrue(storage.directory(sampler).startsWith(save.resolve(".vss/prediction/surface-v1")));
        var key = PredictionDiskCache.Key.surface(0, 0, 1);
        var states = Map.of(new BlockPos(1, 72, 2), Blocks.BIRCH_LEAVES.defaultBlockState());
        try (var cache = storage.open(sampler); var lease = cache.lease(key)) { assertTrue(cache.writeSurface(lease, states)); }
        Path moved = game.resolve("saves/世界 B");
        Files.move(save, moved);
        var renamed = PredictionCacheStorage.forWorld(game.resolve("another-game"), moved, null, null);
        assertEquals(save.relativize(storage.directory(sampler)), moved.relativize(renamed.directory(sampler)),
                "save folder name and installation path must not be in the local cache identity");
        try (var cache = renamed.open(sampler); var lease = cache.lease(key)) {
            assertEquals(states, cache.readSurface(lease));
        }
    }

    @Test void serverCachesUseDotVssAndStableIdentityButSeparateNodesAndWorldgen() {
        var sampler = sampler();
        var first = PredictionCacheStorage.forWorld(game, null, "vss:7k4m9pxa", "old.example:25565");
        var moved = PredictionCacheStorage.forWorld(game, null, "vss:7k4m9pxa", "new.example:25566");
        assertTrue(first.directory(sampler).startsWith(game.resolve(".vss/servers")));
        assertEquals(first.directory(sampler), moved.directory(sampler));
        assertNotEquals(first.legacyDirectory(sampler), moved.legacyDirectory(sampler));
        assertNotEquals(first.directory(sampler), PredictionCacheStorage.forWorld(game, null, "vss:7k4m9pxa-node-b", "old.example:25565").directory(sampler));
        assertNotEquals(first.directory(sampler), PredictionCacheStorage.forWorld(game, null, "vss:8k4m9pxa", "old.example:25565").directory(sampler));
        assertEquals(PredictionCacheStorage.forWorld(game, null, null, "Example.COM:25565").base(),
                PredictionCacheStorage.forWorld(game, null, null, "example.com:25565").base());
        assertNotEquals(PredictionCacheStorage.forWorld(game, null, null, "example.com:25565").base(),
                PredictionCacheStorage.forWorld(game, null, null, "example.com:25566").base());
        assertNull(PredictionCacheStorage.forWorld(game, null, null, null));
        assertNull(PredictionCacheStorage.forWorld(game, null, null, " "));
    }

    @Test void legacySurfaceAndCapturesMigrateTogetherAndDirtyDoesNotRestoreOldData() throws Exception {
        var sampler = sampler();
        var storage = PredictionCacheStorage.forWorld(game, game.resolve("saves/local"), null, null);
        Path legacy = storage.legacyDirectory(sampler);
        var key = PredictionDiskCache.Key.surface(0, 0, 1);
        var states = Map.of(new BlockPos(1, 72, 2), Blocks.SPRUCE_LEAVES.defaultBlockState());
        try (var old = new PredictionDiskCache(legacy, PROFILE.fingerprint()); var lease = old.lease(key)) {
            assertTrue(old.writeSurface(lease, states));
        }
        Files.writeString(legacy.resolve("captures.smp"), "capture fixture");
        try (var cache = storage.open(sampler); var lease = cache.lease(key)) {
            assertEquals(states, cache.readSurface(lease));
            assertEquals("capture fixture", Files.readString(cache.root().resolve("captures.smp")));
            cache.invalidateChunk(0, 0);
            cache.flush();
        }
        // Simulate a retained cross-drive copy or an old installation writing
        // to the original location. It must not refill a deliberately missing entry.
        try (var old = new PredictionDiskCache(legacy, PROFILE.fingerprint()); var lease = old.lease(key)) {
            assertTrue(old.writeSurface(lease, states));
        }
        try (var cache = storage.open(sampler); var lease = cache.lease(key)) {
            assertNull(cache.readSurface(lease));
        }
    }

    @Test void stagedCopyPublishesCompleteTreeWithoutRemovingSource() throws Exception {
        Path source = game.resolve("old"), target = game.resolve("new");
        Files.createDirectories(source.resolve("1-1/0_0"));
        Files.writeString(source.resolve("1-1/0_0/0_0.vpd"), "surface fixture");
        Files.writeString(source.resolve("captures.smp"), "capture fixture");
        PredictionCacheStorage.copyMigration(source, target);
        assertEquals("surface fixture", Files.readString(target.resolve("1-1/0_0/0_0.vpd")));
        assertEquals("capture fixture", Files.readString(target.resolve("captures.smp")));
        assertTrue(Files.isRegularFile(source.resolve("captures.smp")));
        try (var entries = Files.list(game)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".prediction-migration-")));
        }
    }

    @Test void interruptedCopyLeavesNoPublishedOrPartialCache() throws Exception {
        Path source = game.resolve("old"), target = game.resolve("new");
        Files.createDirectories(source);
        Files.writeString(source.resolve("captures.smp"), "original");
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> PredictionCacheStorage.copyMigration(source, target));
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(target));
        assertEquals("original", Files.readString(source.resolve("captures.smp")));
        try (var entries = Files.list(game)) { assertEquals(1, entries.count()); }
    }

    @Test void disabledPersistenceDoesNotCreateOrMigrateDirectories() {
        VSSClientConfig.CONFIG.rememberTerrain = false;
        var storage = PredictionCacheStorage.forWorld(game, game.resolve("saves/local"), null, null);
        assertNull(storage.open(sampler()));
        assertFalse(Files.exists(game.resolve("saves")));
    }

    private static ClientTerrainSampler sampler() { return ClientTerrainSampler.custom(42L, PROFILE, (x, z) -> 64); }
}
