package dev.xantha.vss.networking.server.compat;

import java.io.InputStream;
import java.lang.reflect.*;
import java.util.*;

/** Read-only surface planning. Never consumes the terrain generator's shared random or runs decoration. */
final class LostCityStreetPlan {
    interface Parts { Object get(String name) throws ReflectiveOperationException; }
    record Part(Object template, int y, int turns, Object paletteOwner, boolean clearAir) { }
    record Layout(boolean surface, boolean park, int elevation, int connections, int elevatedNeighbours,
                  boolean parkBorder, List<Part> parts) { }
    record Road(String shape, int turns) { }
    private static final String FEATURE = "mcjty/lostcities/worldgen/LostCityTerrainFeature.class";

    private LostCityStreetPlan() { }

    // 7.4+ reselect non-elevated street types using this coordinate seed during
    // generation; 7.3 keeps the selected planner type. Check the shipped class
    // rather than assuming a mod-version string or changing the shared planner.
    static boolean reselectsStreets(ClassLoader loader) throws java.io.IOException {
        try (InputStream in = loader.getResourceAsStream(FEATURE)) {
            if (in == null) return false;
            byte[] bytes = in.readNBytes(1_048_576);
            long seed = 45_555_558_379L;
            for (int i = 0; i + 8 <= bytes.length; i++) {
                long value = 0;
                for (int j = 0; j < 8; j++) value = value << 8 | bytes[i + j] & 255L;
                if (value == seed) return true;
            }
            return false;
        }
    }

    static Layout read(Object chunk, Object style, int cx, int cz, long seed,
                       boolean reselect, Parts assets) throws ReflectiveOperationException {
        boolean elevated = yes(call(chunk, "isElevatedParkSection"));
        String type = String.valueOf(field(chunk, "streetType"));
        if (!elevated && reselect && !yes(call(chunk, "isHierarchicalOpen"))) {
            // In supported releases NORMAL/FULL precede PARK and the sentinel.
            type = new Random(cz * 155_557_723L + cx * 45_555_558_379L).nextInt(2) == 0 ? "NORMAL" : "FULL";
        }
        if (elevated) type = "PARK";
        Object profile = field(chunk, "profile"), effective = call(chunk, "getEffectiveCitySettings");
        boolean elevation = setting(effective, "parkElevation", style, "getParkElevation", profile, "PARK_ELEVATION", false);
        boolean border = setting(effective, "parkBorder", style, "getParkBorder", profile, "PARK_BORDER", true);
        int connections = 0, roadConnections = 0, adjacent = 0;
        Object[] neighbours = {call(chunk, "getXmin"), call(chunk, "getXmax"), call(chunk, "getZmin"), call(chunk, "getZmax")};
        boolean extendsRoad = road(chunk), canPlace = canPlace(chunk);
        for (int d = 0; d < 4; d++) {
            Object other = neighbours[d];
            if (other == null) continue;
            boolean connected = extendsRoad && road(other)
                    && number(call(chunk, "getCityLevel"), 0) == number(call(other, "getCityLevel"), 0);
            if (connected) roadConnections |= 1 << d;
            if (connected && (!yes(call(chunk, "isPrimaryRoad")) || yes(call(other, "isPrimaryRoad")))
                    || bridge(other, d < 2 ? "hasXBridge" : "hasZBridge", field(chunk, "provider"))) connections |= 1 << d;
            if (yes(call(other, "isElevatedParkSection"))) adjacent |= 1 << d;
        }
        // Corner borders require the diagonal park as well as both edge parks.
        for (int d = 0; d < 4; d++) {
            Object edge = neighbours[d < 2 ? 0 : 1];
            Object corner = call(edge, d % 2 == 0 ? "getZmin" : "getZmax");
            if (yes(call(corner, "isElevatedParkSection"))) adjacent |= 1 << (d + 4);
        }
        List<Part> parts = new ArrayList<>();
        boolean park = "PARK".equals(type);
        if (canPlace) {
            String roadType = String.valueOf(field(chunk, "plannedRoadType"));
            Object streetParts = call(style, "PRIMARY".equals(roadType) ? "getLargeStreetParts"
                    : "TERTIARY".equals(roadType) ? "getTertiaryStreetParts" : "getStreetParts");
            Object slope = call(chunk, "getStreetSlopeDirection");
            if (!park && streetParts != null) {
                Road road = "FULL".equals(type) ? new Road("full", 0) : slope == null ? road(connections)
                        : new Road("stair", turns(call(slope, "getRotation")));
                Object main = selected(call(streetParts, road.shape), assets, seed, cx, cz);
                add(parts, main, 0, road.turns, chunk, false);
                if (main != null && "NORMAL".equals(type) && slope == null && yes(call(chunk, "isPrimaryRoad"))) {
                    for (int d = 0; d < 4; d++) if ((roadConnections & 1 << d) != 0
                            && !yes(call(neighbours[d], "isPrimaryRoad")))
                        add(parts, selected(call(streetParts, "connector"), assets, seed, cx, cz), 0,
                                new int[]{0, 2, 1, 3}[d], chunk, false);
                }
            }
            if (slope == null) add(parts, field(chunk, park ? "parkType" : "fountainType"), 1, 0, chunk, true);
            if (slope == null && "NORMAL".equals(type)) {
                for (int d = 0; d < 4; d++) {
                    Object other = neighbours[d];
                    if (other != null && front(chunk, other))
                        add(parts, field(other, "frontType"), 1, new int[]{0, 2, 1, 3}[d], other, true);
                }
            }
        }
        return new Layout(canPlace, park, canPlace && elevated && elevation ? 1 : 0, connections, elevated ? adjacent : 0,
                border, List.copyOf(parts));
    }

