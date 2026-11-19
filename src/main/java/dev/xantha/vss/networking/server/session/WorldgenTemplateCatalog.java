package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonObject;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import dev.xantha.vss.common.worldgen.WorldgenTemplateResources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** One resource lifetime, shared by all dimensions and players. Stores identities, not all NBT bytes. */
public final class WorldgenTemplateCatalog {
    private static ResourceManager owner;
    private static Snapshot current;

    private WorldgenTemplateCatalog() { }

    public record Entry(StructureTemplateDigest digest, Resource resource) {
        public byte[] read() throws IOException {
            try (var input = resource.open()) { return digest.read(input); }
        }
    }
    public record Snapshot(String identity, JsonObject manifest, Map<ResourceLocation, Entry> entries) { }

    public static synchronized Snapshot snapshot(ResourceManager resources) {
        if (owner == resources && current != null) return current;
        var entries = new LinkedHashMap<ResourceLocation, Entry>();
        var manifest = new JsonObject();
        byte[] scratch = new byte[32 * 1024];
        var catalogHash = StructureTemplateDigest.digest();
        for (var resource : resources.listResources("structure", id -> id.getPath().endsWith(".nbt"))
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String path = resource.getKey().getPath();
            ResourceLocation id = resource.getKey().withPath(path.substring("structure/".length(), path.length() - 4));
            try (var input = resource.getValue().open()) {
                var digest = StructureTemplateDigest.inspect(input, scratch);
                entries.put(id, new Entry(digest, resource.getValue()));
                var descriptor = new JsonObject();
                descriptor.addProperty("sha256", digest.sha256());
                descriptor.addProperty("bytes", digest.bytes());
                manifest.add(id.toString(), descriptor);
                catalogHash.update((id + "\n" + digest.sha256() + "\n" + digest.bytes() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException failure) {
                VSSLogger.debug("VSS template identity unavailable: " + id + ": " + failure);
            }
        }
        // FTF's original template manager consumes full resource paths, including its plural directory.
        for (var resource : resources.listResources("structures", id -> WorldgenTemplateResources.freeTerraForged(id)
                && id.getPath().endsWith(".nbt")).entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            ResourceLocation id = WorldgenTemplateResources.key(resource.getKey());
            try (var input = resource.getValue().open()) {
                var digest = StructureTemplateDigest.inspect(input, scratch);
                entries.put(id, new Entry(digest, resource.getValue()));
                var descriptor = new JsonObject();
                descriptor.addProperty("sha256", digest.sha256());
                descriptor.addProperty("bytes", digest.bytes());
                manifest.add(id.toString(), descriptor);
                catalogHash.update((id + "\n" + digest.sha256() + "\n" + digest.bytes() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException failure) {
                VSSLogger.debug("VSS feature template identity unavailable: " + id + ": " + failure);
            }
        }
        String identity = java.util.HexFormat.of().formatHex(catalogHash.digest());
        manifest.addProperty("_vss_catalog", identity);
        owner = resources;
        current = new Snapshot(identity, manifest, Map.copyOf(entries));
        return current;
    }

    public static synchronized Snapshot current(String identity) {
        return current != null && current.identity().equals(identity) ? current : null;
    }

    public static synchronized void clear() { owner = null; current = null; }
}
