package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldgenRegistryTagsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static <T> void bind(MappedRegistry<T> registry, Map<TagKey<T>, List<Holder<T>>> tags) {
        registry.bindTags(tags);
    }
    private static <T> HolderSet.Named<T> tag(MappedRegistry<T> registry, TagKey<T> key) {
        return registry.getTag(key).orElseThrow();
    }
    private static <T> HolderLookup.RegistryLookup<T> lookup(RegistryAccess access,
            ResourceKey<? extends Registry<T>> key) { return access.registryOrThrow(key).asLookup(); }
    private static JsonObject snapshot(WorldgenRegistryDependencies dependencies) {
        JsonObject root = new JsonObject();
        root.add("noises", new JsonObject());
        root.add("density_functions", new JsonObject());
        root.add("custom_registries", dependencies.encode());
        root.add("registry_tags", dependencies.encodeTags());
        return root;
    }

    @Test void namedSetsAndHolderMembershipSurviveForwardRegistryReferences() {
        var configKey = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("example:climate_config"));
        var dependentKey = ResourceKey.<HolderSet<Integer>>createRegistryKey(ResourceLocation.parse("example:climate_region"));
        var config = new MappedRegistry<Integer>(configKey, Lifecycle.stable());
        var a = ResourceKey.create(configKey, ResourceLocation.parse("example:a"));
        var b = ResourceKey.create(configKey, ResourceLocation.parse("other:b"));
        var first = config.register(a, 731, RegistrationInfo.BUILT_IN);
        var second = config.register(b, 983, RegistrationInfo.BUILT_IN);
        var ordered = TagKey.create(configKey, ResourceLocation.parse("example:climate/ordered"));
        var subset = TagKey.create(configKey, ResourceLocation.parse("other:subset"));
        var empty = TagKey.create(configKey, ResourceLocation.parse("example:empty"));
        Map<TagKey<Integer>, List<Holder<Integer>>> tags = new LinkedHashMap<>();
        tags.put(ordered, List.of(second, first)); tags.put(subset, List.of(first)); tags.put(empty, List.of());
        bind(config, tags);
        config.freeze();
        Codec<HolderSet<Integer>> sets = RegistryCodecs.homogeneousList(configKey, Codec.INT).flatXmap(values ->
                values.size() == 2 ? com.mojang.serialization.DataResult.success(values)
                        : com.mojang.serialization.DataResult.error(() -> "tag members must be available during entry decode"),
                com.mojang.serialization.DataResult::success);
        var dependent = new MappedRegistry<HolderSet<Integer>>(dependentKey, Lifecycle.stable());
        var regionId = ResourceKey.create(dependentKey, ResourceLocation.parse("example:region"));
        dependent.register(regionId, tag(config, ordered), RegistrationInfo.BUILT_IN);
        dependent.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(
                new RegistryDataLoader.RegistryData<>(dependentKey, sets, false),
                new RegistryDataLoader.RegistryData<>(configKey, Codec.INT, false));
        var dependencies = new WorldgenRegistryDependencies(
                new RegistryAccess.ImmutableRegistryAccess(List.of(dependent, config)), codecs);
        dependencies.require(dependentKey.location());
        var root = snapshot(dependencies);
        assertEquals(List.of("example:climate_region", "example:climate_config"),
                root.getAsJsonObject("custom_registries").keySet().stream().toList(),
                "the tag consumer must decode before its member registry");
        var client = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs);
        var values = lookup(client.access(), dependentKey).getOrThrow(regionId).value();
        assertEquals(ordered, values.unwrapKey().orElseThrow());
        assertEquals(List.of(983, 731), values.stream().map(Holder::value).toList());
        var actual = lookup(client.access(), configKey);
        assertTrue(actual.getOrThrow(a).is(ordered));
        assertTrue(actual.getOrThrow(a).is(subset));
        assertFalse(actual.getOrThrow(b).is(subset));
        assertEquals(0, actual.getOrThrow(empty).size());
        assertSame(actual.getOrThrow(b), values.get(0));
    }

    @Test void vanillaProcessorTagsAreAvailableWhileCustomValuesDecode() {
        var processors = new MappedRegistry<StructureProcessorList>(Registries.PROCESSOR_LIST, Lifecycle.stable());
        var id = ResourceKey.create(Registries.PROCESSOR_LIST, ResourceLocation.parse("example:weathered"));
        var holder = processors.register(id, new StructureProcessorList(List.of()), RegistrationInfo.BUILT_IN);
        var processorsTag = TagKey.create(Registries.PROCESSOR_LIST, ResourceLocation.parse("other:pool_processors"));
        bind(processors, Map.of(processorsTag, List.of(holder)));
        processors.freeze();
        var lists = RegistryCodecs.homogeneousList(Registries.PROCESSOR_LIST, StructureProcessorType.DIRECT_CODEC);
        var customKey = ResourceKey.<HolderSet<StructureProcessorList>>createRegistryKey(ResourceLocation.parse("example:pool_config"));
        var custom = new MappedRegistry<HolderSet<StructureProcessorList>>(customKey, Lifecycle.stable());
        var configId = ResourceKey.create(customKey, ResourceLocation.parse("example:pool"));
        custom.register(configId, tag(processors, processorsTag), RegistrationInfo.BUILT_IN); custom.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(customKey, lists, false));
        var dependencies = new WorldgenRegistryDependencies(
                new RegistryAccess.ImmutableRegistryAccess(List.of(custom, processors)), codecs);
        dependencies.require(customKey.location());
        JsonObject root = snapshot(dependencies);
        JsonObject entries = new JsonObject();
        entries.add(id.location().toString(), StructureProcessorType.DIRECT_CODEC
                .encodeStart(dependencies.ops(), holder.value()).getOrThrow());
        root.add("processor_lists", entries);
        var client = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs);
        var decoded = lookup(client.access(), customKey).getOrThrow(configId).value();
        assertEquals(processorsTag, decoded.unwrapKey().orElseThrow());
        assertEquals(1, decoded.size());
        assertSame(lookup(client.access(), Registries.PROCESSOR_LIST).getOrThrow(id), decoded.get(0));
        assertTrue(decoded.get(0).is(processorsTag));
    }

    @Test void emptyTagsRetainAnEmptyRuntimeRegistry() {
        var key = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("example:empty_runtime"));
        var registry = new MappedRegistry<Integer>(key, Lifecycle.stable());
        var empty = TagKey.create(key, ResourceLocation.parse("other:empty_tag"));
        bind(registry, Map.of(empty, List.of())); registry.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, Codec.INT, false));
        var dependencies = new WorldgenRegistryDependencies(new RegistryAccess.ImmutableRegistryAccess(List.of(registry)), codecs);
        dependencies.require(key.location());
        var client = ClientWorldgenRegistries.decode(snapshot(dependencies), RegistryAccess.EMPTY, codecs);
        assertEquals(0, lookup(client.access(), key).getOrThrow(empty).size());
    }

    @Test void oldSnapshotsKeepTheirEntryShapeAndNeedNoTagField() {
        var key = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("example:old_snapshot"));
        var id = ResourceKey.create(key, ResourceLocation.parse("example:value"));
        var registry = new MappedRegistry<Integer>(key, Lifecycle.stable());
        registry.register(id, 871, RegistrationInfo.BUILT_IN); registry.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, Codec.INT, false));
        var dependencies = new WorldgenRegistryDependencies(new RegistryAccess.ImmutableRegistryAccess(List.of(registry)), codecs);
        dependencies.require(key.location());
        var root = snapshot(dependencies); root.remove("registry_tags");
        assertEquals(871, root.getAsJsonObject("custom_registries").getAsJsonObject(key.location().toString())
                .get(id.location().toString()).getAsInt());
        var client = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs);
        assertEquals(871, lookup(client.access(), key).getOrThrow(id).value());
    }

    @Test void missingTagMembersAreRejectedInsteadOfBecomingAnEmptyNamedSet() {
        var key = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("example:strict_tag"));
        var registry = new MappedRegistry<Integer>(key, Lifecycle.stable()); registry.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, Codec.INT, false));
        var dependencies = new WorldgenRegistryDependencies(new RegistryAccess.ImmutableRegistryAccess(List.of(registry)), codecs);
        dependencies.require(key.location());
        var root = snapshot(dependencies);
        var tags = new JsonObject(); var members = new JsonArray(); members.add("other:missing"); tags.add("other:required", members);
        root.getAsJsonObject("registry_tags").add(key.location().toString(), tags);
        assertThrows(IllegalStateException.class, () -> ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs));
    }

    @Test void snapshotTagsDoNotMutateFallbackOrStaticRegistries() {
        var key = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("example:isolated_runtime"));
        var id = ResourceKey.create(key, ResourceLocation.parse("example:value"));
        var activeTag = TagKey.create(key, ResourceLocation.parse("example:active"));
        var live = new MappedRegistry<Integer>(key, Lifecycle.stable());
        var liveHolder = live.register(id, 131, RegistrationInfo.BUILT_IN); bind(live, Map.of(activeTag, List.of(liveHolder))); live.freeze();
        var captured = new MappedRegistry<Integer>(key, Lifecycle.stable());
        var snapshotHolder = captured.register(id, 1091, RegistrationInfo.BUILT_IN);
        bind(captured, Map.of(activeTag, List.of(snapshotHolder))); captured.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, Codec.INT, false));
        var dependencies = new WorldgenRegistryDependencies(new RegistryAccess.ImmutableRegistryAccess(List.of(captured)), codecs);
        dependencies.require(key.location());
        var root = snapshot(dependencies);
        var staticTagsBefore = dev.xantha.vss.common.worldgen.WorldgenRegistryTags.encode(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK);
        var injectedTags = new JsonObject(); var members = new JsonArray(); members.add("minecraft:stone");
        injectedTags.add("example:must_not_bind", members);
        root.getAsJsonObject("registry_tags").add("minecraft:block", injectedTags);
        var client = ClientWorldgenRegistries.decode(root,
                new RegistryAccess.ImmutableRegistryAccess(List.of(live)), codecs);
        assertEquals(1091, lookup(client.access(), key).getOrThrow(activeTag).get(0).value());
        assertEquals(131, tag(live, activeTag).get(0).value());
        assertSame(liveHolder, tag(live, activeTag).get(0));
        assertEquals(staticTagsBefore, dev.xantha.vss.common.worldgen.WorldgenRegistryTags.encode(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK));
    }
}
