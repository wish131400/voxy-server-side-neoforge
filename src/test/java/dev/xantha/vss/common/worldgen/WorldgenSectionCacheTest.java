package dev.xantha.vss.common.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.networking.server.session.WorldgenRegistrySnapshotTestBridge;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The registry section cache of the worldgen profile builds, on the real vanilla worldgen registries and the real
 * section encoders: a cached build sends the same bytes as an uncached one, and the custom registries a cached section
 * looked up are replayed into the next build.
 */
class WorldgenSectionCacheTest {
    private static RegistryAccess vanilla;
    private static final ResourceKey<Registry<Integer>> CONFIG = ResourceKey.createRegistryKey(ResourceLocation.parse("example:noise_config"));
    private static final ResourceKey<Registry<Holder<Integer>>> REGION = ResourceKey.createRegistryKey(ResourceLocation.parse("example:noise_region"));

    @BeforeAll
    static void bootstrap() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "prediction-tests"));
        }
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var statics = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()))) {
            var dynamic = RegistryDataLoader.load(resources, statics, RegistryDataLoader.WORLDGEN_REGISTRIES);
            var values = new LinkedHashMap<ResourceKey<? extends Registry<?>>, Registry<?>>();
            statics.registries().forEach(entry -> values.put(entry.key(), entry.value()));
            dynamic.registries().forEach(entry -> values.put(entry.key(), entry.value()));
            var config = new MappedRegistry<>(CONFIG, Lifecycle.stable());
            var holder = config.register(ResourceKey.create(CONFIG, ResourceLocation.parse("example:overworld")), 731, RegistrationInfo.BUILT_IN);
            config.freeze();
            var region = new MappedRegistry<>(REGION, Lifecycle.stable());
            region.register(ResourceKey.create(REGION, ResourceLocation.parse("example:region")), holder, RegistrationInfo.BUILT_IN);
            region.freeze();
            values.put(CONFIG, config);
            values.put(REGION, region);
            vanilla = new RegistryAccess.ImmutableRegistryAccess(values);
        }
    }

    private static List<RegistryDataLoader.RegistryData<?>> codecs() {
        return List.of(new RegistryDataLoader.RegistryData<>(REGION, RegistryFileCodec.create(CONFIG, Codec.INT), false),
                new RegistryDataLoader.RegistryData<>(CONFIG, Codec.INT, false));
    }

    /** One profile build: the region registry is a runtime dependency, its codec looks up the config registry. */
    private static byte[] build(Object server) throws Exception {
        boolean open = WorldgenSectionCache.begin(server, vanilla);
        try {
            var dependencies = new WorldgenRegistryDependencies(vanilla, codecs());
            dependencies.require(REGION.location());
            return WorldgenRegistrySnapshotTestBridge.encodeBytes(vanilla, dependencies);
        } finally {
            if (open) WorldgenSectionCache.end();
        }
    }

    private static byte[] uncached() throws Exception {
        var dependencies = new WorldgenRegistryDependencies(vanilla, codecs());
        dependencies.require(REGION.location());
        return WorldgenRegistrySnapshotTestBridge.encodeBytes(vanilla, dependencies);
    }

    @BeforeEach @AfterEach
    void reset() throws Exception {
        WorldgenSectionCache.clear();
        set("refused", null);
        WorldgenSectionCache.fault = null;
    }

    private static void set(String name, Object value) throws Exception {
        var field = WorldgenSectionCache.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Test
    void secondBuildOfTheSameServerReusesEverySectionAndSendsTheSameBytes() throws Exception {
        byte[] reference = uncached();
        Object server = new Object();
        byte[] first = build(server);
        var cold = WorldgenSectionCache.counts();
        assertEquals(0, cold.sectionHits());
        assertEquals(10, cold.sectionMisses(), "density functions, noises and eight optional sections");
        assertEquals(2, cold.customMisses());
        assertEquals(1, cold.tagMisses());
        byte[] second = build(server);
        var warm = WorldgenSectionCache.counts();
        assertEquals(10, warm.sectionHits());
        assertEquals(0, warm.sectionMisses());
        assertEquals(2, warm.customHits(), "both custom registries come from the cache");
        assertEquals(0, warm.customMisses());
        assertEquals(1, warm.tagHits());
        assertArrayEquals(reference, first, "a cold build sends what an uncached build sends");
        assertArrayEquals(reference, second, "a warm build sends what an uncached build sends");
        assertTrue(WorldgenSectionCache.open());
    }

    @Test
    void cachedCustomRegistryReplaysTheRegistriesItsCodecLookedUp() throws Exception {
        Object server = new Object();
        build(server);
        boolean open = WorldgenSectionCache.begin(server, vanilla);
        try {
            var dependencies = new WorldgenRegistryDependencies(vanilla, codecs());
            dependencies.require(REGION.location());
            JsonObject custom = dependencies.encode();
            assertTrue(open);
            assertTrue(custom.has("example:noise_region"));
            assertTrue(custom.has("example:noise_config"), "the config registry the region codec looked up travels again");
            assertTrue(dependencies.requested().contains(CONFIG));
        } finally {
            WorldgenSectionCache.end();
        }
    }

    @Test
    void clearAnotherServerAndAnotherRegistryAccessStartCold() throws Exception {
        Object server = new Object();
        build(server);
        dev.xantha.vss.networking.server.session.WorldgenProfileHolder.clear();
        build(server);
        assertEquals(0, WorldgenSectionCache.counts().sectionHits(), "the holder's clear drops the sections");
        build(new Object());
        assertEquals(0, WorldgenSectionCache.counts().sectionHits(), "another server instance is not served");
        assertTrue(WorldgenSectionCache.begin(server, RegistryAccess.EMPTY));
        try {
            var dependencies = new WorldgenRegistryDependencies(vanilla, codecs());
            assertNull(WorldgenSectionCache.cachedSection(vanilla, dependencies, Registries.BIOME, null), "a build for other registries is not served");
        } finally {
            WorldgenSectionCache.end();
        }
    }

    @Test
    void buildOutsideTheHolderAndOnAnotherThreadIsNotServed() throws Exception {
        Object server = new Object();
        build(server);
        WorldgenSectionCache.begin(server, vanilla);
        try {
            var other = new Thread(() -> {
                var dependencies = new WorldgenRegistryDependencies(vanilla, codecs());
                assertNull(WorldgenSectionCache.detached(vanilla, dependencies));
            });
            other.start();
            other.join();
        } finally {
            WorldgenSectionCache.end();
        }
        var before = WorldgenSectionCache.counts();
        assertArrayEquals(uncached(), uncached());
        assertEquals(before, WorldgenSectionCache.counts(), "encodes outside a holder build neither read nor fill the cache");
    }

    @Test
    void failingCacheStepSwitchesTheCacheOffAndTheBuildStillSendsTheSameBytes() throws Exception {
        byte[] reference = uncached();
        for (String step : List.of("cachedSection", "storeSection", "cachedDensities", "storeDensities", "cachedCustom",
                "storeCustom", "cachedTags", "storeTags")) {
            reset();
            Object server = new Object();
            // A cached step fails in a warm build, a store step in a cold one.
            if (step.startsWith("cached")) build(server);
            WorldgenSectionCache.fault = step + "#1";
            byte[] built = build(server);
            assertArrayEquals(reference, built, step);
            assertFalse(WorldgenSectionCache.open(), step);
            assertTrue(WorldgenSectionCache.refused().contains(step), WorldgenSectionCache.refused());
            assertFalse(WorldgenSectionCache.begin(server, vanilla), "the cache stays off: " + step);
        }
    }

    @Test
    void sectionsOfOneDimensionDoNotCarryTheRegistriesAnotherDimensionRequired() throws Exception {
        var plain = new WorldgenRegistryDependencies(vanilla, codecs());
        byte[] reference = WorldgenRegistrySnapshotTestBridge.encodeBytes(vanilla, plain);
        Object server = new Object();
        build(server);
        WorldgenSectionCache.begin(server, vanilla);
        byte[] warm;
        try {
            warm = WorldgenRegistrySnapshotTestBridge.encodeBytes(vanilla, new WorldgenRegistryDependencies(vanilla, codecs()));
        } finally {
            WorldgenSectionCache.end();
        }
        assertEquals(10, WorldgenSectionCache.counts().sectionHits());
        assertArrayEquals(reference, warm, "a dimension without the region dependency must not receive it from a cached section");
    }

    /** A section whose codec looks up a custom registry, as modded worldgen codecs do. */
    private static <T> JsonObject encodeEntries(RegistryAccess access, net.minecraft.resources.RegistryOps<com.google.gson.JsonElement> ops,
            ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
        var result = new JsonObject();
        for (var entry : access.registryOrThrow(key).entrySet())
            result.add(entry.getKey().location().toString(), codec.encodeStart(ops, entry.getValue()).getOrThrow());
        return result;
    }

    @Test
    void customLookupsOfASectionReachTheBuildWhenTheStoreFailsAndWhenItIsServed() throws Exception {
        var codec = RegistryFileCodec.create(CONFIG, Codec.INT);
        Object server = new Object();
        WorldgenSectionCache.fault = "storeSection#1";
        WorldgenSectionCache.begin(server, vanilla);
        try {
            var dependencies = new WorldgenRegistryDependencies(vanilla, codecs());
            JsonObject section = WorldgenSectionCache.section(vanilla, dependencies, REGION, codec, WorldgenSectionCacheTest::encodeEntries);
            assertTrue(section.has("example:region"));
            assertTrue(dependencies.requested().contains(CONFIG), "the fallback encodes again with the build's own instance");
        } finally {
            WorldgenSectionCache.end();
        }
        assertFalse(WorldgenSectionCache.open());
        reset();
        for (int build = 0; build < 2; build++) {
            WorldgenSectionCache.begin(server, vanilla);
            try {
                var dependencies = new WorldgenRegistryDependencies(vanilla, codecs());
                JsonObject section = WorldgenSectionCache.section(vanilla, dependencies, REGION, codec, WorldgenSectionCacheTest::encodeEntries);
                assertTrue(dependencies.requested().contains(CONFIG), "build " + build);
                assertFalse(section.has("added by the caller"), "a served section is the caller's own copy: build " + build);
                section.addProperty("added by the caller", true);
            } finally {
                WorldgenSectionCache.end();
            }
        }
        assertEquals(1, WorldgenSectionCache.counts().sectionHits());
    }

    @Test
    void sectionCacheDeclinesWhatWouldExceedItsByteEstimateAndCountsBytes() {
        long[] text = new long[2], small = new long[2];
        WorldgenSectionCache.estimate(new JsonPrimitive("x".repeat(1 << 20)), text);
        var object = new JsonObject();
        object.addProperty("ab", 1);
        object.add("list", new JsonArray());
        WorldgenSectionCache.estimate(object, small);
        assertEquals(1, text[0]);
        assertTrue(text[1] >= (1 << 20));
        assertEquals(3, small[0]);
        assertEquals(64 + (88 + 2) + (88 + 4) + 40 + 56, small[1]);
    }
}
