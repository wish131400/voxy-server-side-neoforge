package dev.xantha.vss.client.prediction;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.WorldGenLevel;

/** Read-only optional-mod context: never exposes a server world to prediction. */
public final class FreeTerraForgedDecorationContext {
    // Weak owners release parsed templates with the sampler; values capture only immutable resource snapshots.
    private static final Map<ClientTerrainSampler, Map<Class<?>, Object>> TEMPLATES = new WeakHashMap<>();
    private FreeTerraForgedDecorationContext() { }

    public static Object randomState(Object world) {
        var owner = context(world);
        if (owner != null) return owner.randomStateContext();
        return ((WorldGenLevel) world).getLevel().getChunkSource().randomState();
    }

    public static Object templateServer(Object world, Class<?> api) {
        var owner = context(world);
        if (owner != null) {
            synchronized (TEMPLATES) {
                return TEMPLATES.computeIfAbsent(owner, ignored -> new java.util.HashMap<>())
                        .computeIfAbsent(api, key -> createTemplateServer(owner.structureTemplates(), key));
            }
        }
        return ((WorldGenLevel) world).getServer();
    }

    private static ClientTerrainSampler context(Object world) {
        return world instanceof PredictionFeatureContext prediction ? prediction.predictionContext() : null;
    }

    private static Object createTemplateServer(com.google.gson.JsonObject encoded, Class<?> api) {
        if (!Set.of("etcodehome.freeterraforged.server.FTFMinecraftServer",
                "raccoonman.reterraforged.server.RTFMinecraftServer",
                "com.pandaismyname1.ultraterraforged.server.UTFMinecraftServer").contains(api.getName()))
            throw new IllegalArgumentException("Unknown feature template API: " + api.getName());
        try {
            var getter = api.getMethod("getFeatureTemplateManager");
            var manager = getter.getReturnType().getConstructor(ResourceManager.class).newInstance(resources(encoded));
            return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] { api }, (proxy, method, args) -> {
                if (method.equals(getter)) return manager;
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "toString" -> "VSS immutable feature template context";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                };
                throw new UnsupportedOperationException("Feature template context cannot access " + method);
            });
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged template context unavailable", failure);
        }
    }

    static byte[] templateBytes(com.google.gson.JsonObject encoded, ResourceLocation resource) {
        return templateBytes(encoded, encoded.has("_vss_catalog") ? PredictionTemplateRepository.forCatalog(encoded) : null, resource);
    }

    private static byte[] templateBytes(com.google.gson.JsonObject encoded, PredictionTemplateRepository repository,
            ResourceLocation resource) {
        if (!dev.xantha.vss.common.worldgen.WorldgenTemplateResources.freeTerraForged(resource)
                || !resource.getPath().startsWith("structures/") || !resource.getPath().endsWith(".nbt"))
            throw new UnsupportedOperationException("Uncatalogued feature template resource: " + resource);
        var key = dev.xantha.vss.common.worldgen.WorldgenTemplateResources.key(resource);
        if (!encoded.has(key.toString())) return null;
        return repository != null ? repository.bytes(key)
                : java.util.Base64.getDecoder().decode(encoded.get(key.toString()).getAsString());
    }

    private static ResourceManager resources(com.google.gson.JsonObject encoded) {
        var repository = encoded.has("_vss_catalog") ? PredictionTemplateRepository.forCatalog(encoded) : null;
        return new ResourceManager() {
            @Override public Set<String> getNamespaces() { return Set.of("freeterraforged", "reterraforged", "ultraterraforged"); }
            @Override public Optional<Resource> getResource(ResourceLocation id) {
                byte[] bytes = templateBytes(encoded, repository, id);
                return bytes == null ? Optional.empty()
                        : Optional.of(new Resource(null, () -> new ByteArrayInputStream(bytes)));
            }
            @Override public List<Resource> getResourceStack(ResourceLocation id) { return getResource(id).stream().toList(); }
            @Override public Map<ResourceLocation, Resource> listResources(String directory, Predicate<ResourceLocation> filter) {
                throw new UnsupportedOperationException("Feature templates use exact catalog resource identities");
            }
            @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(String directory, Predicate<ResourceLocation> filter) {
                throw new UnsupportedOperationException("Feature templates use exact catalog resource identities");
            }
            @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
        };
    }
}
