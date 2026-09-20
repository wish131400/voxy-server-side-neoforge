package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;

/** Visual representatives only. Never executes placed features or writes the world. */
final class PredictionSimpleVegetation {
    static final int MAX_FORMS = 256;
    private static final int MAX_HINTS = 4096;
    record Species(BlockState log, BlockState leaves, BlockState ground) {
        Species(BlockState log, BlockState leaves) { this(log, leaves, null); }
    }
    record Hint(List<Species> trees, boolean grass) {
        static final Hint EMPTY = new Hint(List.of(), false);
    }
    record Form(int cell, int x, int z, int y, int height, Species tree, int grassTint) { }
    record Result(List<Form> forms, int[] forestTints, int maxY) {
        static final Result EMPTY = new Result(List.of(), null, Integer.MIN_VALUE);
    }
    private record Key(int x, int y, int z, int scale) { }
    private final ClientTerrainSampler context;
    private final Map<Key, Hint> hints = new LinkedHashMap<>(64, .75F, true);
    private final Map<Holder<Biome>, Hint> biomes = new HashMap<>();
    private final java.util.concurrent.atomic.LongAdder loads = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder elapsed = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failures = new java.util.concurrent.atomic.LongAdder();

    PredictionSimpleVegetation(ClientTerrainSampler terrain) { context = terrain.decorationContext(); }

    private synchronized Hint hint(int x, int y, int z, int scale) {
        if (context.biomeSourceContext() == null || context.randomStateContext() == null) return Hint.EMPTY;
        var key = new Key(Math.floorDiv(x, scale), Math.floorDiv(y, 32), Math.floorDiv(z, scale), scale);
        var known = hints.get(key);
        if (known != null) return known;
        Hint value;
        try {
            var biome = context.noiseBiome(key.x() * scale / 4 + scale / 8, key.y() * 8 + 4, key.z() * scale / 4 + scale / 8);
            value = biomes.computeIfAbsent(biome, PredictionSimpleVegetation::inspect);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unsupported) {
            // A custom biome/feature provider must not prevent terrain publication.
            failures.increment();
            value = Hint.EMPTY;
        }
        hints.put(key, value);
        if (hints.size() > MAX_HINTS) hints.remove(hints.keySet().iterator().next());
        loads.increment();
        return value;
    }

    static Hint inspect(Holder<Biome> biome) {
        var id = biome.unwrapKey().map(k -> k.location()).orElse(null);
        if (id != null && id.getNamespace().equals("minecraft")
                && (id.getPath().contains("ocean") || id.getPath().contains("river"))) return Hint.EMPTY;
        var species = new LinkedHashSet<Species>();
        boolean grass = false;
        for (var stage : biome.value().getGenerationSettings().features()) for (var placed : stage) {
            String name = placed.unwrapKey().map(k -> k.location().toString()).orElse("");
            grass |= name.startsWith("minecraft:") && (name.contains("grass") || name.contains("fern"));
            for (var feature : placed.value().getFeatures().toList()) {
                if (feature.config() instanceof net.minecraft.world.level.levelgen.feature.HugeFungusConfiguration fungus) {
                    if (species.size() < 8) species.add(new Species(fungus.stemState, fungus.hatState, fungus.validBaseState));
                    continue;
                }
                if (!(feature.config() instanceof TreeConfiguration tree)) continue;
                var random = RandomSource.create(0);
                var log = tree.trunkProvider.getState(random, BlockPos.ZERO);
                var leaves = tree.foliageProvider.getState(random, BlockPos.ZERO);
                if (leaves.getBlock() instanceof LeavesBlock && !log.isAir() && species.size() < 8)
                    species.add(new Species(log, leaves));
            }
        }
        return new Hint(List.copyOf(species), grass);
    }

    Result build(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ,
                 int[] grass, int[] foliage, PredictionVegetation.Tile exact) {
        return build(samples, grid, step, baseX, baseZ, grass, foliage, exact, (x,z) -> false);
    }

    Result build(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ,
                 int[] grass, int[] foliage, PredictionVegetation.Tile exact,
                 java.util.function.BiPredicate<Integer,Integer> known) {
        long started = System.nanoTime();
        try {
            return build(samples, grid, step, baseX, baseZ, context.profile().seed(), grass, foliage,
                    exact, (x,y,z) -> known.test(x,z) ? Hint.EMPTY : hint(x, y, z, Math.max(64, step * 8)));
        } finally { elapsed.add(System.nanoTime() - started); }
    }

    @FunctionalInterface interface Hints { Hint get(int x, int y, int z); }

