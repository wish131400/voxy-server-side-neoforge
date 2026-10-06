package dev.xantha.vss.networking.server.compat;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Reads selected templates/palettes only; it never invokes the chunk generator or decoration. */
final class LostCityPlanningReader {
    private static final int FLOOR_HEIGHT = 6, MAX_TEMPLATE_HEIGHT = 64;
    private static final Map<TemplateKey, Template> TEMPLATES = new LinkedHashMap<>(64, .75f, true);
    private static long templateBytes;
    private static final Map<SurfaceKey, SurfaceModels> SURFACES = new LinkedHashMap<>(32, .75f, true);
    private static long surfaceBytes;
    private record TemplateKey(List<String> slices, List<Integer> materials, int width, int depth) { }
    private record SurfacePartKey(TemplateKey template, int y, int turns, boolean clearAir) { }
    private record SurfaceKey(List<Integer> base, List<SurfacePartKey> parts) { }
    private record SurfaceModels(LostCityPreview.Model detailed, LostCityPreview.Model distant) { }
    private record InfrastructureKey(TemplateKey template, int transform) { }
    private static final Map<InfrastructureKey, SurfaceModels> INFRASTRUCTURE = new LinkedHashMap<>(32, .75f, true);
    private static long infrastructureBytes;
    private record InfrastructureInput(TemplateInput template, TemplateInput water, int y, int transform,
                                       List<LostCityInfrastructurePlan.Pillar> pillars, List<Integer> supportStates) { }
    private record Template(int[] states, int height, LostCityPreview.Model model) { }
    private record Plan(int kind, int ground, boolean flatten, int surface, int pavement,
                        int width, int connections, List<Object> parts, List<Object> extra, Object palette) { }
    private record CityPlanning(Object style, LostCityStreetPlan.Layout street) { }

    private LostCityPlanningReader() { }

