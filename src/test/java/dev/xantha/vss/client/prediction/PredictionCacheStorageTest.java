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

    @AfterEach void awaitCacheClose() throws Exception {
        var field = PredictionDiskCache.class.getDeclaredField("COMMITS");
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(null)).submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test void worldCacheMigratesFineTerrainAndFinishedMeshThenRestoresMappedCapturesAcrossReconnect() throws Exception {
        var storage = PredictionCacheStorage.forWorld(game, game.resolve("saves/local"), null, null);
        var original = sampler();
        Path oldRoot = PredictionCacheMigration.wireDirectory(storage.base(), original);
        var surface = PredictionDiskCache.Key.surface(0, 0, 1);
        var terrain = PredictionDiskCache.Key.terrain(0, 0, 0);
        var states = Map.of(new BlockPos(1, 72, 2), Blocks.SPRUCE_LEAVES.defaultBlockState());
        var mesh = PredictionMeshCodecTest.fixture();
        var samples = new ClientColumnSample[18 * 18];
        java.util.Arrays.fill(samples, original.sampleSurface(1, 2));
        byte[] full = new byte[32], base = new byte[32]; full[0] = 7;
        try (var cache = new PredictionDiskCache(oldRoot, PROFILE.fingerprint())) {
            try (var lease = cache.lease(surface)) { assertTrue(cache.writeSurface(lease, states)); }
            try (var lease = cache.lease(terrain)) {
                assertTrue(cache.writeTerrain(lease, samples));
                cache.writeMeshLater(lease, full, mesh, base, true);
                cache.flushMeshes(); cache.flush();
            }
        }
        awaitCacheClose();
        try (var captures = new PredictionSampleStore(oldRoot.resolve("captures.smp"), PROFILE.fingerprint())) {
            captures.put(4, samples[0]);
        }
        var first = sampler(PROFILE);
        Path contentRoot = storage.directory(first);
        assertNotEquals(oldRoot, contentRoot);
        try (var cache = storage.open(first); var lease = cache.lease(terrain)) {
            assertNotNull(cache);
            assertArrayEquals(samples, cache.readTerrain(lease, samples.length));
            var restored = cache.readMeshBase(lease, base, mesh.cellAxis());
            assertNotNull(restored);
            assertArrayEquals(mesh.gpuPayload().quads(), restored.gpuPayload().quads());
            assertEquals(PROFILE.fingerprint(), first.cacheFingerprint());
            try (var captures = new PredictionSampleStore(cache.root().resolve("captures.smp"), first.cacheFingerprint(), cache.mappings())) {
                assertNull(captures.get(4), "legacy captures without block names must be recaptured, never remapped by guessing");
                captures.put(4, samples[0]);
            }
        }
        assertFalse(Files.exists(oldRoot));
        assertTrue(Files.isRegularFile(contentRoot.resolve("world.json")));
        var changedWireProfile = new DimensionProfile(PROFILE.dimension(), PROFILE.seed(), PROFILE.minY(),
                PROFILE.height(), PROFILE.generatorType(), PROFILE.generatorSettings(), 999L);
        var second = sampler(changedWireProfile);
        assertEquals(contentRoot, storage.directory(second));
        try (var cache = storage.open(second)) {
            assertEquals(PROFILE.fingerprint(), second.cacheFingerprint());
            assertEquals(999L, second.profile().fingerprint(), "network session fingerprint stays intact");
            try (var lease = cache.lease(surface)) { assertEquals(states, cache.readSurface(lease)); }
            try (var lease = cache.lease(terrain)) { assertNotNull(cache.readMeshBase(lease, base, mesh.cellAxis())); }
            try (var captures = new PredictionSampleStore(cache.root().resolve("captures.smp"), second.cacheFingerprint(), cache.mappings())) {
                assertEquals(samples[0], captures.get(4));
            }
            cache.invalidateChunk(0, 0); cache.flush();
        }
        // A retained old copy must not refill dirty entries in the initialized content cache.
        try (var old = new PredictionDiskCache(oldRoot, PROFILE.fingerprint()); var lease = old.lease(surface)) {
            assertTrue(old.writeSurface(lease, states));
        }
        try (var cache = storage.open(sampler(changedWireProfile)); var lease = cache.lease(surface)) {
            assertNull(cache.readSurface(lease));
        }
    }

    @Test void domainToIpReconnectRestoresFineMeshDespiteChangedTransportFingerprint() throws Exception {
        var domain = PredictionCacheStorage.forWorld(game, null, "vss:7k4m9pxa", "play.example.com:25565");
        var direct = PredictionCacheStorage.forWorld(game, null, "vss:7k4m9pxa", "192.0.2.25:25565");
        var first = sampler(PROFILE);
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        var mesh = PredictionMeshCodecTest.fixture();
        byte[] full = new byte[32], base = new byte[32]; full[0] = 7;
        try (var cache = domain.open(first); var lease = cache.lease(key)) {
            cache.writeMeshLater(lease, full, mesh, base, true);
            cache.flushMeshes(); cache.flush();
        }
        awaitCacheClose();
        var changedWire = new DimensionProfile(PROFILE.dimension(), PROFILE.seed(), PROFILE.minY(),
                PROFILE.height(), PROFILE.generatorType(), PROFILE.generatorSettings(), 999L);
        var second = sampler(changedWire);
        assertEquals(domain.directory(first), direct.directory(second));
        try (var cache = direct.open(second); var lease = cache.lease(key)) {
            var restored = cache.readMeshBase(lease, base, mesh.cellAxis());
            assertNotNull(restored, "a different connection address must restore the finished fine mesh");
            assertArrayEquals(mesh.gpuPayload().quads(), restored.gpuPayload().quads());
        }
        var differentServer = PredictionCacheStorage.forWorld(game, null, "vss:8k4m9pxa", "192.0.2.25:25565");
        try (var cache = differentServer.open(sampler(changedWire)); var lease = cache.lease(key)) {
            assertNull(cache.readMeshBase(lease, base, mesh.cellAxis()));
        }
    }

    @Test void exactLegacyContentMigratesOnceAndUnidentifiedDirectoriesRemainUntouched() throws Exception {
        var storage = PredictionCacheStorage.forWorld(game, game.resolve("saves/content"), null, null);
        var first = sampler();
        String hash = "a".repeat(64);
        Path content = PredictionCacheMigration.contentDirectory(storage.base(), first, hash);
        Path unrelated = PredictionCacheMigration.contentDirectory(storage.base(), first, "b".repeat(64));
        var key = PredictionDiskCache.Key.surface(0, 0, 1);
        var states = Map.of(new BlockPos(1, 72, 2), Blocks.SPRUCE_LEAVES.defaultBlockState());
        try (var cache = new PredictionDiskCache(content, -17); var lease = cache.lease(key)) {
            assertTrue(cache.writeSurface(lease, states)); cache.flush();
        }
        awaitCacheClose();
        Files.writeString(content.resolve("worldgen.identity"), "1\n" + hash + "\n-17\n");
        Files.createDirectories(unrelated);
        Files.writeString(unrelated.resolve("do-not-adopt"), "unknown world snapshot");
        var hashes = new java.util.concurrent.atomic.AtomicInteger();
        first.legacyCacheIdentity(() -> { hashes.incrementAndGet(); return hash; });
        try (var cache = storage.open(first); var lease = cache.lease(key)) {
            assertEquals(states, cache.readSurface(lease));
            assertEquals(-17, first.cacheFingerprint());
        }
        assertEquals(1, hashes.get());
        assertFalse(Files.exists(content)); assertTrue(Files.isRegularFile(unrelated.resolve("do-not-adopt")));
        assertFalse(Files.exists(storage.directory(first).resolve("worldgen.identity")));
        first.legacyCacheIdentity(() -> { fail("Migration must not scan or hash again"); return ""; });
        try (var cache = storage.open(first); var lease = cache.lease(key)) { assertEquals(states, cache.readSurface(lease)); }
    }

    @Test void unmatchedLegacyContentDoesNotGetAdoptedAndCorruptMatchingMarkerFailsClosed() throws Exception {
        var storage = PredictionCacheStorage.forWorld(game, game.resolve("saves/unknown"), null, null);
        var source = sampler();
        String hash = "c".repeat(64);
        Path unrelated = PredictionCacheMigration.contentDirectory(storage.base(), source, "d".repeat(64));
        Files.createDirectories(unrelated);
        source.legacyCacheIdentity(() -> hash);
        try (var cache = storage.open(source)) { assertNotNull(cache); }
        assertTrue(Files.isDirectory(unrelated));
        var other = PredictionCacheStorage.forWorld(game, game.resolve("saves/broken"), null, null);
        Path matching = PredictionCacheMigration.contentDirectory(other.base(), source, hash);
        Files.createDirectories(matching);
        Files.writeString(matching.resolve("worldgen.identity"), "1\n" + "e".repeat(64) + "\n12\n");
        assertNull(other.open(source));
        assertFalse(Files.exists(other.directory(source)));
    }

    @Test void changedWorldgenAndSamplerSettingsReuseWorldCacheAndBadMetadataFailsClosed() throws Exception {
        var storage = PredictionCacheStorage.forWorld(game, null, "vss:test", "test:25565");
        var first = sampler();
        var changed = sampler(new DimensionProfile(PROFILE.dimension(), PROFILE.seed(), PROFILE.minY(),
                PROFILE.height(), "different:generator", "different:settings", 999L));
        changed.legacyCacheIdentity(() -> { fail("Initialized caches must never hash the worldgen profile"); return ""; });
        assertEquals(storage.directory(first), storage.directory(changed));
        boolean supersample = VSSClientConfig.CONFIG.predictionSupersample;
        try {
            Path normal = storage.directory(first);
            VSSClientConfig.CONFIG.predictionSupersample = !supersample;
            assertEquals(normal, storage.directory(first));
        } finally { VSSClientConfig.CONFIG.predictionSupersample = supersample; }
        try (var cache = storage.open(first)) { assertNotNull(cache); }
        try (var cache = storage.open(changed)) { assertNotNull(cache); }
        Path marker = storage.directory(first).resolve("world.json");
        Files.writeString(marker, "{\"version\":1,\"seed\":43,\"dimension\":\"minecraft:overworld\",\"fingerprint\":123}");
        assertNull(storage.open(sampler()), "a mismatched world marker must fail closed");
    }

    @Test void nativeAlgorithmChangesPreserveWorldSnapshots() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var original = LithostitchedNativeTest.document();
        original.add("possible_biomes", new com.google.gson.JsonArray());
        var changed = original.deepCopy();
        changed.getAsJsonObject("settings").getAsJsonObject("noise_router").add("final_density",
                com.google.gson.JsonParser.parseString("{\"type\":\"lithostitched:sqrt\",\"argument\":-3.3}"));
        var storage = PredictionCacheStorage.forWorld(game, game.resolve("saves/test"), null, null);
        try (var plain = new RustTerrainSampler(RustWorldgenBackend.create(0,0,original.toString()),PROFILE,sampler());
             var sqrt = new RustTerrainSampler(RustWorldgenBackend.create(0,0,changed.toString()),PROFILE,sampler())) {
            assertFalse(plain.signedSqrt());
            assertTrue(sqrt.signedSqrt());
            assertEquals(storage.directory(plain), storage.directory(sqrt));
            var key = PredictionDiskCache.Key.surface(0,0,1);
            var states = Map.of(new BlockPos(1,72,2),Blocks.DIRT.defaultBlockState());
            try (var cache = storage.open(plain); var lease = cache.lease(key)) {
                assertTrue(cache.writeSurface(lease,states));
            }
            try (var cache = storage.open(sqrt); var lease = cache.lease(key)) {
                assertEquals(states,cache.readSurface(lease),"previously cached areas remain world snapshots");
            }
            try (var cache = storage.open(plain); var lease = cache.lease(key)) {
                assertEquals(states,cache.readSurface(lease),"unchanged vanilla caches remain readable");
            }
        }
    }

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

    @Test void serverCachesUseDotVssAndStableIdentityButSeparateServers() {
        var sampler = sampler();
        var first = PredictionCacheStorage.forWorld(game, null, "vss:7k4m9pxa", "old.example:25565");
        var moved = PredictionCacheStorage.forWorld(game, null, "vss:7k4m9pxa", "new.example:25566");
        assertTrue(first.directory(sampler).startsWith(game.resolve(".vss/servers")));
        assertEquals(first.directory(sampler), moved.directory(sampler));
        assertNotEquals(first.legacyBase(), moved.legacyBase());
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
        Path legacy = PredictionCacheMigration.wireDirectory(storage.legacyBase(), sampler);
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

    private static ClientTerrainSampler sampler() { return sampler(PROFILE); }
    private static ClientTerrainSampler sampler(DimensionProfile profile) { return ClientTerrainSampler.custom(profile.seed(), profile, (x, z) -> 64); }
}