    static Result build(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ, long seed,
                        int[] grass, int[] foliage, PredictionVegetation.Tile exact, Hints hints) {
        if (step < 4 || step > 32) return Result.EMPTY;
        int axis = grid - 2;
        var forms = new ArrayList<Form>();
        int[] tints = null;
        int maxY = Integer.MIN_VALUE;
        // Traverse in world order; one representative per fixed 16-block tree
        // parcel (8 for grass), independent of tile identity or rebuild order.
        for (int z = 0; z < axis; z++) for (int x = 0; x < axis; x++) {
            int i = (z + 1) * grid + x + 1, cell = z * axis + x;
            var s = samples[i];
            if (s.volume() != null) {
                if (exact.cell(cell).isEmpty()) addInteriorForms(forms, samples, grid, step, baseX, baseZ,
                        x, z, cell, seed, hints);
                continue;
            }
            if (!s.hasSurface() || s.hasFluid() || s.snow() || s.ice()
                    || s.topBlockIndex() != PredictionMaterialPalette.grassBlockIndex()) continue;
            int wx = baseX + x * step, wz = baseZ + z * step;
            Hint hint = hints.get(wx, s.surfaceY(), wz);
            if (hint.trees().stream().anyMatch(t -> t.ground() != null))
                hint = new Hint(hint.trees().stream().filter(t -> t.ground() == null).toList(), hint.grass());
            if (step > 8) {
                if (!hint.trees().isEmpty()) {
                    if (tints == null) tints = grass.clone();
                    tints[i] = PredictionVegetation.forestTint(grass[i], foliage[i], .5F);
                }
                continue;
            }
            if (!exact.cell(cell).isEmpty()) continue;
            int parcel = hint.trees().isEmpty() ? 8 : 16;
            long hash = mix(seed ^ (long)Math.floorDiv(wx, parcel) * 0x9e3779b97f4a7c15L
                    ^ (long)Math.floorDiv(wz, parcel) * 0xc2b2ae3d27d4eb4fL);
            int offset = hint.trees().isEmpty() ? 2 : parcel / 2 + 2;
            int px = Math.floorDiv(wx, parcel) * parcel + offset;
            int pz = Math.floorDiv(wz, parcel) * parcel + offset;
            if (px < wx || px >= wx + step || pz < wz || pz >= wz + step) continue;
            if (!hint.trees().isEmpty() && (hash & 15) < (step == 8 ? 3 : 12)) {
                Species tree = hint.trees().get(Math.floorMod(hash >>> 8, hint.trees().size()));
                int height = 5 + (int)((hash >>> 16) & 3);
                // Centre within the owning cell so a canopy never changes
                // ownership halfway across a neighbouring tile.
                forms.add(new Form(cell, px - baseX, pz - baseZ, s.surfaceY(), height, tree, grass[i]));
                maxY = Math.max(maxY, s.surfaceY() + height);
            } else if (step == 4 && hint.grass() && (hash & 3) == 0) {
                forms.add(new Form(cell, x * step + 1, z * step + 1, s.surfaceY(), 1, null, grass[i]));
                maxY = Math.max(maxY, s.surfaceY() + 1);
            }
        }
        if (forms.size() > MAX_FORMS) {
            forms.sort(Comparator.comparingLong(f -> mix(seed ^ (long)(baseX + f.x()) << 32 ^ (baseZ + f.z()))));
            forms.subList(MAX_FORMS, forms.size()).clear();
            forms.sort(Comparator.comparingInt(Form::cell));
        }
        for (var form : forms) maxY = Math.max(maxY, form.y() + form.height());
        return new Result(List.copyOf(forms), tints, maxY);
    }

    private static void addInteriorForms(List<Form> forms, ClientColumnSample[] samples, int grid, int step,
                                          int baseX, int baseZ, int x, int z, int cell, long seed, Hints hints) {
        int wx = baseX + x * step, wz = baseZ + z * step;
        int parcel = Math.max(16, step);
        int px = Math.floorDiv(wx, parcel) * parcel + parcel / 2;
        int pz = Math.floorDiv(wz, parcel) * parcel + parcel / 2;
        if (px < wx || px >= wx + step || pz < wz || pz >= wz + step) return;
        var volume = samples[(z + 1) * grid + x + 1].volume();
        for (int run = 0; run < volume.size(); run++) {
            int y = volume.top(run);
            if (volume.fluid(run) != 0 || volume.occupied(y, false)) continue;
            long hash = mix(seed ^ (long)Math.floorDiv(px, parcel) * 0x9e3779b97f4a7c15L
                    ^ (long)Math.floorDiv(pz, parcel) * 0xc2b2ae3d27d4eb4fL ^ (long)y * 0x632be59bd9b4e019L);
            if ((hash & 15) >= (step <= 8 ? 10 : 5)) continue;
            var hint = hints.get(px, y, pz);
            var ground = net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(volume.block(run));
            var fungi = hint.trees().stream().filter(t -> t.ground() != null && t.ground().is(ground)).toList();
            if (fungi.isEmpty()) continue;
            int height = 5 + (int)((hash >>> 16) & 3);
            boolean clear = true;
            // Check all sampled columns touched by the cap, using retained geometry only.
            for (int zz = Math.floorDiv(pz - baseZ - 2, step); zz <= Math.floorDiv(pz - baseZ + 1, step); zz++)
                for (int xx = Math.floorDiv(px - baseX - 2, step); xx <= Math.floorDiv(px - baseX + 1, step); xx++) {
                    if (xx < -1 || zz < -1 || xx >= grid - 1 || zz >= grid - 1) { clear = false; continue; }
                    var neighbor = samples[(zz + 1) * grid + xx + 1].volume();
                    if (neighbor == null || !neighbor.occupied(y - 1, true)) { clear = false; continue; }
                    for (int yy = y; yy < y + height; yy++) if (neighbor.occupied(yy, false)) { clear = false; break; }
                }
            if (clear) forms.add(new Form(cell, px - baseX, pz - baseZ, y, height,
                    fungi.get(Math.floorMod(hash >>> 8, fungi.size())), 0));
        }
    }

    static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    String diagnostics() { return "biomeLoads=" + loads.sum() + ",buildMs=" + elapsed.sum() / 1_000_000
            + ",unsupportedHints=" + failures.sum(); }
}
