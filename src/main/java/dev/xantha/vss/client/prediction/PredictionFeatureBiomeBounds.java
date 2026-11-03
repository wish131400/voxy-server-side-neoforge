package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.placement.*;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;

/**
 * Conservative candidate bounds up to the first biome filter.
 * This analyzer never executes placement, consumes RNG, samples terrain, or
 * prefetches biomes. Bounds alone do not authorize skipping a feature: callers
 * must preserve preceding callbacks and prove every biome corner absent.
 */
final class PredictionFeatureBiomeBounds {
    private static final Set<Class<?>> MODIFIERS = Set.of(CountPlacement.class, InSquarePlacement.class,
            HeightRangePlacement.class, HeightmapPlacement.class, EnvironmentScanPlacement.class,
            NoiseBasedCountPlacement.class, NoiseThresholdCountPlacement.class, SurfaceWaterDepthFilter.class,
            SurfaceRelativeThresholdFilter.class,
            RandomOffsetPlacement.class, RarityFilter.class, BlockPredicateFilter.class, BiomeFilter.class);
    // Runtime type identities survive Fabric intermediary and Forge SRG remapping.
    private static final Set<Class<?>> PREDICATES = new java.util.HashSet<>(List.of(
            BlockPredicate.allOf(BlockPredicate.alwaysTrue()).getClass(),
            BlockPredicate.anyOf(BlockPredicate.alwaysTrue()).getClass(),
            BlockPredicate.not(BlockPredicate.alwaysTrue()).getClass(),
            BlockPredicate.matchesTag(net.minecraft.tags.BlockTags.DIRT).getClass(),
            BlockPredicate.matchesBlocks(net.minecraft.world.level.block.Blocks.AIR).getClass(),
            BlockPredicate.matchesFluids(net.minecraft.world.level.material.Fluids.WATER).getClass(),
            BlockPredicate.solid().getClass(),BlockPredicate.alwaysTrue().getClass()));
    private PredictionFeatureBiomeBounds() { }

    static Plan compile(PlacedFeature feature) {
        return compile(feature, PredictionPlacementExecutor::unmodified);
    }

    static Plan compile(PlacedFeature feature, Predicate<Class<?>> pristine) {
        var modifiers = new ArrayList<JsonObject>();
        if (!pristine.test(PlacedFeature.class) || !pristine.test(BiomeManager.class)
                || !pristine.test(PlacementModifier.class)
                || !pristine.test(PlacementFilter.class) || !pristine.test(RepeatingPlacement.class)
                || !pristine.test(BlockPredicate.class)) return Plan.UNKNOWN;
        if (feature.placement().stream().noneMatch(p -> p.getClass() == BiomeFilter.class)) return Plan.UNKNOWN;
        try {
            for (var modifier : feature.placement()) {
                if (!MODIFIERS.contains(modifier.getClass()) || !pristine.test(modifier.getClass())) return Plan.UNKNOWN;
                for (Class<?> owner=modifier.getClass();owner!=null && owner!=Object.class;owner=owner.getSuperclass())
                    for (var field:owner.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        if (net.minecraft.util.valueproviders.IntProvider.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            if (!intProvider(field.get(modifier),pristine)) return Plan.UNKNOWN;
                        } else if (net.minecraft.world.level.levelgen.heightproviders.HeightProvider.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true); Object height=field.get(modifier);
                            if ((height.getClass()!=UniformHeight.class && height.getClass()!=ConstantHeight.class)
                                    || !pristine.test(height.getClass()) || !heightAnchors(height,pristine)) return Plan.UNKNOWN;
                        } else if (BlockPredicate.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            if (!predicate((BlockPredicate)field.get(modifier),pristine,0)) return Plan.UNKNOWN;
                        }
                    }
                JsonObject encoded;
                if (modifier instanceof BlockPredicateFilter) {
                    // Holder sets in predicates need registry-aware codec ops.
                    // Their payload cannot move candidates and was audited above.
                    encoded = new JsonObject();
                    encoded.addProperty("type", "minecraft:block_predicate_filter");
                } else if (modifier instanceof EnvironmentScanPlacement scan) {
                    encoded = new JsonObject();
                    encoded.addProperty("type", "minecraft:environment_scan");
                    encoded.addProperty("direction_of_search", scanDirection(scan).getSerializedName());
                    encoded.addProperty("max_steps", scanSteps(scan));
                } else {
                    encoded = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, modifier).result().orElseThrow().getAsJsonObject();
                }
                modifiers.add(encoded);
                if (modifier.getClass() == BiomeFilter.class) {
                    return new Plan(List.copyOf(modifiers));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return Plan.UNKNOWN; }
        return Plan.UNKNOWN;
    }

