package dev.xantha.vss.networking.server.compat;

import java.lang.reflect.*;
import java.util.*;

/** Reads infrastructure plans without running terrain generation or consuming its shared RNG. */
final class LostCityInfrastructurePlan {
    private static final ClassValue<Map<String, List<Method>>> METHODS = new ClassValue<>() {
        @Override protected Map<String, List<Method>> computeValue(Class<?> type) {
            Map<String, List<Method>> methods = new HashMap<>();
            for (Method method : type.getMethods())
                methods.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(method);
            methods.replaceAll((name, values) -> List.copyOf(values));
            return Map.copyOf(methods);
        }
    };
    // 0..3 rotations, 4 mirror X, 5 transpose X/Z (legacy north/south bridges).
    record Part(Object template, Object water, int y, int transform, List<Pillar> pillars) { }
    record Pillar(int x, int z, int width, int depth, int bottom, int top, char material) { }

    static List<Part> read(Object chunk, int cx, int cz, long seed, LostCityStreetPlan.Parts assets)
            throws ReflectiveOperationException {
        Object provider = field(chunk, "provider");
        if (provider == null) return List.of();
        Object worldStyle = call(provider, "getWorldStyle"), selector = call(worldStyle, "getPartSelector");
        Object profile = field(chunk, "profile");
        int ground = number(field(chunk, "groundLevel"), 64);
        int lx = number(call(chunk, "getHighwayXLevel"), -1), lz = number(call(chunk, "getHighwayZLevel"), -1);
        List<Part> parts = new ArrayList<>();
        // Ordinary bridges are generated only outside city chunks, before highways.
        if (!yes(call(chunk, "isCity")) && lx != 0 && lz != 0) {
            Object bridge = call(chunk, "hasXBridge", provider); int transform = 0;
            if (bridge == null) { bridge = call(chunk, "hasZBridge", provider); transform = 5; }
            if (bridge != null) {
                int y = number(field(profile, "GROUNDLEVEL"), ground)
                        + ("HIERARCHICAL_GRID_V1".equals(String.valueOf(call(provider, "getStreetGenerationMode"))) ? 0 : 1);
                List<Pillar> pillars = new ArrayList<>();
                Character support = support(bridge);
                if (support != null && yes(field(profile, "BRIDGE_SUPPORTS"))) {
                    String bridgeMethod = transform == 0 ? "hasXBridge" : "hasZBridge";
                    boolean min = call(call(chunk, transform == 0 ? "getXmin" : "getZmin"), bridgeMethod, provider) != null;
                    boolean max = call(call(chunk, transform == 0 ? "getXmax" : "getZmax"), bridgeMethod, provider) != null;
                    if (min && max) pillars.add(new Pillar(7, 7, 2, 2,
                            number(field(chunk, "waterLevel"), ground) - 10, ground + 1, support));
                    int bank = number(field(profile, "GROUNDLEVEL"), ground);
                    if (!min) pillars.add(new Pillar(0, 6, 1, 4, bank, bank + 1, support));
                    if (!max) pillars.add(new Pillar(15, 6, 1, 4, bank, bank + 1, support));
                }
                parts.add(new Part(bridge, null, y, transform, List.copyOf(pillars)));
            }
        }
        Object highways = call(selector, "highwayParts");
        if (lx >= 0 && lx == lz) {
            highway(parts, chunk, profile, worldStyle, highways, lx, 0, true,
                    call(chunk, "getXmax"), call(chunk, "getZmax"), ground, cx, cz, seed, assets);
        } else {
            if (lx >= 0) highway(parts, chunk, profile, worldStyle, highways, lx, 0, false,
                    call(chunk, "getZmin"), call(chunk, "getZmax"), ground, cx, cz, seed, assets);
            if (lz >= 0) highway(parts, chunk, profile, worldStyle, highways, lz, 1, false,
                    call(chunk, "getXmax"), call(chunk, "getXmax"), ground, cx, cz, seed, assets);
        }
        Object rail = call(chunk, "getRailInfo");
        String type = String.valueOf(call(rail, "getType"));
        if (!type.equals("null") && !type.equals("NONE")) {
            Object railways = call(selector, "railwayParts");
            int railLevel = number(call(rail, "getLevel"), 0), y = ground + railLevel * 6;
            String name = switch (type) {
                case "STATION_SURFACE", "STATION_EXTENSION_SURFACE" -> railLevel < number(call(chunk, "getCityLevel"), 0)
                        ? "stationUnderground" : "stationOpen";
                case "STATION_UNDERGROUND" -> "stationUndergroundStairs";
                case "STATION_EXTENSION_UNDERGROUND" -> "stationUnderground";
                case "RAILS_END_HERE" -> "railsHorizontalEnd";
                case "HORIZONTAL" -> "railsHorizontal";
                case "VERTICAL" -> "railsVertical";
                case "THREE_SPLIT" -> "rails3Split";
                case "GOING_DOWN_TWO_FROM_SURFACE", "GOING_DOWN_FURTHER" -> "railsDown2";
                case "GOING_DOWN_ONE_FROM_SURFACE" -> "railsDown1";
                case "DOUBLE_BEND" -> "railsBend";
                default -> "railsFlat";
            };
            Object names = call(railways, name);
            if ((type.equals("STATION_SURFACE") || type.equals("STATION_EXTENSION_SURFACE"))
                    && name.equals("stationOpen") && call(rail, "getPart") != null) names = call(rail, "getPart");
            Object main = selected(names, cx, cz, seed, assets), water = null;
            if (type.equals("VERTICAL") || type.equals("HORIZONTAL")
                    && !station(call(chunk, "getXmin")) && !station(call(chunk, "getXmax")))
                water = selected(call(railways, name + "Water"), cx, cz, seed, assets);
            boolean mirror = !type.startsWith("STATION") && !type.equals("HORIZONTAL")
                    && "EAST".equals(String.valueOf(call(rail, "getDirection")));
            if (main != null) parts.add(new Part(main, water, y, mirror ? 4 : 0, List.of()));
        }
        return List.copyOf(parts);
    }

