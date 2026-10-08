package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.config.VSSServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistentCacheRetentionTest {
    @TempDir Path root;
    static final long NOW = 2_000_000_000_000L;
    static final long DAY = TimeUnit.DAYS.toMillis(1);
    @BeforeAll static void initialize() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Files.createTempDirectory("vss-retention"));
    }
    Path column(int x, int bytes, long modified) throws Exception {
        Path p = root.resolve("minecraft_overworld/0_0/" + x + "_0.vcl");
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(modified));
        return p;
    }
    @Test void expiresAtSevenDaysEvenWhenCacheIsBelowBothLimits() throws Exception {
        var config = new VSSServerConfig();
        var store = new PersistentColumnLodStore(config);
        Path expired = column(0, 100, NOW - 7 * DAY), fresh = column(1, 100, NOW - 7 * DAY + 1);
        Files.readAllBytes(expired); // Reading cannot extend the lifetime.
        var removed = new ArrayList<Path>();
        store.cleanupRoot(root, NOW, removed::add);
        assertFalse(Files.exists(expired)); assertTrue(Files.exists(fresh));
        assertEquals(java.util.List.of(expired), removed);
    }
    @Test void entryLimitAndByteLimitEvictOldestAndKeepIndexesAndOtherFiles() throws Exception {
        var config = new VSSServerConfig(); config.persistentColumnCacheMaxEntries = 1;
        var store = new PersistentColumnLodStore(config);
        Path old = column(0, 600_000, NOW - DAY), newer = column(1, 600_000, NOW);
        Path index = Files.writeString(newer.resolveSibling("index.vci"), "index");
        Path other = Files.writeString(newer.resolveSibling("notes.txt"), "preserve");
        store.cleanupRoot(root, NOW, ignored -> {});
        assertFalse(Files.exists(old)); assertTrue(Files.exists(newer));
        assertTrue(Files.exists(index)); assertTrue(Files.exists(other));
        config.persistentColumnCacheMaxEntries = 100; config.persistentColumnCacheMaxMiB = 1;
        Path newest = column(2, 600_000, NOW + 1);
        store.cleanupRoot(root, NOW, ignored -> {});
        assertFalse(Files.exists(newer)); assertTrue(Files.exists(newest));
    }
    @Test void rewriteDuringSweepSurvivesAnOldEvictionCandidate() throws Exception {
        var store = new PersistentColumnLodStore(new VSSServerConfig());
        Path first = column(0, 100, NOW - 9 * DAY), rewritten = column(1, 100, NOW - 8 * DAY);
        store.cleanupRoot(root, NOW, removed -> {
            if (removed.equals(first)) try {
                Files.setLastModifiedTime(rewritten, FileTime.fromMillis(NOW));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertFalse(Files.exists(first)); assertTrue(Files.exists(rewritten));
    }
    @Test void expiredReadGuardRejectsOldFilesBeforeMaintenance() throws Exception {
        var store = new PersistentColumnLodStore(new VSSServerConfig());
        var check = PersistentColumnLodStore.class.getDeclaredMethod("expiredFile", Path.class, long.class);
        check.setAccessible(true);
        assertEquals(true, check.invoke(store, column(0, 100, NOW - 7 * DAY), NOW));
        assertEquals(false, check.invoke(store, column(1, 100, NOW - DAY), NOW));
    }
    @Test void upgradesOldDefaultsOnceAndPreservesCustomQuota() throws Exception {
        var validate = VSSServerConfig.class.getDeclaredMethod("validate"); validate.setAccessible(true);
        var old = new VSSServerConfig(); old.configVersion = VSSServerConfig.CURRENT_CONFIG_VERSION;
        old.persistentColumnCacheMaxMiB = 1024; old.persistentColumnCacheMaxEntries = 250000;
        validate.invoke(old);
        assertEquals(10240, old.persistentColumnCacheMaxMiB); assertEquals(2500000, old.persistentColumnCacheMaxEntries);
        old.persistentColumnCacheMaxMiB = 1024; validate.invoke(old);
        assertEquals(1024, old.persistentColumnCacheMaxMiB);
        var custom = new VSSServerConfig(); custom.configVersion = VSSServerConfig.CURRENT_CONFIG_VERSION;
        custom.persistentColumnCacheMaxMiB = 8192; custom.persistentColumnCacheMaxEntries = 900000;
        custom.persistentColumnCacheRetentionDays = 100; validate.invoke(custom);
        assertEquals(8192, custom.persistentColumnCacheMaxMiB); assertEquals(900000, custom.persistentColumnCacheMaxEntries);
        assertEquals(7, custom.persistentColumnCacheRetentionDays);
    }
    @Test void quotaCommandPersistsExplicitValuesWithoutRemigration() throws Exception {
        class RecordingConfig extends VSSServerConfig {
            int saves;
            @Override public void save() { saves++; }
        }
        var config = new RecordingConfig();
        config.configVersion = VSSServerConfig.CURRENT_CONFIG_VERSION;
        config.setPersistentCacheGiB(20);
        assertEquals(20480, config.persistentColumnCacheMaxMiB);
        assertEquals(5000000, config.persistentColumnCacheMaxEntries);
        assertEquals(1, config.saves);
        config.setPersistentCacheGiB(1);
        var validate = VSSServerConfig.class.getDeclaredMethod("validate");
        validate.setAccessible(true); validate.invoke(config);
        assertEquals(1024, config.persistentColumnCacheMaxMiB);
        assertEquals(2, config.saves);
        assertThrows(IllegalArgumentException.class, () -> config.setPersistentCacheGiB(0));
        assertThrows(IllegalArgumentException.class, () -> config.setPersistentCacheGiB(65));
        assertEquals(2, config.saves);
    }
}
