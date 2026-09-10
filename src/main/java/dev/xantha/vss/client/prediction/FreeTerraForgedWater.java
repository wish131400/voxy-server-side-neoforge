package dev.xantha.vss.client.prediction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Surface water elevation from the same hydrology function as FreeTerraForged's surface pass. */
final class FreeTerraForgedWater {
    private final Object levels;
    private final Method scale, hydrology, river, lake, wetland;
    private final Field terrain, riverLevel, waterTable, continentScale, continentModifier;
    private final float ocean;

    FreeTerraForgedWater(Object context) throws ReflectiveOperationException {
        ClassLoader loader = context.getClass().getClassLoader();
        String prefix = "raccoonman.reterraforged.world.worldgen.cell.";
        levels = context.getClass().getField("levels").get(context);
        scale = levels.getClass().getMethod("scale", float.class);
        ocean = levels.getClass().getField("water").getFloat(levels);
        Class<?> cell = loader.loadClass(prefix + "Cell");
        terrain = cell.getField("terrain");
        Field waterLevel;
        try {
            waterLevel = cell.getField("riverWaterLevel");
        } catch (NoSuchFieldException legacyRelease) {
            // 6001/6002 use the generator's ordinary sea-level fluid picker.
            waterLevel = null;
        }
        riverLevel = waterLevel;
        if (riverLevel == null) {
            waterTable = continentScale = continentModifier = null;
            river = lake = wetland = hydrology = null;
            return;
        }
        waterTable = cell.getField("waterTable");
        river = terrain.getType().getMethod("isRiver");
        lake = terrain.getType().getMethod("isLake");
        wetland = terrain.getType().getMethod("isWetland");
        Class<?> hydrologyType = loader.loadClass(prefix + "rivermap.ContinentalHydrology");
        Method waterHeight;
        try {
            waterHeight = hydrologyType.getMethod("getComplexWaterHeight", float.class, float.class, float.class);
        } catch (NoSuchMethodException legacyRelease) {
            waterHeight = hydrologyType.getMethod("getWeightedWaterHeight", float.class);
        }
        hydrology = waterHeight;
        continentScale = hydrology.getParameterCount() == 3 ? cell.getField("globalContinentScale") : null;
        continentModifier = hydrology.getParameterCount() == 3 ? cell.getField("continentSizeModifier") : null;
    }

    int surfaceY(Object cell) throws ReflectiveOperationException {
        if (riverLevel == null) return Integer.MIN_VALUE;
        Object kind = terrain.get(cell);
        if (kind == null || riverLevel.getFloat(cell) <= 0
                || !((boolean) river.invoke(kind) || (boolean) lake.invoke(kind) || (boolean) wetland.invoke(kind))) {
            return Integer.MIN_VALUE;
        }
        float uplift = hydrology.getParameterCount() == 1
                ? (float) hydrology.invoke(null, waterTable.getFloat(cell))
                : (float) hydrology.invoke(null, waterTable.getFloat(cell),
                        continentScale.getFloat(cell), continentModifier.getFloat(cell));
        // The mod places a water block at waterY inclusively; VSS stores its top face.
        return 1 + Math.max((int) scale.invoke(levels, ocean), (int) scale.invoke(levels, ocean + uplift));
    }
}
