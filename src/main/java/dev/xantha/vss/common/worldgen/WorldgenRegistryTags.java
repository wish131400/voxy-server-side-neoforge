package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

/** Resolved tag membership for isolated worldgen registries, never live client registries. */
public final class WorldgenRegistryTags {
    private WorldgenRegistryTags() { }

    public static <T> JsonObject encode(Registry<T> registry) {
        JsonObject result = new JsonObject();
        registry.getTags().map(com.mojang.datafixers.util.Pair::getSecond).sorted(java.util.Comparator.comparing(tag -> tag.key().location().toString()))
                .forEach(tag -> {
                    JsonArray members = new JsonArray();
                    tag.stream().forEach(holder -> members.add(holder.unwrapKey().orElseThrow(() ->
                            new IllegalStateException("Unregistered member of worldgen tag " + tag.key()))
                            .location().toString()));
                    result.add(tag.key().location().toString(), members);
                });
        return result;
    }

    /** Bind before decoding entries: named sets and their members may be forward references. */
    public static <T> void bind(MappedRegistry<T> registry, JsonObject snapshot) {
        var lookup = registry.createRegistrationLookup();
        Map<TagKey<T>, List<Holder<T>>> tags = new LinkedHashMap<>();
        for (var entry : snapshot.entrySet()) {
            TagKey<T> tag = TagKey.create(registry.key(), ResourceLocation.parse(entry.getKey()));
            List<Holder<T>> members = new ArrayList<>();
            for (var value : entry.getValue().getAsJsonArray()) {
                members.add(lookup.getOrThrow(ResourceKey.create(registry.key(), ResourceLocation.parse(value.getAsString()))));
            }
            tags.put(tag, List.copyOf(members));
        }
        registry.bindTags(tags);
    }
}
