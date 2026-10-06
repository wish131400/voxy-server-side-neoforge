package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.client.ClientConnectionIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

/** Immutable connection snapshot: a delayed decoder cannot select the next world's cache. */
record PredictionCacheStorage(Path base, Path legacyBase) {
    private static final String FORMAT = "prediction/surface-v1";
    private static final String WORLD_MARKER = "world.json";
    private static final Object WORLD_LOCK = new Object();

    static PredictionCacheStorage current() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) return null;
            var server = minecraft.getSingleplayerServer();
            String address = minecraft.getCurrentServer() == null ? null : minecraft.getCurrentServer().ip;
            return forWorld(minecraft.gameDirectory.toPath(), server == null ? null : server.getWorldPath(LevelResource.ROOT),
                    ClientConnectionIdentity.currentPredictionScope(), address);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static PredictionCacheStorage forWorld(Path game, Path save, String serverScope, String address) {
        if (game == null) return null;
        Path base;
        String legacyWorld;
        if (save != null) {
            Path world = save.toAbsolutePath().normalize();
            base = world.resolve(".vss").resolve(FORMAT);
            legacyWorld = "local:" + world;
        } else {
            if (serverScope == null && (address == null || address.isBlank())) return null;
            String scope = serverScope == null ? "server:" + address.trim().toLowerCase(java.util.Locale.ROOT) : serverScope;
            base = game.resolve(".vss/servers").resolve(digest(scope.getBytes(StandardCharsets.UTF_8))).resolve(FORMAT);
            legacyWorld = address == null ? null : "server:" + address;
        }
        Path legacy = legacyWorld == null ? null : game.resolve("vss").resolve(FORMAT)
                .resolve(digest(legacyWorld.getBytes(StandardCharsets.UTF_8)));
        return new PredictionCacheStorage(base.toAbsolutePath().normalize(), legacy == null ? null : legacy.toAbsolutePath().normalize());
    }

    Path directory(ClientTerrainSampler sampler) {
        var profile = sampler.profile();
        return base.resolve(profile.dimension().getNamespace()).resolve(profile.dimension().getPath())
                .resolve("seed-" + Long.toUnsignedString(profile.seed(), 16));
    }

    static long fingerprint(dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile profile) {
        String hash = digest((profile.seed() + ":" + profile.dimension()).getBytes(StandardCharsets.UTF_8));
        return Long.parseUnsignedLong(hash.substring(0, 16), 16);
    }

    /** Called by the profile decoder before any manager can read, write or invalidate this directory. */
    PredictionDiskCache open(ClientTerrainSampler sampler) {
        if (!VSSClientConfig.CONFIG.rememberTerrain) return null;
        Path target = directory(sampler);
        try {
            synchronized (WORLD_LOCK) { return openWorld(sampler, target); }
        } catch (IOException | RuntimeException failure) {
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction storage unavailable: " + target + ": " + failure);
            return null;
        }
    }

    private PredictionDiskCache openWorld(ClientTerrainSampler sampler, Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            var source = PredictionCacheMigration.find(base, legacyBase, sampler);
            if (source != null) {
                writeWorld(source.directory(), sampler, source.fingerprint(), true);
                migrate(source.directory(), target);
            } else {
                Files.createDirectories(target.getParent());
                Path staging = Files.createTempDirectory(target.getParent(), ".prediction-world-");
                try {
                    writeWorld(staging, sampler, fingerprint(sampler.profile()), false);
                    migrate(staging, target);
                } finally {
                    Files.deleteIfExists(staging.resolve(WORLD_MARKER));
                    Files.deleteIfExists(staging);
                }
            }
        }
        JsonObject world = readWorld(target, sampler);
        long fingerprint = world.get("fingerprint").getAsLong();
        var mappings = new PredictionCacheMappings(sampler.cacheBiomes(), names(world, "legacy_biomes"), names(world, "legacy_blocks"));
        sampler.bindCacheFingerprint(fingerprint);
        Files.deleteIfExists(target.resolve("worldgen.identity"));
        if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction world cache: seed="
                + sampler.profile().seed() + ", recordFingerprint=" + fingerprint + ", directory=" + target);
        return new PredictionDiskCache(target, fingerprint, mappings);
    }

    private static void writeWorld(Path directory, ClientTerrainSampler sampler, long fingerprint, boolean legacy) throws IOException {
        Path marker = directory.resolve(WORLD_MARKER);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (readWorld(directory, sampler).get("fingerprint").getAsLong() != fingerprint) throw new IOException("Cache record identity changed");
            return;
        }
        var p = sampler.profile();
        var world = new JsonObject();
        world.addProperty("version", 1); world.addProperty("seed", p.seed());
        world.addProperty("dimension", p.dimension().toString()); world.addProperty("fingerprint", fingerprint);
        world.addProperty("min_y", p.minY()); world.addProperty("height", p.height());
        if (legacy) {
            var biomes = new JsonArray(); sampler.cacheBiomes().forEach(biomes::add);
            world.add("legacy_biomes", biomes);
            // Legacy captures never saved their block registry. The current registry cannot prove old numeric IDs.
        }
        Path staging = Files.createTempFile(directory, ".prediction-world-", ".tmp");
        try {
            Files.writeString(staging, world.toString(), StandardCharsets.UTF_8);
            try { Files.move(staging, marker, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(staging, marker); }
        } finally { Files.deleteIfExists(staging); }
    }

    private static JsonObject readWorld(Path directory, ClientTerrainSampler sampler) throws IOException {
        Path marker = directory.resolve(WORLD_MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 1024 * 1024)
            throw new IOException("Missing or invalid cache world metadata");
        try (var reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            var world = JsonParser.parseReader(reader).getAsJsonObject();
            var p = sampler.profile();
            if (world.get("version").getAsInt() != 1 || world.get("seed").getAsLong() != p.seed()
                    || !world.get("dimension").getAsString().equals(p.dimension().toString()))
                throw new IOException("Cache world mismatch");
            world.get("fingerprint").getAsLong();
            return world;
        } catch (RuntimeException invalid) { throw new IOException("Invalid cache world metadata", invalid); }
    }

    private static java.util.List<String> names(JsonObject world, String key) throws IOException {
        if (!world.has(key)) return java.util.List.of();
        var names = world.getAsJsonArray(key);
        if (names.size() > 65536) throw new IOException("Invalid cache registry mapping");
        return names.asList().stream().map(JsonElement::getAsString).toList();
    }

    static synchronized void migrate(Path source, Path target) throws IOException {
        // Never merge legacy entries into an initialized cache: missing files
        // there may have been deliberately removed by dirty-column refresh.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Files.createDirectories(target.getParent());
        if (source == null || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unavailable) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
            // Saves may live on another drive. Publish a complete copy from a
            // sibling staging directory; interruption never publishes half a cache.
            copyMigration(source, target);
        }
    }

    static void copyMigration(Path source, Path target) throws IOException {
        Path staging = Files.createTempDirectory(target.getParent(), ".prediction-migration-");
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("cache migration cancelled");
                    Files.createDirectories(staging.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("cache migration cancelled");
                    if (!attrs.isRegularFile()) throw new IOException("unsupported legacy cache entry: " + file);
                    Files.copy(file, staging.resolve(source.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("cache migration cancelled");
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(staging, target); }
        } finally {
            if (Files.exists(staging)) Files.walkFileTree(staging, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