    private static void highway(List<Part> result, Object chunk, Object profile, Object worldStyle, Object types,
                                int level, int transform, boolean crossing, Object a, Object b, int ground,
                                int cx, int cz, long seed, LostCityStreetPlan.Parts assets) throws ReflectiveOperationException {
        // Non-city tunnel selection needs generated heightmaps. Keep the exterior bridge template;
        // prediction terrain naturally hides it where buried instead of generating chunks here.
        boolean city = yes(call(chunk, "isCity"));
        String kind = city && number(call(chunk, "getCityLevel"), 0) > level ? "tunnel"
                : city && yes(call(a, "isCity")) && yes(call(b, "isCity"))
                && number(call(a, "getCityLevel"), 0) >= level && number(call(b, "getCityLevel"), 0) >= level ? "open" : "bridge";
        Object template = selected(call(types, kind + (crossing ? "Bi" : "")), cx, cz, seed, assets);
        if (template == null) return;
        int y = ground + level * 6;
        Character support = support(template);
        if (support == null && call(worldStyle, "getHighwaySupportPart") == null) {
            Object fallback = call(worldStyle, "getHighwaySupport");
            if (fallback instanceof Character c) support = c;
        }
        List<Pillar> pillars = new ArrayList<>();
        if (support != null && yes(field(profile, "HIGHWAY_SUPPORTS"))) {
            pillars.add(new Pillar(0, 0, 1, 1, y - 40, y, support));
            pillars.add(new Pillar(0, 15, 1, 1, y - 40, y, support));
        }
        result.add(new Part(template, null, y, transform, List.copyOf(pillars)));
    }

    private static Character support(Object part) throws ReflectiveOperationException {
        Object value = part instanceof LostCityLegacyPalettes.PreviewPart preview ? preview.support() : call(part, "getMetaChar", "support");
        return value instanceof Character c ? c : null;
    }
    private static boolean station(Object chunk) throws ReflectiveOperationException {
        return String.valueOf(call(call(chunk, "getRailInfo"), "getType")).startsWith("STATION");
    }
    private static Object selected(Object names, int cx, int cz, long seed, LostCityStreetPlan.Parts assets)
            throws ReflectiveOperationException {
        if (names instanceof String name) return assets.get(name);
        if (!(names instanceof List<?> values) || values.isEmpty()) return null;
        return assets.get(String.valueOf(values.get(new Random(seed + cx * 132_897_987_541L + cz * 341_873_128_712L).nextInt(values.size()))));
    }
    static int tx(int x, int z, int transform) {
        return switch (transform) { case 1 -> 15 - z; case 2, 4 -> 15 - x; case 3, 5 -> z; default -> x; };
    }
    static int tz(int x, int z, int transform) {
        return switch (transform) { case 1, 5 -> x; case 2 -> 15 - z; case 3 -> 15 - x; default -> z; };
    }
    private static Object call(Object owner, String name, Object... args) throws ReflectiveOperationException {
        if (owner == null) return null;
        for (Method m : METHODS.get(owner.getClass()).getOrDefault(name, List.of())) if (m.getParameterCount() == args.length) {
            boolean fits = true;
            for (int i = 0; i < args.length; i++) if (!m.getParameterTypes()[i].isInstance(args[i])) fits = false;
            if (fits) return m.invoke(owner, args);
        }
        return null;
    }
    private static Object field(Object owner, String name) throws IllegalAccessException {
        if (owner == null) return null;
        try { return owner.getClass().getField(name).get(owner); } catch (NoSuchFieldException absent) { return null; }
    }
    private static boolean yes(Object value) { return Boolean.TRUE.equals(value); }
    private static int number(Object value, int fallback) { return value instanceof Integer n ? n : fallback; }
}