    static List<LostCityPreview.Chunk> query(Object info, ServerLevel level, int rx, int rz)
            throws ReflectiveOperationException {
        ClassLoader loader = info.getClass().getClassLoader();
        Class<?> api = Class.forName("mcjty.lostcities.api.ILostCityInformation", false, loader);
        Method getChunk = api.getMethod("getChunkInfo", int.class, int.class);
        Method realHeight = api.getMethod("getRealHeight", int.class);
        Class<?> chunkApi = Class.forName("mcjty.lostcities.api.ILostChunkInfo", false, loader);
        Method building = chunkApi.getMethod("getBuildingId"), isCity = chunkApi.getMethod("isCity");
        Method cityLevel = chunkApi.getMethod("getCityLevel"), floors = chunkApi.getMethod("getNumFloors");
        Object plannerLock = level == null ? null : LostCityPlannerAccess.lock(loader, level.dimension());
        LostCityLegacyPalettes legacy = plannerLock instanceof Class<?> ? new LostCityLegacyPalettes(loader, level) : null;
        boolean reselect;
        try { reselect = LostCityStreetPlan.reselectsStreets(loader); }
        catch (java.io.IOException failure) { throw new ReflectiveOperationException("Lost Cities street contract", failure); }
        Map<Long, Object> neighbours = new HashMap<>();
        List<LostCityPreview.Chunk> result = new ArrayList<>(64);
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int cx = rx * 8 + x, cz = rz * 8 + z;
            Object chunk = LostCityPlannerAccess.withLegacyMonitor(plannerLock,
                    () -> chunk(info, getChunk, neighbours, cx, cz));
            // Modern planners can wait for another construction task. Do not hold
            // their memoization lock while requesting that task or its neighbours.
            CityPlanning planning = LostCityPlannerAccess.withLegacyMonitor(plannerLock, () -> {
                boolean city = Boolean.TRUE.equals(isCity.invoke(chunk));
                boolean hasBuilding = building.invoke(chunk) != null;
                if (city && !hasBuilding) {
                    chunk(info, getChunk, neighbours, cx - 1, cz);
                    chunk(info, getChunk, neighbours, cx + 1, cz);
                    chunk(info, getChunk, neighbours, cx, cz - 1);
                    chunk(info, getChunk, neighbours, cx, cz + 1);
                }
                // getCityStyle can also enter the modern staged planner.
                Object style = hasBuilding || city ? optional(chunk, "getCityStyle") : null;
                LostCityStreetPlan.Layout street = !hasBuilding && city
                        ? LostCityStreetPlan.read(chunk, style, cx, cz, level == null ? 0 : level.getSeed(), reselect,
                                name -> part(loader, level, legacy, name)) : null;
                return new CityPlanning(style, street);
            });
            Object style = planning.style();
            LostCityStreetPlan.Layout street = planning.street();
            // Resolve bridge/rail neighbours before entering modern memoization locks.
            var infrastructure = LostCityPlannerAccess.withLegacyMonitor(plannerLock,
                    () -> LostCityInfrastructurePlan.read(chunk, cx, cz, level == null ? 0 : level.getSeed(),
                            name -> part(loader, level, legacy, name)));
            List<InfrastructureInput> inputs;
            Snapshot snapshot;
            if (plannerLock != null) synchronized (plannerLock) {
                snapshot = snapshot(info, chunk, level, cx, cz, getChunk, realHeight, building, isCity,
                        cityLevel, floors, neighbours, legacy, style, street);
                inputs = infrastructureInputs(infrastructure, chunk, level, legacy);
            } else {
                snapshot = snapshot(info, chunk, level, cx, cz, getChunk, realHeight, building, isCity,
                        cityLevel, floors, neighbours, legacy, style, street);
                inputs = infrastructureInputs(infrastructure, chunk, level, legacy);
            }
            List<LostCityPreview.Overlay> overlays = infrastructure(inputs);
            Plan plan = snapshot.plan;
            if (plan.kind == 0) { result.add(LostCityPreview.EMPTY.withOverlays(overlays)); continue; }
            if (street != null) {
                SurfaceModels model = street.surface() ? surface(snapshot) : null;
                result.add(new LostCityPreview.Chunk(plan.kind, plan.ground, plan.flatten, plan.surface,
                        plan.pavement, plan.width, plan.connections,
                        model == null ? List.of() : List.of(new LostCityPreview.Placement(0, model.detailed)),
                        model == null ? null : model.distant, overlays));
                continue;
            }
            List<LostCityPreview.Placement> placements = new ArrayList<>();
            for (int f = 0; f < plan.parts.size(); f++) {
                Template main = template(snapshot.parts.get(f));
                Template extra = template(snapshot.extra.get(f));
                if (main == null && extra == null) continue;
                LostCityPreview.Model model = main == null ? extra.model : main.model;
                if (main != null && extra != null) {
                    int h = Math.max(main.height, extra.height);
                    int[] merged = new int[h * 256]; Arrays.fill(merged, -1);
                    System.arraycopy(main.states, 0, merged, 0, main.states.length);
                    for (int i = 0; i < extra.states.length; i++) if (extra.states[i] != -1) merged[i] = extra.states[i];
                    model = LostCityExterior.mesh(merged, h, 1);
                }
                int y = f * FLOOR_HEIGHT;
                if (y + model.height() <= LostCityPreview.MAX_HEIGHT)
                    placements.add(new LostCityPreview.Placement(y, model));
            }
            var silhouette = LostCityExterior.silhouette(placements);
            result.add(new LostCityPreview.Chunk(plan.kind, plan.ground, plan.flatten, plan.surface,
                    plan.pavement, plan.width, plan.connections, placements, silhouette, overlays));
        }
        return LostCityPreview.bounded(result);
    }

    private static Plan plan(Object info, Object chunk, ServerLevel level, int cx, int cz, Method getChunk,
                             Method realHeight, Method building, Method isCity, Method cityLevel, Method floors,
                             Map<Long, Object> neighbours, LostCityLegacyPalettes legacy, Object style,
                             LostCityStreetPlan.Layout street) throws ReflectiveOperationException {
        boolean hasBuilding = building.invoke(chunk) != null, city = Boolean.TRUE.equals(isCity.invoke(chunk));
        if (!hasBuilding && !city) return new Plan(0, 0, false, 0, 0, 0, 0, List.of(), List.of(), null);
        int kind = hasBuilding ? LostCityPreview.BUILDING : LostCityPreview.ROAD;
        int ground = optionalInt(chunk, "getCityGroundLevel", (Integer) realHeight.invoke(info, cityLevel.invoke(chunk)));
        Object profile = field(chunk, "profile"), palette = legacy != null ? privatePalette(chunk, legacy, hasBuilding)
                : optional(chunk, "getCompiledPalette");
        boolean flatten = profile != null && (Boolean.TRUE.equals(optional(profile, "isDefault"))
                || Boolean.TRUE.equals(optional(profile, "isVoidSpheres")));
        if (street != null && street.park()) kind = LostCityPreview.PARK;
        if (street != null) ground += street.elevation();
        int pavement = material(palette, style == null ? null : optional(style, "getStreetBaseBlock"), Blocks.STONE);
        int surface = material(palette, style == null ? null : optional(style,
                kind == LostCityPreview.PARK ? "getGrassBlock" : "getStreetBlock"),
                kind == LostCityPreview.PARK ? Blocks.GRASS_BLOCK : Blocks.GRAY_CONCRETE);
        int width = style == null ? 8 : Math.max(2, Math.min(16, optionalInt(style, "getStreetWidth", 8)));
        if (hasBuilding) surface = pavement;
        int connections = street == null ? 0 : street.connections();
        List<Object> parts = new ArrayList<>(), extra = new ArrayList<>();
        if (hasBuilding && palette != null) {
            Object primary = field(chunk, "floorTypes"), secondary = field(chunk, "floorTypes2");
            Object[] floorParts = primary instanceof Object[] values ? values.clone() : null;
            Object[] extraParts = secondary instanceof Object[] values ? values.clone() : null;
            Object offset = field(chunk, "cellars");
            int cellarCount = offset instanceof Integer n ? n : 0;
            Method floor = method(chunk, "getFloor", int.class), second = method(chunk, "getFloorPart2", int.class);
            int count = Math.max(0, Math.min(127, (Integer) floors.invoke(chunk)));
            int maxY = level == null ? ground + LostCityPreview.MAX_HEIGHT : level.getMaxBuildHeight() - 2 - FLOOR_HEIGHT;
            // Do not call getBuildingBottomHeight(): it mutates the planner's cellar/floor counts.
            while (count > 0 && ground + count * FLOOR_HEIGHT >= maxY) count--;
            if (floorParts != null) {
                // Generation can clamp cellar counts without taking the planner
                // monitor. Use one offset and bounded array snapshots throughout.
                for (int f = 0; f <= count; f++) {
                    int index = f + cellarCount;
                    if (index < 0 || index >= floorParts.length) break;
                    parts.add(floorParts[index]);
                    extra.add(extraParts != null && index < extraParts.length ? extraParts[index] : null);
                }
            } else if (floor != null) for (int f = 0; f <= count; f++) {
                parts.add(floor.invoke(chunk, f)); extra.add(second == null ? null : second.invoke(chunk, f));
            }
        }
        return new Plan(kind, ground, flatten, surface, pavement, width, connections, parts, extra, palette);
    }

    private record TemplateInput(TemplateKey key, String[] slices, Map<Character, Integer> states, int width, int depth) { }
    private record SurfaceInput(TemplateInput template, int y, int turns, boolean clearAir) { }
    private record Snapshot(Plan plan, List<TemplateInput> parts, List<TemplateInput> extra,
                            List<Integer> base, List<SurfaceInput> surface) { }

    private static List<InfrastructureInput> infrastructureInputs(List<LostCityInfrastructurePlan.Part> parts,
            Object chunk, ServerLevel level, LostCityLegacyPalettes legacy) throws ReflectiveOperationException {
        if (parts.isEmpty()) return List.of();
        Object palette = legacy != null ? privatePalette(chunk, legacy, optional(chunk, "getBuildingId") != null)
                : optional(chunk, "getCompiledPalette");
        List<InfrastructureInput> result = new ArrayList<>();
        for (var part : parts) {
            TemplateInput input = templateInput(part.template(), palette, level, legacy);
            if (input == null) continue;
            List<Integer> supports = new ArrayList<>();
            Object local = mergedPalette(part.template(), palette, level, legacy);
            for (var pillar : part.pillars()) supports.add(material(local, pillar.material(), Blocks.STONE));
            result.add(new InfrastructureInput(input, templateInput(part.water(), palette, level, legacy),
                    part.y(), part.transform(), part.pillars(), List.copyOf(supports)));
        }
        return result;
    }

    private static List<LostCityPreview.Overlay> infrastructure(List<InfrastructureInput> parts) {
        List<LostCityPreview.Overlay> overlays = new ArrayList<>();
        for (var part : parts) {
            SurfaceModels model = infrastructureModel(part.template, part.transform);
            SurfaceModels water = part.water == null ? null : infrastructureModel(part.water, part.transform);
            overlays.add(new LostCityPreview.Overlay(part.y, model.detailed, model.distant,
                    water == null ? null : water.detailed, water == null ? null : water.distant));
            for (int p = 0; p < part.pillars.size(); p++) {
                var pillar = part.pillars.get(p); int height = pillar.top() - pillar.bottom();
                if (height <= 0 || height > LostCityPreview.MAX_HEIGHT) continue;
                // Small six-face boxes remain small at every LOD; never turn pillars into a filled bridge skirt.
                int x0 = LostCityInfrastructurePlan.tx(pillar.x(), pillar.z(), part.transform);
                int z0 = LostCityInfrastructurePlan.tz(pillar.x(), pillar.z(), part.transform);
                int x1 = LostCityInfrastructurePlan.tx(pillar.x() + pillar.width() - 1, pillar.z() + pillar.depth() - 1, part.transform);
                int z1 = LostCityInfrastructurePlan.tz(pillar.x() + pillar.width() - 1, pillar.z() + pillar.depth() - 1, part.transform);
                var shape = LostCityExterior.box(Math.min(x0, x1), Math.min(z0, z1),
                        Math.abs(x1 - x0) + 1, Math.abs(z1 - z0) + 1, height, part.supportStates.get(p));
                overlays.add(new LostCityPreview.Overlay(pillar.bottom(), shape, shape, null, null));
            }
        }
        return List.copyOf(overlays);
    }

    private static SurfaceModels infrastructureModel(TemplateInput input, int transform) {
        var key = new InfrastructureKey(input.key, transform);
        synchronized (INFRASTRUCTURE) { var cached = INFRASTRUCTURE.get(key); if (cached != null) return cached; }
        Template template = template(input); int[] states = new int[template.states.length]; Arrays.fill(states, -1);
        for (int y = 0; y < template.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int state = template.states[y * 256 + z * 16 + x];
            if (state >= 0) state = transformedState(state, transform);
            states[y * 256 + LostCityInfrastructurePlan.tz(x, z, transform) * 16
                    + LostCityInfrastructurePlan.tx(x, z, transform)] = state;
        }
        var result = new SurfaceModels(LostCityExterior.infrastructure(states, template.height, 1, 4096),
                LostCityExterior.infrastructure(states, template.height, 4, 512));
        synchronized (INFRASTRUCTURE) {
            var old = INFRASTRUCTURE.put(key, result);
            infrastructureBytes += infrastructureBytes(key, result) - (old == null ? 0 : infrastructureBytes(key, old));
            while (INFRASTRUCTURE.size() > 512 || infrastructureBytes > 8L * 1024 * 1024) {
                var iterator = INFRASTRUCTURE.entrySet().iterator(); var entry = iterator.next(); iterator.remove();
                infrastructureBytes -= infrastructureBytes(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static long infrastructureBytes(InfrastructureKey key, SurfaceModels models) {
        return 256L + key.template.slices.stream().mapToLong(s -> 48L + s.length() * 2L).sum()
                + key.template.materials.size() * 24L + (models.detailed.quadCount() + models.distant.quadCount()) * 12L;
    }

    private static int transformedState(int id, int transform) {
        if (transform == 0) return id;
        if (transform <= 3) return rotatedState(id, transform);
        BlockState state = Block.stateById(id).mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK);
        if (transform == 5) state = state.rotate(net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90);
        return Block.getId(state);
    }

    private static Snapshot snapshot(Object info, Object chunk, ServerLevel level, int cx, int cz, Method getChunk,
                                     Method realHeight, Method building, Method isCity, Method cityLevel, Method floors,
                                     Map<Long, Object> neighbours, LostCityLegacyPalettes legacy, Object style,
                                     LostCityStreetPlan.Layout street) throws ReflectiveOperationException {
        Plan plan = plan(info, chunk, level, cx, cz, getChunk, realHeight, building, isCity, cityLevel, floors, neighbours, legacy, style, street);
        List<TemplateInput> parts = new ArrayList<>(), extra = new ArrayList<>();
        for (int f = 0; f < plan.parts.size(); f++) {
            parts.add(templateInput(plan.parts.get(f), plan.palette, level, legacy));
            extra.add(templateInput(plan.extra.get(f), plan.palette, level, legacy));
        }
        List<Integer> base = new ArrayList<>(); List<SurfaceInput> surface = new ArrayList<>();
        if (street != null && street.surface()) {
            int border = material(plan.palette, style == null ? null : optional(style, "getStreetBlock"), Blocks.GRAY_CONCRETE);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                boolean edge = x == 0 || x == 15 || z == 0 || z == 15;
                boolean connected = parkBorderConnected(x, z, street.elevatedNeighbours());
                base.add(street.park() ? edge && street.parkBorder() && !connected ? border : plan.surface : plan.pavement);
            }
            for (var placement : street.parts()) {
                Object owner = placement.paletteOwner();
                Object palette = owner == chunk ? plan.palette : legacy != null ? privatePalette(owner, legacy, true) : optional(owner, "getCompiledPalette");
                TemplateInput input = templateInput(placement.template(), palette, level, legacy);
                if (input != null) surface.add(new SurfaceInput(input, placement.y(), placement.turns(), placement.clearAir()));
            }
        }
        return new Snapshot(plan, parts, extra, List.copyOf(base), List.copyOf(surface));
    }

    private static boolean parkBorderConnected(int x, int z, int c) {
        int edges = (x == 0 ? 1 : x == 15 ? 2 : 0) | (z == 0 ? 4 : z == 15 ? 8 : 0);
        if ((c & edges) != edges) return false;
        if (Integer.bitCount(edges) < 2) return true;
        int corner = (x == 0 ? 0 : 2) + (z == 0 ? 0 : 1);
        return (c & 1 << (corner + 4)) != 0;
    }

    private static Object part(ClassLoader loader, ServerLevel level, LostCityLegacyPalettes legacy, String name)
            throws ReflectiveOperationException {
        if (level == null) return null;
        if (legacy != null) return legacy.part(name);
        Object registry = Class.forName("mcjty.lostcities.worldgen.lost.cityassets.AssetRegistries", false, loader)
                .getField("PARTS").get(null);
        return registry.getClass().getMethod("getOrWarn", CommonLevelAccessor.class, String.class).invoke(registry, level, name);
    }

    private static SurfaceModels surface(Snapshot snapshot) {
        List<SurfacePartKey> parts = snapshot.surface.stream().map(p -> new SurfacePartKey(p.template.key, p.y, p.turns, p.clearAir)).toList();
        SurfaceKey key = new SurfaceKey(snapshot.base, parts);
        synchronized (SURFACES) { var cached = SURFACES.get(key); if (cached != null) return cached; }
        int height = snapshot.surface.stream().mapToInt(p -> p.y + p.template.slices.length).max().orElse(1);
        int[] states = new int[height * 256]; Arrays.fill(states, -1);
        for (int i = 0; i < 256; i++) states[i] = snapshot.base.get(i);
        for (SurfaceInput part : snapshot.surface) {
            Template t = template(part.template);
            for (int y = 0; y < t.height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int state = t.states[y * 256 + z * 16 + x];
                if (state == -1 || state == -2 && !part.clearAir) continue;
                int px = switch (part.turns) { case 1 -> 15 - z; case 2 -> 15 - x; case 3 -> z; default -> x; };
                int pz = switch (part.turns) { case 1 -> x; case 2 -> 15 - z; case 3 -> 15 - x; default -> z; };
                if (state >= 0 && part.turns != 0) state = rotatedState(state, part.turns);
                states[(y + part.y) * 256 + pz * 16 + px] = state;
            }
        }
        var model = new SurfaceModels(LostCityExterior.surface(states, height, 1),
                LostCityExterior.surface(states, height, 4, 512));
        synchronized (SURFACES) {
            var old = SURFACES.put(key, model);
            surfaceBytes += surfaceBytes(key, model) - (old == null ? 0 : surfaceBytes(key, old));
            while (SURFACES.size() > 512 || surfaceBytes > 8L * 1024 * 1024) {
                var it = SURFACES.entrySet().iterator(); var entry = it.next(); it.remove();
                surfaceBytes -= surfaceBytes(entry.getKey(), entry.getValue());
            }
        }
        return model;
    }

    private static int rotatedState(int state, int turns) {
        var rotation = switch (turns) {
            case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            default -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
        };
        return Block.getId(Block.stateById(state).rotate(rotation));
    }
    private static long surfaceBytes(SurfaceKey key, SurfaceModels model) {
        long bytes = 256L + key.base.size() * 24L + (model.detailed.quadCount() + model.distant.quadCount()) * 12L;
        for (var part : key.parts) {
            bytes += 128L + part.template.materials.size() * 24L;
            for (String slice : part.template.slices) bytes += 48L + slice.length() * 2L;
        }
        return bytes;
    }

    // Legacy getCompiledPalette publishes a partially initialized lazy value to
    // normal generation. Build a private palette instead of writing that field.
    private static Object privatePalette(Object chunk, LostCityLegacyPalettes legacy, boolean hasBuilding) throws ReflectiveOperationException {
        ClassLoader loader = chunk.getClass().getClassLoader();
        Class<?> paletteType = Class.forName("mcjty.lostcities.worldgen.lost.cityassets.Palette", false, loader);
        Class<?> compiled = Class.forName("mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette", false, loader);
        Object base = declared(chunk, "palette"), building = field(chunk, "buildingType");
        Object local = !hasBuilding || building == null ? null : legacy.local(building);
        Object palettes = Array.newInstance(paletteType, local == null ? 1 : 2);
        Array.set(palettes, 0, base);
        if (local != null) Array.set(palettes, 1, local);
        return compiled.getConstructor(palettes.getClass()).newInstance(palettes);
    }

    private static Object declared(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static Object localPalette(Object part, CommonLevelAccessor level, LostCityLegacyPalettes legacy) throws ReflectiveOperationException {
        return legacy != null ? legacy.local(part)
                : part.getClass().getMethod("getLocalPalette", CommonLevelAccessor.class).invoke(part, level);
    }

    private static TemplateInput templateInput(Object part, Object palette, CommonLevelAccessor level, LostCityLegacyPalettes legacy) throws ReflectiveOperationException {
        if (part == null || palette == null) return null;
        String[] slices = ((String[]) part.getClass().getMethod("getSlices").invoke(part)).clone();
        int width = (Integer) part.getClass().getMethod("getXSize").invoke(part);
        int depth = (Integer) part.getClass().getMethod("getZSize").invoke(part);
        if (slices.length > MAX_TEMPLATE_HEIGHT || width < 1 || width > 16 || depth < 1 || depth > 16)
            throw new IllegalArgumentException("Unsupported Lost Cities part dimensions");
        // Packs use zero-height roof parts as deliberate placeholders. There is
        // nothing to mesh, but the remaining floors and chunks are still valid.
        if (slices.length == 0) return null;
        palette = mergedPalette(part, palette, level, legacy);
        SortedSet<Character> characters = new TreeSet<>();
        for (String slice : slices) {
            if (slice.length() != width * depth) throw new IllegalArgumentException("Lost Cities slice size");
            for (int i = 0; i < slice.length(); i++) characters.add(slice.charAt(i));
        }
        Map<Character, Integer> states = new HashMap<>(); List<Integer> materials = new ArrayList<>();
        Method get = palette.getClass().getMethod("get", char.class, Random.class);
        for (char c : characters) {
            BlockState state = (BlockState) get.invoke(palette, c, new Random(c));
            int id = previewState(state);
            states.put(c, id); materials.add((int) c); materials.add(id);
        }
        TemplateKey key = new TemplateKey(List.of(slices), List.copyOf(materials), width, depth);
        return new TemplateInput(key, slices, Map.copyOf(states), width, depth);
    }

    private static Object mergedPalette(Object part, Object palette, CommonLevelAccessor level, LostCityLegacyPalettes legacy)
            throws ReflectiveOperationException {
        Object local = level == null ? null : localPalette(part, level, legacy);
        if (local != null) {
            Class<?> paletteType = Class.forName("mcjty.lostcities.worldgen.lost.cityassets.Palette", false, palette.getClass().getClassLoader());
            Object list = Array.newInstance(paletteType, 1); Array.set(list, 0, local);
            palette = palette.getClass().getConstructor(palette.getClass(), list.getClass()).newInstance(palette, list);
        }
        return palette;
    }

    private static Template template(TemplateInput input) {
        if (input == null) return null;
        TemplateKey key = input.key;
        synchronized (TEMPLATES) {
            Template cached = TEMPLATES.get(key); if (cached != null) return cached;
        }
        String[] slices = input.slices;
        int width = input.width, depth = input.depth;
        Map<Character, Integer> states = input.states;
        int[] voxels = new int[slices.length * 256]; Arrays.fill(voxels, -1);
        for (int y = 0; y < slices.length; y++) for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++)
            voxels[y * 256 + z * 16 + x] = states.get(slices[y].charAt(z * width + x));
        Template result = new Template(voxels, slices.length, LostCityExterior.mesh(voxels, slices.length, 1));
        synchronized (TEMPLATES) {
            Template oldEntry = TEMPLATES.put(key, result);
            templateBytes += bytes(result) - (oldEntry == null ? 0 : bytes(oldEntry));
            while (TEMPLATES.size() > 1024 || templateBytes > 8L * 1024 * 1024) {
                var iterator = TEMPLATES.values().iterator(); Template old = iterator.next(); iterator.remove(); templateBytes -= bytes(old);
            }
        }
        return result;
    }

    private static long bytes(Template template) { return 256L + template.states.length * 4L + template.model.quadCount() * 12L; }
    static int previewState(BlockState state) {
        return state == null || state.isAir() ? -1 : state.is(Blocks.STRUCTURE_VOID) ? -2
                : state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock ? -2 : Block.getId(state);
    }
    static void clear() {
        synchronized (TEMPLATES) { TEMPLATES.clear(); templateBytes = 0; }
        synchronized (SURFACES) { SURFACES.clear(); surfaceBytes = 0; }
        synchronized (INFRASTRUCTURE) { INFRASTRUCTURE.clear(); infrastructureBytes = 0; }
    }

    private static Object chunk(Object info, Method get, Map<Long, Object> cache, int x, int z) throws ReflectiveOperationException {
        long key = (long) x << 32 | z & 0xffffffffL;
        Object result = cache.get(key);
        if (result == null) { result = get.invoke(info, x, z); cache.put(key, result); }
        return result;
    }
    private static int material(Object palette, Object character, Block fallback) throws ReflectiveOperationException {
        if (palette == null || !(character instanceof Character c)) return Block.getId(fallback.defaultBlockState());
        BlockState state = (BlockState) palette.getClass().getMethod("get", char.class, Random.class).invoke(palette, c, new Random(c));
        return state == null || state.isAir() ? Block.getId(fallback.defaultBlockState()) : Block.getId(state);
    }
    private static Method method(Object object, String name, Class<?>... types) {
        try { return object.getClass().getMethod(name, types); } catch (NoSuchMethodException missing) { return null; }
    }
    private static Object optional(Object object, String name) throws ReflectiveOperationException {
        Method method = method(object, name); return method == null ? null : method.invoke(object);
    }
    private static int optionalInt(Object object, String name, int fallback) throws ReflectiveOperationException {
        Object value = optional(object, name); return value instanceof Integer n ? n : fallback;
    }
    private static Object field(Object object, String name) throws IllegalAccessException {
        try { return object.getClass().getField(name).get(object); } catch (NoSuchFieldException missing) { return null; }
    }
}
