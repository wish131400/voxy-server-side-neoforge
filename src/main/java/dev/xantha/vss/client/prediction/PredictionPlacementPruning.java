package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.placement.*;

/** Cache-only rejection before a pure environment read; no random draws or coordinate sampling. */
final class PredictionPlacementPruning {
    private static final Set<String> PURE = Set.of("minecraft:true", "minecraft:matching_blocks",
            "minecraft:matching_fluids", "minecraft:matching_block_tag", "minecraft:all_of",
            "minecraft:any_of", "minecraft:not");

    interface Rejection { boolean rejects(PlacementContext context, BlockPos position); }

    static Rejection compile(List<PlacementModifier> modifiers, int index, Predicate<Class<?>> pristine) {
        // No intervening random, position transformation, custom predicate or terminal may be skipped.
        if(index+1>=modifiers.size() || modifiers.get(index+1).getClass()!=BiomeFilter.class
                || !pristine.test(BiomeFilter.class))return null;
        var modifier=modifiers.get(index);
        if(!pristine.test(modifier.getClass()))return null;
        if(modifier.getClass()!=BlockPredicateFilter.class && modifier.getClass()!=EnvironmentScanPlacement.class)
            return null;
        if(!pristinePredicates(modifier,pristine))return null;
        var ops=net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                        net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        var encoded=PlacementModifier.CODEC.encodeStart(ops,modifier).result();
        if(encoded.isEmpty() || !encoded.get().isJsonObject())return null;
        var data=encoded.get().getAsJsonObject();
        // Predicates that call custom block hooks or mutable world conditions are not a pure proof.
        if(modifier.getClass()==BlockPredicateFilter.class) {
            if(!pure(data.get("predicate")))return null;
            return (context,pos)->rejects(context,pos);
        }
        if(!pure(data.get("target_condition"))
                || data.has("allowed_search_condition") && !pure(data.get("allowed_search_condition")))return null;
        Direction direction=Direction.byName(data.get("direction_of_search").getAsString());
        int steps=data.get("max_steps").getAsInt();
        if(direction==null || steps<0 || steps>32)return null;
        return (context,pos)-> {
            var cursor=pos.mutable();
            // Include the origin and maxSteps endpoint. An unknown/evicted answer cannot reject.
            for(int i=0;i<=steps;i++,cursor.move(direction))if(!rejects(context,cursor))return false;
            return true;
        };
    }

    private static boolean rejects(PlacementContext context,BlockPos pos) {
        return context.getLevel() instanceof PredictionDecorationLevel level
                && context.topFeature().isPresent() && level.cachedBiomeRejects(context.topFeature().get(),pos);
    }

    private static boolean pure(JsonElement value) {
        if(value==null || !value.isJsonObject())return false;
        var object=value.getAsJsonObject();
        if(!object.has("type") || !PURE.contains(object.get("type").getAsString()))return false;
        if(object.has("predicate") && !pure(object.get("predicate")))return false;
        if(object.has("predicates"))for(var child:object.getAsJsonArray("predicates"))if(!pure(child))return false;
        return true;
    }

    private static boolean pristinePredicates(Object value,Predicate<Class<?>> pristine) {
        if(value instanceof net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate
                && (!value.getClass().getName().startsWith("net.minecraft.world.level.levelgen.blockpredicates.")
                || !pristine.test(value.getClass())))return false;
        try {
            for(Class<?> type=value.getClass();type!=null && type!=Object.class;type=type.getSuperclass())
            for(var field:type.getDeclaredFields()) {
                if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
                if(net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate.class.isAssignableFrom(field.getType())) {
                    if(!field.trySetAccessible() || !pristinePredicates(field.get(value),pristine))return false;
                } else if(List.class.isAssignableFrom(field.getType())) {
                    if(!field.trySetAccessible())return false;
                    for(var child:(List<?>)field.get(value))
                        if(child instanceof net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate
                                && !pristinePredicates(child,pristine))return false;
                }
            }
            return true;
        } catch(ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    private PredictionPlacementPruning() { }
}