    private static net.minecraft.core.Direction scanDirection(EnvironmentScanPlacement scan) throws ReflectiveOperationException {
        for (var field:EnvironmentScanPlacement.class.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && field.getType()==net.minecraft.core.Direction.class) {
            field.setAccessible(true);return (net.minecraft.core.Direction)field.get(scan);
        }
        throw new NoSuchFieldException("environment scan direction");
    }
    private static int scanSteps(EnvironmentScanPlacement scan) throws ReflectiveOperationException {
        for (var field:EnvironmentScanPlacement.class.getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && field.getType()==int.class) {
                field.setAccessible(true);return field.getInt(scan);
            }
        throw new NoSuchFieldException("environment scan steps");
    }

    private static boolean heightAnchors(Object provider, Predicate<Class<?>> pristine)
            throws ReflectiveOperationException {
        for (var field : provider.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !net.minecraft.world.level.levelgen.VerticalAnchor.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object anchor = field.get(provider);
            if (anchor.getClass().getNestHost() != net.minecraft.world.level.levelgen.VerticalAnchor.class
                    || !pristine.test(anchor.getClass())) return false;
        }
        return true;
    }

    private static boolean intProvider(Object value, Predicate<Class<?>> pristine) {
        return (value.getClass() == ConstantInt.class || value.getClass() == UniformInt.class
               ) && pristine.test(value.getClass());
    }

