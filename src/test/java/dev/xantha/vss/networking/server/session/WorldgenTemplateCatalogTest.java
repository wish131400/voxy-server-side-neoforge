package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldgenTemplateCatalogTest {
    @AfterEach void clear() { WorldgenTemplateCatalog.clear(); }

    @Test void ftfPluralResourcesKeepExactDigestAndDoNotExposeUnrelatedResources() throws Exception {
        var original = ResourceLocation.parse("freeterraforged:structures/trees/oak.nbt");
        var key = dev.xantha.vss.common.worldgen.WorldgenTemplateResources.key(original);
        assertEquals(original, dev.xantha.vss.common.worldgen.WorldgenTemplateResources.original(key));
        var data = new byte[] {3,4,5};
        var resources = resources(Map.of("freeterraforged:trees/oak", data, "other:trees/oak", data));
        // The optional resource contract uses full plural paths, separately from normal structure ids.
        var plural = org.mockito.Mockito.mock(ResourceManager.class, org.mockito.AdditionalAnswers.delegatesTo(resources));
        org.mockito.Mockito.when(plural.listResources(org.mockito.ArgumentMatchers.eq("structures"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    @SuppressWarnings("unchecked") var filter = (Predicate<ResourceLocation>) call.getArgument(1);
                    var map = new java.util.HashMap<ResourceLocation,Resource>();
                    for (String namespace : java.util.List.of("freeterraforged","other")) {
                        var id = ResourceLocation.parse(namespace + ":structures/trees/oak.nbt");
                        if (filter.test(id)) map.put(id,new Resource(null,()->new ByteArrayInputStream(data)));
                    }
                    return map;
                });
        var catalog = WorldgenTemplateCatalog.snapshot(plural);
        assertArrayEquals(data,catalog.entries().get(key).read());
        assertEquals(StructureTemplateDigest.hash(data),catalog.entries().get(key).digest().sha256());
        assertFalse(catalog.manifest().has("vss_ftf_resource:other/structures/trees/oak.nbt"));
    }

    @Test void catalogContainsLargeTemplatesWithoutEmbeddingNbtAndIsSharedBetweenDimensions() throws Exception {
        byte[] data = new byte[600 * 1024]; new java.util.Random(3).nextBytes(data);
        var resources = resources(Map.of("z:house", data, "a:tree", new byte[] {2}));
        var snapshot = WorldgenTemplateCatalog.snapshot(resources);
        assertEquals(2, snapshot.entries().size());
        assertEquals(3, snapshot.manifest().size());
        assertTrue(snapshot.manifest().toString().length() < 500);
        assertArrayEquals(data, snapshot.entries().get(ResourceLocation.parse("z:house")).read());
        assertSame(snapshot, WorldgenTemplateCatalog.snapshot(resources));
        assertSame(snapshot, WorldgenTemplateCatalog.current(snapshot.identity()));
        assertNull(WorldgenTemplateCatalog.current("b".repeat(64)));
    }

    @Test void contentAndRemovedResourcesChangeCatalogIdentityOnReload() {
        var before = WorldgenTemplateCatalog.snapshot(resources(Map.of("mod:house", new byte[] {1}, "mod:tree", new byte[] {2})));
        WorldgenTemplateCatalog.clear();
        assertNull(WorldgenTemplateCatalog.current(before.identity()));
        var after = WorldgenTemplateCatalog.snapshot(resources(Map.of("mod:house", new byte[] {3})));
        assertNotEquals(before.identity(), after.identity());
        assertFalse(after.manifest().has("mod:tree"));
    }

    @Test void effectiveServerPackWinsAndResourceChangesCannotBeServedWithOldHash() throws Exception {
        byte[] data = {7, 8, 9};
        var snapshot = WorldgenTemplateCatalog.snapshot(resources(Map.of("mod:house", data)));
        assertEquals(StructureTemplateDigest.hash(data), snapshot.entries().get(ResourceLocation.parse("mod:house")).digest().sha256());
        data[0] = 1;
        assertThrows(IOException.class, () -> snapshot.entries().get(ResourceLocation.parse("mod:house")).read());
    }

    private static ResourceManager resources(Map<String, byte[]> values) {
        var entries = new java.util.HashMap<ResourceLocation, Resource>();
        values.forEach((id, data) -> {
            var key = ResourceLocation.parse(id);
            key = key.withPath("structure/" + key.getPath() + ".nbt");
            entries.put(key, new Resource(null, () -> new ByteArrayInputStream(data)));
        });
        return new ResourceManager() {
            @Override public Set<String> getNamespaces() { return Set.of("mod"); }
            @Override public Optional<Resource> getResource(ResourceLocation id) { return Optional.ofNullable(entries.get(id)); }
            @Override public java.util.List<Resource> getResourceStack(ResourceLocation id) { return getResource(id).stream().toList(); }
            @Override public Map<ResourceLocation, Resource> listResources(String directory, Predicate<ResourceLocation> predicate) {
                var selected = new java.util.HashMap<ResourceLocation, Resource>();
                entries.forEach((id, resource) -> { if (id.getPath().startsWith(directory + "/") && predicate.test(id)) selected.put(id, resource); });
                return selected;
            }
            @Override public Map<ResourceLocation, java.util.List<Resource>> listResourceStacks(String dir, Predicate<ResourceLocation> filter) {
                throw new UnsupportedOperationException();
            }
            @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
        };
    }
}
