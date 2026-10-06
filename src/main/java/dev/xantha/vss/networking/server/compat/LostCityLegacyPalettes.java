package dev.xantha.vss.networking.server.compat;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.CommonLevelAccessor;
import org.apache.commons.lang3.tuple.Pair;

/** Query-local preview palettes. Never populates the legacy mod's shared lazy caches. */
final class LostCityLegacyPalettes {
    private static final String ASSETS = "mcjty.lostcities.worldgen.lost.cityassets.";
    private final ClassLoader loader;
    private final CommonLevelAccessor level;
    private final Map<String, Object> palettes = new HashMap<>();
    private final Map<String, Object> parts = new HashMap<>();

    /** Registry slices with a private palette; avoids RegistryAssetRegistry's unsynchronized lazy writes. */
    public record PreviewPart(String[] slices, int width, int depth, Object local, String reference, Character support) {
        public String[] getSlices() { return slices; }
        public int getXSize() { return width; }
        public int getZSize() { return depth; }
    }

    LostCityLegacyPalettes(ClassLoader loader, CommonLevelAccessor level) {
        this.loader = loader;
        this.level = level;
    }

    Object local(Object asset) throws ReflectiveOperationException {
        Object local = asset instanceof PreviewPart part ? part.local : field(asset, "localPalette");
        String reference = asset instanceof PreviewPart part ? part.reference : (String) field(asset, "refPaletteName");
        if (local != null || reference == null) return local;
        Object cached = palettes.get(reference);
        if (cached != null) return cached;
        Object result = previewPalette(raw("PALETTES", reference));
        palettes.put(reference, result);
        return result;
    }

    Object part(String name) throws ReflectiveOperationException {
        Object cached = parts.get(name);
        if (cached != null) return cached;
        Object result = previewPart(raw("PARTS", name));
        parts.put(name, result);
        return result;
    }

    private PreviewPart previewPart(Object raw) throws ReflectiveOperationException {
        Object inline = call(raw, "getLocalPalette");
        Object local = inline == null ? null : previewPalette(inline);
        Character support = null;
        Object metadata = call(raw, "getMetadata");
        if (metadata instanceof List<?> entries) for (Object entry : entries) {
            Object chr = call(entry, "chr");
            if ("support".equals(call(entry, "key")) && chr instanceof String s && !s.isEmpty()) support = s.charAt(0);
        }
        return new PreviewPart(((String[]) call(raw, "getSlices")).clone(),
                (Integer) call(raw, "getxSize"), (Integer) call(raw, "getzSize"), local,
                (String) call(raw, "getRefPaletteName"), support);
    }

    // Only block mappings matter to the preview. Loot, spawners and damage are
    // generation metadata; no decoration or world mutation is performed here.
    @SuppressWarnings("unchecked")
    private Object previewPalette(Object definition) throws ReflectiveOperationException {
        return previewPaletteEntries((List<?>) call(definition, "getPaletteEntries"));
    }

    @SuppressWarnings("unchecked")
    private Object previewPaletteEntries(List<?> entries) throws ReflectiveOperationException {
        Class<?> palette = type("Palette"), info = type("Palette$Info"), entry = type("Palette$PE");
        Object result = palette.getConstructor(String.class).newInstance("vss_preview");
        Object metadata = info.getConstructor(String.class, String.class, boolean.class,
                net.minecraft.nbt.CompoundTag.class).newInstance(null, null, false, null);
        Constructor<?> makeEntry = entry.getConstructor(Object.class, info);
        Map<Character, Object> target = (Map<Character, Object>) palette.getMethod("getPalette").invoke(result);
        for (Object e : entries) {
            String character = (String) call(e, "getChr");
            Object block = call(e, "getBlock"), variant = call(e, "getVariant"), alias = call(e, "getFrompalette");
            Object blocks;
            if (block != null) blocks = state((String) block);
            else if (variant != null) blocks = weighted((List<?>) call(raw("VARIANTS", (String) variant), "getBlocks"));
            else if (alias != null) blocks = alias;
            else blocks = weighted((List<?>) call(e, "getBlocks"));
            target.put(character.charAt(0), makeEntry.newInstance(blocks, metadata));
        }
        return result;
    }

    private Pair<?, ?>[] weighted(List<?> entries) throws ReflectiveOperationException {
        if (entries == null) throw new IllegalArgumentException("Lost Cities palette has no block mapping");
        Pair<?, ?>[] result = new Pair<?, ?>[entries.size()];
        for (int i = 0; i < result.length; i++) {
            Object e = entries.get(i);
            result[i] = Pair.of(call(e, "random"), state((String) call(e, "block")));
        }
        return result;
    }

    private Object state(String text) throws ReflectiveOperationException {
        return Class.forName("mcjty.lostcities.varia.Tools", false, loader)
                .getMethod("stringToState", String.class).invoke(null, text);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object raw(String registryName, String name) throws ReflectiveOperationException {
        Object assets = type("AssetRegistries").getField(registryName).get(null);
        ResourceKey key = (ResourceKey) field(assets, "registryKey");
        ResourceLocation id = (ResourceLocation) Class.forName(
                "mcjty.lostcities.worldgen.lost.regassets.data.DataTools", false, loader)
                .getMethod("fromName", String.class).invoke(null, name);
        Registry<?> registry = level.registryAccess().registryOrThrow(key);
        Object definition = registry.get(id);
        if (definition == null) throw new IllegalArgumentException("Missing Lost Cities asset " + id);
        return definition;
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(ASSETS + name, false, loader); }
    private static Object call(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name).invoke(owner);
    }
    static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
}
