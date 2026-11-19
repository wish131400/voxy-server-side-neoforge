package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import java.io.IOException;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.neoforged.fml.ModList;

/** Reads SERVER_DATA, not the client's assets-only resource manager. */
final class PredictionLocalTemplates {
    private PredictionLocalTemplates() { }

    static byte[] read(ResourceLocation id, StructureTemplateDigest expected) {
        ResourceLocation resource = dev.xantha.vss.common.worldgen.WorldgenTemplateResources.original(id);
        if (resource == null) resource = id.withPath("structure/" + id.getPath() + ".nbt");
        try {
            var supplier = Minecraft.getInstance().getVanillaPackResources().getResource(PackType.SERVER_DATA, resource);
            if (supplier != null) {
                try (var input = supplier.get()) { return expected.read(input); }
                catch (IOException mismatch) { /* The server may have a datapack override. */ }
            }
        } catch (RuntimeException unavailable) { /* No client in isolated tests. */ }
        var mods = ModList.get();
        if (mods != null) for (var mod : mods.getModFiles()) {
            try {
                var path = mod.getFile().findResource("data", resource.getNamespace(), resource.getPath());
                if (Files.isRegularFile(path) && Files.size(path) == expected.bytes())
                    try (var input = Files.newInputStream(path)) { return expected.read(input); }
            } catch (IOException | RuntimeException unavailable) { /* Try another installed provider. */ }
        }
        // Vanilla and dev launches may expose SERVER_DATA through the classpath.
        try {
            var resources = PredictionLocalTemplates.class.getClassLoader().getResources(
                    "data/" + resource.getNamespace() + "/" + resource.getPath());
            while (resources.hasMoreElements()) {
                try (var input = resources.nextElement().openStream()) { return expected.read(input); }
                catch (IOException mismatch) { /* Match bytes, never guess resource-pack precedence. */ }
            }
        } catch (IOException unavailable) { }
        return null;
    }
}
