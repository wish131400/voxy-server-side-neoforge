package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.client.ClientConnectionIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

/** Immutable connection snapshot: a delayed decoder cannot select the next world's cache. */
record PredictionCacheStorage(Path base, Path legacyBase) {
    private static final String FORMAT = "prediction/surface-v1";

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

    Path directory(ClientTerrainSampler sampler) { return dimensionDirectory(base, sampler); }
    Path legacyDirectory(ClientTerrainSampler sampler) { return legacyBase == null ? null : dimensionDirectory(legacyBase, sampler); }

    private static Path dimensionDirectory(Path base, ClientTerrainSampler sampler) {
        var profile = sampler.profile();
        String input = profile.seed() + ":" + profile.fingerprint() + ":" + profile.minY() + ":" + profile.height()
                + ":" + profile.dimension() + ":" + profile.generatorType() + ":" + profile.generatorSettings()
                + ":" + sampler.getClass().getName() + ":" + VSSClientConfig.CONFIG.predictionSupersample
                + (sampler instanceof RustTerrainSampler ? ":" + RustTerrainSampler.ALGORITHM : "")
                // Old FTF Java full grids could contain unmarked point estimates
                // selected by spacing. They cannot be trusted as exact-stage data.
                + (sampler instanceof FreeTerraForgedTerrainSampler ? ":java-stage-exact-r2" : "");
        return base.resolve(profile.dimension().getNamespace()).resolve(profile.dimension().getPath())
                .resolve(digest(input.getBytes(StandardCharsets.UTF_8)) + "-" + digest(profile.generatorData()));
    }

    /** Called by the profile decoder before any manager can read, write or invalidate this directory. */
    PredictionDiskCache open(ClientTerrainSampler sampler) {
        if (!VSSClientConfig.CONFIG.rememberTerrain) return null;
        Path target = directory(sampler);
        try {
            migrate(legacyDirectory(sampler), target);
            return new PredictionDiskCache(target, sampler.profile().fingerprint());
        } catch (IOException | RuntimeException failure) {
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction storage unavailable: " + target + ": " + failure);
            return null;
        }
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

    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
