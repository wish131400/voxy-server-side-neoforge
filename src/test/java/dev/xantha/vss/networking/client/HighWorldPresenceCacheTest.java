package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.HighWorldColumnFixtures;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HighWorldPresenceCacheTest {
    @TempDir Path directory;

    @Test void legacyByteAndNewShortManifestsBothLoadWithoutCountTruncation() throws Exception {
        for (int version : new int[] {4, 5}) {
            for (int count : version == 4 ? new int[] {24, 64} : new int[] {65, 80, 256}) {
                Path path = directory.resolve("presence-" + version + "-" + count);
                var manifest = ClientLodPresenceCache.encodeSectionManifest(HighWorldColumnFixtures.heights(count));
                try (var out = new DataOutputStream(Files.newOutputStream(path))) {
                    out.writeInt(0x56535043); out.writeInt(version); out.writeInt(1); out.writeUTF("test");
                    out.writeInt(1); out.writeUTF("minecraft:overworld"); out.writeInt(1); out.writeLong(0);
                    out.writeInt(1); out.writeShort(0); out.writeLong(123L);
                    if (version == 4) out.writeByte(count); else out.writeShort(count);
                    out.write(manifest);
                }
                var scopes = field(ClientLodPresenceCache.class, "scopes");
                ((Map<?, ?>) scopes).clear();
                var read = ClientLodPresenceCache.class.getDeclaredMethod("readCache", Path.class);
                read.setAccessible(true); read.invoke(null, path);
                var scope = ((Map<?, ?>) scopes).get("test"); assertNotNull(scope);
                var dimension = ((Map<?, ?>) field(scope.getClass(), "dimensions", scope)).get("minecraft:overworld");
                var region = ((Map<?, ?>) field(dimension.getClass(), "regions", dimension)).get(0L);
                assertArrayEquals(manifest, ((byte[][]) field(region.getClass(), "sectionManifests", region))[0]);
                ((Map<?, ?>) scopes).clear();
            }
        }
    }

    @Test void currentWriterPersists256RatherThanZero() throws Exception {
        var manifest = ClientLodPresenceCache.encodeSectionManifest(HighWorldColumnFixtures.heights(256));
        long[] times = {123L}; byte[][] manifests = {manifest};
        Object region = record("RegionSnapshot", 1, times, manifests);
        Object dimension = record("DimensionSnapshot", Map.of(0L, region));
        Object scope = record("ScopeSnapshot", Map.of("minecraft:overworld", dimension));
        Path path = directory.resolve("written");
        Object snapshot = record("CacheSnapshot", path, Map.of("test", scope));
        var write = ClientLodPresenceCache.class.getDeclaredMethod("writeSnapshot", snapshot.getClass());
        write.setAccessible(true); assertEquals(true, write.invoke(null, snapshot));
        try (var in = new java.io.DataInputStream(Files.newInputStream(path))) {
            assertEquals(0x56535043, in.readInt()); assertEquals(5, in.readInt()); assertEquals(1, in.readInt());
            in.readUTF(); in.readInt(); in.readUTF(); in.readInt(); in.readLong(); in.readInt(); in.readUnsignedShort(); in.readLong();
            assertEquals(256, in.readUnsignedShort()); assertArrayEquals(manifest, in.readNBytes(256));
            assertEquals(-1, in.read());
        }
    }

    private static Object record(String name, Object... arguments) throws Exception {
        Class<?> type = Class.forName(ClientLodPresenceCache.class.getName() + "$" + name);
        Constructor<?> constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }
    private static Object field(Class<?> type, String name, Object... instance) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(instance.length == 0 ? null : instance[0]);
    }
}