    private static boolean predicate(BlockPredicate value, Predicate<Class<?>> pristine, int depth)
            throws ReflectiveOperationException {
        Class<?> type = value.getClass();
        if (depth > 16 || !PREDICATES.contains(type) || !pristine.test(type)) return false;
        // Only predicate-valued children need auditing; state and tag payloads
        // cannot move a candidate or execute a custom placement implementation.
        for (Class<?> owner = type; owner != Object.class && owner != null; owner = owner.getSuperclass())
            for (var field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true); Object child = field.get(value);
                if (child instanceof BlockPredicate nested && !predicate(nested, pristine, depth + 1)) return false;
                if (child instanceof Iterable<?> children) for (var nested : children)
                    if (nested instanceof BlockPredicate p && !predicate(p, pristine, depth + 1)) return false;
            }
        return true;
    }

    static final class Plan {
        static final Plan UNKNOWN = new Plan(List.of());
        private final List<JsonObject> modifiers;
        Plan(List<JsonObject> modifiers) { this.modifiers = modifiers; }
        boolean known() { return !modifiers.isEmpty(); }
        Bounds bounds(BlockPos origin, WorldGenerationContext context, int minY, int maxY) {
            Bounds b = new Bounds(origin.getX(), origin.getY(), origin.getZ(), origin.getX(), origin.getY(), origin.getZ());
            try {
                for (var m : modifiers) {
                    String type = m.get("type").getAsString();
                    switch (type) {
                        case "minecraft:count", "minecraft:rarity_filter", "minecraft:block_predicate_filter",
                             "minecraft:noise_based_count", "minecraft:noise_threshold_count",
                             "minecraft:surface_water_depth_filter", "minecraft:surface_relative_threshold_filter" -> { }
                        case "minecraft:in_square" -> b = b.offset(0, 0, 0, 15, 0, 15);
                        case "minecraft:height_range" -> {
                            int[] y = height(m.get("height"), context);
                            b = new Bounds(b.minX, y[0], b.minZ, b.maxX, y[1], b.maxZ);
                        }
                        case "minecraft:heightmap" -> b = new Bounds(b.minX, minY, b.minZ, b.maxX, Math.addExact(maxY, 1), b.maxZ);
                        case "minecraft:random_offset" -> {
                            int[] xz=integer(m.get("xz_spread")), y=integer(m.get("y_spread"));
                            b=b.offset(xz[0],y[0],xz[0],xz[1],y[1],xz[1]);
                        }
                        case "minecraft:offset" -> {
                            int[] x = integer(m.get("x")), y = integer(m.get("y")), z = integer(m.get("z"));
                            b = b.offset(x[0], y[0], z[0], x[1], y[1], z[1]);
                        }
                        case "minecraft:environment_scan" -> {
                            int steps = m.get("max_steps").getAsInt();
                            if (steps < 0 || steps > 4096) return null;
                            b = switch (m.get("direction_of_search").getAsString()) {
                                case "up" -> b.offset(0, 0, 0, 0, steps, 0);
                                case "down" -> b.offset(0, -steps, 0, 0, 0, 0);
                                case "east" -> b.offset(0, 0, 0, steps, 0, 0);
                                case "west" -> b.offset(-steps, 0, 0, 0, 0, 0);
                                case "south" -> b.offset(0, 0, 0, 0, 0, steps);
                                case "north" -> b.offset(0, 0, -steps, 0, 0, 0);
                                default -> null;
                            };
                            if (b == null) return null;
                        }
                        case "minecraft:biome" -> { return b; }
                        default -> { return null; }
                    }
                }
            } catch (IllegalArgumentException | ArithmeticException | IllegalStateException | NullPointerException unknown) { return null; }
            return null;
        }
    }

    private static int[] height(JsonElement element, WorldGenerationContext context) {
        JsonObject value = element.getAsJsonObject();
        if (!value.has("type")) { int y = anchor(value, context); return new int[] { y, y }; }
        return switch (value.get("type").getAsString()) {
            case "minecraft:constant" -> { int y = anchor(value.getAsJsonObject("value"), context); yield new int[] { y, y }; }
            case "minecraft:uniform" -> {
                int min = anchor(value.getAsJsonObject("min_inclusive"), context), max = anchor(value.getAsJsonObject("max_inclusive"), context);
                // UniformHeight returns its min anchor when the range is inverted.
                yield new int[] { min, Math.max(min, max) };
            }
            default -> throw new IllegalArgumentException("Unknown height provider");
        };
    }

    private static int anchor(JsonObject value, WorldGenerationContext context) {
        if (value.size() != 1) throw new IllegalArgumentException("Unknown anchor");
        var entry = value.entrySet().iterator().next(); int offset = entry.getValue().getAsInt();
        return switch (entry.getKey()) {
            case "absolute" -> offset;
            case "above_bottom" -> Math.addExact(context.getMinGenY(), offset);
            case "below_top" -> Math.subtractExact(Math.addExact(context.getMinGenY(), context.getGenDepth() - 1), offset);
            default -> throw new IllegalArgumentException("Unknown anchor");
        };
    }

    private static int[] integer(JsonElement element) {
        if (element.isJsonPrimitive()) { int value = element.getAsInt(); return new int[] { value, value }; }
        var value = element.getAsJsonObject();
        if (value.get("type").getAsString().equals("minecraft:constant")) {
            int number = value.get("value").getAsInt(); return new int[] { number, number };
        }
        if (value.get("type").getAsString().equals("minecraft:trapezoid")) {
            int min=value.get("min").getAsInt(),max=value.get("max").getAsInt();
            if (min>max) throw new IllegalArgumentException("Inverted int provider");
            return new int[]{min,max};
        }
        if (!value.get("type").getAsString().equals("minecraft:uniform")) throw new IllegalArgumentException("Unknown int provider");
        var bounds = value.has("value") ? value.getAsJsonObject("value") : value;
        int min = bounds.get("min_inclusive").getAsInt(), max = bounds.get("max_inclusive").getAsInt();
        if (min > max) throw new IllegalArgumentException("Inverted int provider");
        return new int[] { min, max };
    }

    static Bounds quartEnvelope(Bounds b) {
        // BiomeManager offsets blocks by -2 then chooses one of the surrounding
        // eight quart corners. All eight must be covered, including negatives.
        return new Bounds(quart(b.minX), quart(b.minY), quart(b.minZ),
                Math.addExact(quart(b.maxX), 1), Math.addExact(quart(b.maxY), 1), Math.addExact(quart(b.maxZ), 1));
    }
    private static int quart(int block) { return Math.toIntExact(Math.floorDiv((long) block - 2, 4)); }
    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Bounds { if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Inverted bounds"); }
        Bounds offset(int x0, int y0, int z0, int x1, int y1, int z1) {
            return new Bounds(Math.addExact(minX,x0), Math.addExact(minY,y0), Math.addExact(minZ,z0),
                    Math.addExact(maxX,x1), Math.addExact(maxY,y1), Math.addExact(maxZ,z1));
        }
    }
}
