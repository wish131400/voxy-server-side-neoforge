package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.fml.loading.FMLPaths;

/** Server-supplied templates only; never consult a player's save or guess missing pieces. */
final class PredictionStructureTemplates extends StructureTemplateManager {
    private final JsonObject encoded;
    private final Map<ResourceLocation, StructureTemplate> decoded = new LinkedHashMap<>(32, .75F, true);

    static synchronized PredictionStructureTemplates open(JsonObject encoded) throws IOException {
        // Vanilla's constructor requires a generated-directory handle. This is a
        // private disposable cache directory, closed immediately after construction;
        // get/getOrCreate below use only the immutable server snapshot.
        var storage = LevelStorageSource.createDefault(FMLPaths.GAMEDIR.get().resolve("vss/prediction/template-loader"));
        try (var handle = storage.createAccess("cache")) {
            return new PredictionStructureTemplates(encoded, handle);
        }
    }

    private PredictionStructureTemplates(JsonObject encoded, LevelStorageSource.LevelStorageAccess handle) {
        super(ResourceManager.Empty.INSTANCE, handle, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK.asLookup());
        this.encoded = encoded;
    }

    @Override public synchronized Optional<StructureTemplate> get(ResourceLocation id) {
        StructureTemplate template = decoded.get(id);
        if (template != null) return Optional.of(template);
        if (!encoded.has(id.toString())) return Optional.empty();
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded.get(id.toString()).getAsString());
            var tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(8 * 1024 * 1024));
            // Prediction has no entities, block entities, loot or ticking state.
            tag.remove("entities");
            stripEndCityEntityMarkers(id, tag);
            template = readStructure(tag);
            decoded.put(id, template);
            if (decoded.size() > 256) decoded.remove(decoded.keySet().iterator().next());
            return Optional.of(template);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid synced structure template: " + id, failure);
        }
    }

    @Override public StructureTemplate getOrCreate(ResourceLocation id) {
        return get(id).orElseThrow(() -> new UnsupportedOperationException("Missing synced structure template: " + id));
    }

    static void stripEndCityEntityMarkers(ResourceLocation id, net.minecraft.nbt.CompoundTag tag) {
        if (!id.getNamespace().equals("minecraft") || !id.getPath().startsWith("end_city/")) return;
        for (var value : tag.getList("blocks",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var block = (net.minecraft.nbt.CompoundTag) value;
            if (!block.contains("nbt",net.minecraft.nbt.Tag.TAG_COMPOUND)) continue;
            var data = block.getCompound("nbt");
            String marker = data.getString("metadata");
            if (marker.startsWith("Sentry") || marker.startsWith("Elytra")) data.putString("metadata","");
        }
    }
}
