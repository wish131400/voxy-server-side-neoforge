package dev.xantha.vss.common.worldgen;

import net.minecraft.resources.ResourceLocation;

/** Distinct catalog keys for optional-mod resources whose path is already complete. */
public final class WorldgenTemplateResources {
    private static final String RAW_NAMESPACE = "vss_ftf_resource";
    private WorldgenTemplateResources() { }

    public static boolean freeTerraForged(ResourceLocation id) {
        return id.getNamespace().equals("freeterraforged") || id.getNamespace().equals("reterraforged")
                || id.getNamespace().equals("ultraterraforged");
    }
    public static ResourceLocation key(ResourceLocation resource) {
        return ResourceLocation.fromNamespaceAndPath(RAW_NAMESPACE, resource.getNamespace() + "/" + resource.getPath());
    }
    /** null identifies an ordinary vanilla structure key. */
    public static ResourceLocation original(ResourceLocation key) {
        if (!key.getNamespace().equals(RAW_NAMESPACE)) return null;
        int slash = key.getPath().indexOf('/');
        if (slash <= 0) throw new IllegalArgumentException("Malformed optional template key: " + key);
        var id = ResourceLocation.fromNamespaceAndPath(key.getPath().substring(0, slash), key.getPath().substring(slash + 1));
        if (!freeTerraForged(id) || !id.getPath().startsWith("structures/") || !id.getPath().endsWith(".nbt"))
            throw new IllegalArgumentException("Invalid optional template resource: " + id);
        return id;
    }
}