    private static int turns(Object rotation) {
        return switch (String.valueOf(rotation)) { case "ROTATE_90" -> 1; case "ROTATE_180" -> 2; case "ROTATE_270" -> 3; default -> 0; };
    }
    private static Object selected(Object names, Parts assets, long seed, int cx, int cz) throws ReflectiveOperationException {
        if (!(names instanceof List<?> list) || list.isEmpty()) return null;
        // Road variants use a mutable generator RNG and are not retained in
        // the plan. Use a stable member of the correct style/shape instead.
        return assets.get(String.valueOf(list.get(new Random(seed + cx * 132_897_987_541L
                + cz * 341_873_128_712L).nextInt(list.size()))));
    }

    static Road road(int c) {
        boolean w = (c & 1) != 0, e = (c & 2) != 0, n = (c & 4) != 0, s = (c & 8) != 0;
        return switch (Integer.bitCount(c)) {
            case 0 -> new Road("none", 0);
            case 1 -> new Road("end", w ? 0 : e ? 2 : n ? 1 : 3);
            case 2 -> w == e || n == s ? new Road("straight", w ? 0 : e ? 2 : n ? 1 : 3)
                    : new Road("bend", w && n ? 0 : w && s ? 3 : e && n ? 1 : 2);
            case 3 -> new Road("t", !w ? 1 : !e ? 3 : !n ? 2 : 0);
            default -> new Road("all", 0);
        };
    }

    private static void add(List<Part> out, Object part, int y, int turns, Object palette, boolean clear) {
        if (part != null) out.add(new Part(part, y, turns, palette, clear));
    }
    private static boolean front(Object street, Object building) throws ReflectiveOperationException {
        if (call(building, "getBuildingId") == null || field(building, "frontType") == null) return false;
        int level = number(call(street, "getCityLevel"), 0), otherLevel = number(call(building, "getCityLevel"), 0);
        if (level >= otherLevel + number(call(building, "getNumFloors"), 0)) return false;
        String rail = String.valueOf(call(call(street, "getRailInfo"), "getType"));
        if ("STATION_UNDERGROUND".equals(rail) || "GOING_DOWN_ONE_FROM_SURFACE".equals(rail)
                || number(call(street, "getMaxHighwayLevel"), -1) >= 0) return false;
        int local = level - otherLevel;
        if (!yes(callWith(building, "isValidFloor", local))) return true;
        return !yes(callWith(callWith(building, "getFloor", local), "getMetaBoolean", "dontconnect"));
    }
    private static boolean road(Object chunk) throws ReflectiveOperationException {
        Object result = call(chunk, "doesRoadExtendTo");
        return result == null ? yes(call(chunk, "isCity")) && call(chunk, "getBuildingId") == null : yes(result);
    }
    private static boolean canPlace(Object chunk) throws ReflectiveOperationException {
        int level = number(call(chunk, "getCityLevel"), 0);
        if (number(call(chunk, "getHighwayXLevel"), -1) == level || number(call(chunk, "getHighwayZLevel"), -1) == level) return false;
        Object rail = call(chunk, "getRailInfo");
        String type = String.valueOf(call(rail, "getType"));
        return !"STATION_SURFACE".equals(type) && (!"STATION_EXTENSION_SURFACE".equals(type)
                || number(call(rail, "getLevel"), -1) < level);
    }
    private static boolean bridge(Object owner, String name, Object provider) throws ReflectiveOperationException {
        return provider != null && callWith(owner, name, provider) != null;
    }
    private static boolean setting(Object effective, String method, Object style, String styleMethod,
                                   Object profile, String name, boolean fallback) throws ReflectiveOperationException {
        Object value = call(effective, method);
        if (!(value instanceof Boolean)) value = call(style, styleMethod);
        if (!(value instanceof Boolean)) value = field(profile, name);
        return value instanceof Boolean b ? b : fallback;
    }
    private static int number(Object value, int fallback) { return value instanceof Integer n ? n : fallback; }
    private static boolean yes(Object value) { return Boolean.TRUE.equals(value); }
    private static Object callWith(Object owner, String name, Object arg) throws ReflectiveOperationException {
        if (owner == null || arg == null) return null;
        for (Method method : owner.getClass().getMethods()) if (method.getName().equals(name)
                && method.getParameterCount() == 1 && (method.getParameterTypes()[0].isInstance(arg)
                || method.getParameterTypes()[0] == int.class && arg instanceof Integer)) return method.invoke(owner, arg);
        return null;
    }
    private static Object call(Object owner, String name) throws ReflectiveOperationException {
        if (owner == null) return null;
        try { return owner.getClass().getMethod(name).invoke(owner); } catch (NoSuchMethodException missing) { return null; }
    }
    private static Object field(Object owner, String name) throws IllegalAccessException {
        if (owner == null) return null;
        try { return owner.getClass().getField(name).get(owner); } catch (NoSuchFieldException missing) { return null; }
    }
}
