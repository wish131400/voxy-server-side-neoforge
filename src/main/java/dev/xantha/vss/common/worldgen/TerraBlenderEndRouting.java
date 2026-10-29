package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact replay of the initialized server's four End weighted areas. */
public final class TerraBlenderEndRouting {
    public static final String FIELD = "vss_terrablender_end";
    private static final String[] NAMES = {"highlands", "midlands", "edge", "islands"};
    private record Area(TerraBlenderGrid.Tree grid, List<String> biomes, String fallback) {
        String get(int x, int z) { int index = grid.get(x, z); return index < biomes.size() ? biomes.get(index) : fallback; }
    }
    private final Area[] areas = new Area[4];
    private final Set<String> possible;
    public TerraBlenderEndRouting(JsonObject section, long seed) {
        if (integer(section, "revision") != 1 || section.get("seed").getAsLong() != seed
                || !section.get("density_coordinates").getAsString().equals("block"))
            throw new IllegalArgumentException("Unsupported TerraBlender End routing contract");
        Set<String> union = new LinkedHashSet<>();
        union.add("minecraft:the_end");
        for (int i = 0; i < areas.length; i++) {
            JsonObject area = section.getAsJsonObject(NAMES[i]);
            var input = area.getAsJsonArray("biomes");
            if (input.size() > 4096) throw new IllegalArgumentException("TerraBlender End biome count");
            List<String> names = new ArrayList<>();
            List<TerraBlenderGrid.Region> weights = new ArrayList<>();
            for (var value : input) {
                JsonObject entry = value.getAsJsonObject();
                String biome = entry.get("biome").getAsString();
                if (!biome.contains(":")) throw new IllegalArgumentException("Invalid End biome identity");
                int index = names.indexOf(biome);
                if (index < 0) { index = names.size(); names.add(biome); }
                weights.add(new TerraBlenderGrid.Region(index, integer(entry, "weight")));
            }
            String fallback = area.get("default_biome").getAsString();
            // Index past the table denotes the BiomeInitialLayer default when total weight is zero.
            if (weights.stream().allMatch(w -> w.weight() == 0)) weights = List.of(new TerraBlenderGrid.Region(names.size(), 1));
            areas[i] = new Area(TerraBlenderGrid.build(seed, integer(area, "size"), weights), List.copyOf(names), fallback);
            union.addAll(names); union.add(fallback);
        }
        possible = Set.copyOf(union);
    }
    private static int integer(JsonObject value, String name) {
        return value.get(name).getAsBigDecimal().intValueExact();
    }
    public Set<String> possibleBiomes() { return possible; }
    public static boolean central(int quartX, int quartZ) {
        long x = (quartX << 2) >> 4, z = (quartZ << 2) >> 4;
        return x * x + z * z <= 4096;
    }
    public String sample(int quartX, int quartZ, double erosion) {
        if (central(quartX, quartZ)) return "minecraft:the_end";
        int index = erosion > .25 ? 0 : erosion >= -.0625 ? 1 : erosion < -.21875 ? 3 : 2;
        return areas[index].get(quartX, quartZ);
    }
}
