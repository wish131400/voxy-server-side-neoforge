package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonElement;
import java.lang.reflect.Field;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.CherryFoliagePlacer;

/** Preserve effective runtime values which the 1.21.1 vanilla codec encodes incorrectly. */
public final class VanillaFeatureSnapshot {
    private VanillaFeatureSnapshot() { }

    public static JsonElement correct(ConfiguredFeature<?, ?> feature, JsonElement encoded) {
        if (feature.config() instanceof TreeConfiguration tree
                && tree.foliagePlacer instanceof CherryFoliagePlacer cherry) {
            // Vanilla's corner_hole_chance getter reads wideBottomLayerHoleChance.
            // Read the actual field once per registry snapshot, never per block.
            try {
                encoded.getAsJsonObject().getAsJsonObject("config")
                        .getAsJsonObject("foliage_placer")
                        .addProperty("corner_hole_chance", CornerField.VALUE.getFloat(cherry));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot preserve the vanilla cherry foliage configuration", exception);
            }
        }
        return encoded;
    }

    private static final class CornerField {
        static final Field VALUE = find();
        private static Field find() {
            try {
                Field field = CherryFoliagePlacer.class.getDeclaredField("cornerHoleChance");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Minecraft 1.21.1 cherry foliage field unavailable", exception);
            }
        }
    }
}
