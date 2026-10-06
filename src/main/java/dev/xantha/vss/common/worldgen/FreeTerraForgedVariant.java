package dev.xantha.vss.common.worldgen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Published FreeTerraForged APIs before and after the 1.0 namespace migration. */
public enum FreeTerraForgedVariant {
    CURRENT("freeterraforged", "etcodehome.freeterraforged.", "FTF"),
    LEGACY("reterraforged", "raccoonman.reterraforged.", "RTF");

    public final String namespace;
    public final String packagePrefix;
    public final String abbreviation;

    FreeTerraForgedVariant(String namespace, String packagePrefix, String abbreviation) {
        this.namespace = namespace;
        this.packagePrefix = packagePrefix;
        this.abbreviation = abbreviation;
    }

    public String worldgenClass(String name) { return packagePrefix + "world.worldgen." + name; }
    public String stateClass() { return worldgenClass(abbreviation + "RandomState"); }

    public boolean hasPreset(RegistryAccess registries) {
        var key = ResourceKey.createRegistryKey(ResourceLocation.tryParse(namespace + ":worldgen/preset"));
        return registries.registry(key).map(registry -> registry.containsKey(
                ResourceLocation.tryParse(namespace + ":preset"))).orElse(false);
    }

    public static FreeTerraForgedVariant fromClass(Class<?> type) throws ClassNotFoundException {
        for (var variant : values()) {
            if (type.getName().startsWith(variant.packagePrefix)) return variant;
        }
        throw new ClassNotFoundException("Unknown FreeTerraForged API: " + type.getName());
    }

    public static FreeTerraForgedVariant detect(ClassLoader loader) throws ClassNotFoundException {
        for (var variant : values()) {
            try {
                Class.forName(variant.stateClass(), false, loader);
                return variant;
            } catch (ClassNotFoundException absent) { }
        }
        throw new ClassNotFoundException("FreeTerraForged RandomState API unavailable");
    }
}
