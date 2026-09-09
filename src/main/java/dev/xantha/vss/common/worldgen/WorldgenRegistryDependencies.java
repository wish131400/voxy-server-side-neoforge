package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;
import java.util.*;

/** Collects custom data registries actually requested by worldgen codecs. */
public final class WorldgenRegistryDependencies implements RegistryOps.RegistryInfoLookup {
    private final RegistryAccess access;
    private final Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.RegistryData<?>> catalog = new LinkedHashMap<>();
    private final Set<ResourceKey<? extends Registry<?>>> requested = new LinkedHashSet<>();
    private final RegistryOps<JsonElement> ops;

    public WorldgenRegistryDependencies(RegistryAccess access) {
        this(access, DataPackRegistriesHooks.getDataPackRegistries());
    }

    public WorldgenRegistryDependencies(RegistryAccess access, List<RegistryDataLoader.RegistryData<?>> codecs) {
        this.access = access;
        for (var data : codecs) {
            if (!data.key().location().getNamespace().equals("minecraft")) catalog.put(data.key(), data);
        }
        ops = RegistryOps.create(JsonOps.INSTANCE, this);
        // Lithostitched's structure mixins look this up at generation time;
        // no holder in the vanilla structure codec exposes the dependency.
        var templates = ResourceKey.createRegistryKey(
                net.minecraft.resources.ResourceLocation.parse("lithostitched:template_list"));
        if (catalog.containsKey(templates) && access.registry(templates).isPresent()) requested.add(templates);
    }

    public RegistryOps<JsonElement> ops() { return ops; }

    /** Runtime dependencies (such as RTF's preset) need not appear in a holder codec. */
    public void require(net.minecraft.resources.ResourceLocation id) {
        var key = ResourceKey.createRegistryKey(id);
        if (!catalog.containsKey(key) || access.registry(key).isEmpty()) {
            throw new IllegalStateException("Missing worldgen runtime registry: " + id);
        }
        requested.add(key);
    }

    @Override
    public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
        if (catalog.containsKey(key)) requested.add((ResourceKey<? extends Registry<?>>) key);
        return access.lookup(key).map(RegistryOps.RegistryInfo::fromRegistryLookup);
    }

    public JsonObject encode() {
        JsonObject result = new JsonObject();
        while (true) {
            var pending = requested.stream().filter(key -> !result.has(key.location().toString())).toList();
            if (pending.isEmpty()) return result;
            for (var key : pending) result.add(key.location().toString(), encode(catalog.get(key)));
        }
    }

    private <T> JsonObject encode(RegistryDataLoader.RegistryData<T> data) {
        JsonObject entries = new JsonObject();
        for (var entry : access.registryOrThrow(data.key()).entrySet()) {
            try {
                entries.add(entry.getKey().location().toString(),
                        data.elementCodec().encodeStart(ops, entry.getValue()).getOrThrow());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Cannot snapshot custom registry " + data.key().location()
                        + " entry " + entry.getKey().location(), failure);
            }
        }
        return entries;
    }
}
