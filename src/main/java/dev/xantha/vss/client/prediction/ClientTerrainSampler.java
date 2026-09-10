package dev.xantha.vss.client.prediction;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Small deterministic sampler owned by VSS. It deliberately has no dependency
 * on another terrain mod or on client chunk storage, so it can run safely on a
 * worker thread while the authoritative VSS columns arrive in the foreground.
 * Subclasses (the rust graph sampler) replace surfaceY/sample wholesale.
 */
public class ClientTerrainSampler {
    private final long seed;
    private final DimensionProfile profile;
    private final long dimensionSalt;
    private final NoiseBasedChunkGenerator generator;
    private final RandomState randomState;
    private final LevelHeightAccessor heights;
    private final int seaLevel;
    private final DensityFunction finalDensity;
    private final DensityFunction initialDensity;
    private final boolean initialDensityIsConstant;
    private final boolean lavaOcean;
    private final BiomeSource biomeSource;
    private final net.minecraft.world.level.biome.Climate.Sampler climate;
    private final PredictionBiomeCache biomeCache;
    private final Map<Holder<Biome>, Integer> biomeIndices;
    private final ClientFeatureHintCache featureHints;
    private final ClientStructureHintCache structureHints;
    private final PredictionFeatureStampCache featureStamps;
    private net.minecraft.core.RegistryAccess decorationAccess;
    private com.google.gson.JsonObject structureTemplates = new com.google.gson.JsonObject();
    private final int floorY;
    private final int ceilingY;
    private final TerrainFunction customSurface;
    private final ClientSurfaceResolver surfaceMaterials;
    // Preview-only cache: never used by surfaceY, exact materials or decoration.
    private final ThreadLocal<java.util.LinkedHashMap<Long, Integer>> previewHeights =
            ThreadLocal.withInitial(() -> new java.util.LinkedHashMap<>(256, .75f, true));
    private volatile PredictionBiomeCache previewBiomes;

    @FunctionalInterface
    public interface TerrainFunction {
        int surfaceY(int blockX, int blockZ);
    }

    public ClientTerrainSampler(long seed, DimensionProfile profile) {
        this.seed = seed;
        this.profile = profile;
        this.dimensionSalt = profile.fingerprint() ^ profile.dimension().hashCode();
        this.generator = null;
        this.randomState = null;
        this.heights = null;
        this.seaLevel = profile.minY() + Math.min(profile.height() - 1, 63 - profile.minY());
        this.finalDensity = null;
        this.initialDensity = null;
        this.initialDensityIsConstant = false;
        this.lavaOcean = false;
        this.biomeSource = null;
        this.climate = null;
        this.biomeCache = null;
        this.biomeIndices = Map.of();
        this.featureHints = null;
        this.structureHints = new ClientStructureHintCache(java.util.List.of());
        this.featureStamps = new PredictionFeatureStampCache();
        this.floorY = profile.minY();
        this.ceilingY = profile.minY() + profile.height() - 1;
        this.customSurface = null;
        this.surfaceMaterials = null;
    }

    /** Creates a sampler for a third-party terrain backend without exposing
     * VSS internals or requiring reflection into another mod. */
    public static ClientTerrainSampler custom(long seed, DimensionProfile profile,
                                              TerrainFunction surface) {
        if (surface == null) throw new IllegalArgumentException("surface function is null");
        return new ClientTerrainSampler(seed, profile, surface);
    }

    private ClientTerrainSampler(long seed, DimensionProfile profile, TerrainFunction surface) {
        this.seed = seed;
        this.profile = profile;
        this.dimensionSalt = profile.fingerprint() ^ profile.dimension().hashCode();
        this.generator = null;
        this.randomState = null;
        this.heights = null;
        this.seaLevel = profile.minY() + Math.min(profile.height() - 1, 63 - profile.minY());
        this.finalDensity = null;
        this.initialDensity = null;
        this.initialDensityIsConstant = false;
        this.lavaOcean = false;
        this.biomeSource = null;
        this.climate = null;
        this.biomeCache = null;
        this.biomeIndices = Map.of();
        this.featureHints = null;
        this.structureHints = new ClientStructureHintCache(java.util.List.of());
        this.featureStamps = new PredictionFeatureStampCache();
        this.floorY = profile.minY();
        this.ceilingY = profile.minY() + profile.height() - 1;
        this.customSurface = surface;
        this.surfaceMaterials = null;
    }

    /**
     * Copies a fully decoded sampler while replacing only its surface
     * function.  Compatibility adapters use this for generators which keep
     * Minecraft's biome/feature context but replace the terrain height
     * calculation in a mixin (for example BetterEnd's PAULEVS generator).
     */
    static ClientTerrainSampler withSurfaceOverride(ClientTerrainSampler source,
                                                     TerrainFunction surface) {
        if (source == null) throw new IllegalArgumentException("sampler is null");
        if (surface == null) throw new IllegalArgumentException("surface function is null");
        return new ClientTerrainSampler(source, surface);
    }

    ClientTerrainSampler(ClientTerrainSampler source, TerrainFunction surface) {
        this.seed = source.seed;
        this.profile = source.profile;
        this.dimensionSalt = source.dimensionSalt;
        this.generator = source.generator;
        this.randomState = source.randomState;
        this.heights = source.heights;
        this.seaLevel = source.seaLevel;
        this.finalDensity = source.finalDensity;
        this.initialDensity = source.initialDensity;
        this.initialDensityIsConstant = source.initialDensityIsConstant;
        this.lavaOcean = source.lavaOcean;
        this.biomeSource = source.biomeSource;
        this.climate = source.climate;
        this.biomeCache = source.biomeCache;
        this.biomeIndices = source.biomeIndices;
        this.featureHints = source.featureHints;
        this.structureHints = source.structureHints;
        this.featureStamps = source.featureStamps;
        this.decorationAccess = source.decorationAccess;
        this.structureTemplates = source.structureTemplates;
        this.floorY = source.floorY;
        this.ceilingY = source.ceilingY;
        this.customSurface = surface;
        this.surfaceMaterials = source.surfaceMaterials;
    }

    ClientTerrainSampler(long seed, DimensionProfile profile, NoiseBasedChunkGenerator generator,
                         RandomState randomState, LevelHeightAccessor heights, int seaLevel,
                         Iterable<ResourceLocation> structureIds) {
        this(seed, profile, generator, randomState, heights, seaLevel, structureIds, null, null);
    }

    ClientTerrainSampler(long seed, DimensionProfile profile, NoiseBasedChunkGenerator generator,
                         RandomState randomState, LevelHeightAccessor heights, int seaLevel,
                         Iterable<ResourceLocation> structureIds,
                         ClientWorldgenRegistries registries, net.minecraft.core.RegistryAccess access) {
        this.seed = seed;
        this.profile = profile;
        this.dimensionSalt = profile.fingerprint() ^ profile.dimension().hashCode();
        this.generator = generator;
        this.randomState = randomState;
        this.heights = heights;
        this.seaLevel = seaLevel;
        NoiseRouter router = FreeTerraForgedCompat.predictionRouter(randomState);
        this.finalDensity = router.finalDensity();
        this.initialDensity = router.initialDensityWithoutJaggedness();
        this.initialDensityIsConstant = initialDensity.minValue() == initialDensity.maxValue();
        this.lavaOcean = generator.generatorSettings().value().defaultFluid().is(Blocks.LAVA);
        this.biomeSource = generator.getBiomeSource();
        this.climate = randomState.sampler();
        this.biomeCache = new PredictionBiomeCache(biomeSource, climate);
        Map<Holder<Biome>, Integer> indices = new HashMap<>();
        int index = 0;
        for (Holder<Biome> biome : biomeSource.possibleBiomes()) {
            indices.put(biome, index++);
        }
        this.biomeIndices = Map.copyOf(indices);
        this.featureHints = new ClientFeatureHintCache(biomeSource.possibleBiomes());
        this.structureHints = registries == null
                ? new ClientStructureHintCache(structureIds)
                : new ClientStructureHintCache(registries.structurePlacements(), seed);
        this.featureStamps = new PredictionFeatureStampCache(registries, access, generator, this);
        this.decorationAccess = registries == null ? access : registries.access();
        if (registries != null) this.structureTemplates = registries.templates();
        this.floorY = heights.getMinBuildHeight();
        this.ceilingY = heights.getMaxBuildHeight() - 1;
        this.customSurface = null;
        this.surfaceMaterials = access == null ? null : new ClientSurfaceResolver(generator, randomState,
                heights, access.registryOrThrow(net.minecraft.core.registries.Registries.BIOME), biomeCache);
    }

    ClientColumnSample resolveSurface(ClientColumnSample sample, int x, int z, TerrainFunction terrain) {
        return surfaceMaterials == null ? sample : surfaceMaterials.resolve(sample, x, z, terrain);
    }

    public DimensionProfile profile() {
        return profile;
    }

    /** Returns a stable surface estimate in absolute block coordinates. */
    public int surfaceY(int blockX, int blockZ) {
        if (customSurface != null) {
            // A custom generator may use the dimension floor to represent a
            // genuine void column (BetterEnd's outer End does this).  Do not
            // turn that void into a one-block phantom floor.
            return Math.max(profile.minY(),
                    Math.min(profile.minY() + profile.height() - 1,
                            customSurface.surfaceY(blockX, blockZ)));
        }
        if (generator != null) {
            // Keep the raw solid boundary. the uses this value to decide
            // whether a column needs a sea/lava surface; the mesh builder
            // raises fluid cells to seaLevel separately.
            return densitySurfaceY(blockX, blockZ);
        }
        double continental = valueNoise(blockX * 0.0018, blockZ * 0.0018, 0x243F6A8885A308D3L);
        double erosion = valueNoise(blockX * 0.0065, blockZ * 0.0065, 0x13198A2E03707344L);
        double detail = valueNoise(blockX * 0.021, blockZ * 0.021, 0xA4093822299F31D0L);
        double normalized = continental * 0.62 + erosion * 0.26 + detail * 0.12;
        int amplitude = Math.max(12, Math.min(96, profile.height() / 5));
        int result = seaLevel + (int) Math.round(normalized * amplitude);
        return Math.max(profile.minY() + 1, Math.min(profile.minY() + profile.height() - 1, result));
    }

    public int groundY(int blockX, int blockZ) {
        if (customSurface != null) {
            return surfaceY(blockX, blockZ);
        }
        if (generator == null) {
            return surfaceY(blockX, blockZ);
        }
        return densitySurfaceY(blockX, blockZ);
    }

    /** Decoded generator context for optional terrain compatibility adapters. */
    NoiseBasedChunkGenerator generatorContext() {
        return generator;
    }

    /** Decoded random state whose climate sampler is used by modded biomes. */
    RandomState randomStateContext() {
        return randomState;
    }

    /** Decoded biome source, including modded End biome-source implementations. */
    BiomeSource biomeSourceContext() {
        return biomeSource;
    }

    Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
        return biomeCache != null ? biomeCache.get(quartX, quartY, quartZ)
                : biomeSourceContext().getNoiseBiome(quartX, quartY, quartZ, randomStateContext().sampler());
    }

    ClientTerrainSampler decorationContext() { return this; }

    com.google.gson.JsonObject structureTemplates() { return structureTemplates; }

    net.minecraft.core.RegistryAccess decorationAccess() {
        return decorationAccess;
    }

    /** Expensive backends can publish a smaller coverage grid before final refinement. */
    // Publish a cheap coarse grid first.  Terrain mods often use the Java
    // fallback; requiring the final 64x64 grid here made the first visible
    // tile wait for thousands of density/NoiseChunk samples.
    /** Stable resource-colormap identity, or unavailable for opaque color providers. */
    long colorCacheFingerprint() { return Long.MIN_VALUE; }

    int initialTerrainCellAxis(int lod) { return generator == null ? VssLodLayout.TILE_QUADS : PredictionWorkOrder.initialCellAxis(lod); }

    /** Samples all metadata needed by the Packed-quad mesh pipeline. */
    public ClientColumnSample sample(int blockX, int blockZ) {
        int surface = surfaceY(blockX, blockZ);
        if (generator == null || biomeSource == null || climate == null) {
            return new ClientColumnSample(surface, surface, -1,
                    PredictionMaterialPalette.representativeBlock(null, false, false, false), 0,
                    0, 0, 0, 0, 0, 0,
                    PredictionMaterialPalette.dirtIndex(),
                    PredictionMaterialPalette.stoneIndex(),
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        }
        boolean submerged = surface < seaLevel;
        int fluidKind = submerged ? (lavaOcean ? 2 : 1) : 0;
        int fluidY = submerged ? seaLevel : surface;
        int flags = 0;
        Holder<Biome> biome = noiseBiome(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(surface - 1),
                QuartPos.fromBlock(blockZ));
        Biome biomeValue = biome.value();
        if ((fluidKind == 1 && biomeValue.coldEnoughToSnow(
                new net.minecraft.core.BlockPos(blockX, fluidY - 1, blockZ)))
                || (fluidKind == 0 && biomeValue.coldEnoughToSnow(
                new net.minecraft.core.BlockPos(blockX, surface, blockZ)))) {
            flags |= fluidKind == 1 ? ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE
                    : ClientColumnSample.FLAG_SNOW;
        }
        int biomeIndex = biomeIndices.getOrDefault(biome, ClientColumnSample.NO_BLOCK);
        ClientFeatureHintCache.Hint hint = featureHints.hint(biome);
        int treeKind = hint.treeKind();
        int treeDensity = hint.density();
        int treeHeight = treeKind == 0 ? 0
                : 5 + (int) (featureHash(blockX + 17, blockZ - 31) % 5);
        // Feature-list hints cannot establish placement. The Java density
        // fallback leaves trees absent until a placement sample is available.
        int[] spans = spans(blockX, blockZ, surface);
        Optional<ResourceKey<Biome>> biomeKey = biome.unwrapKey();
        String biomePath = biomeKey.map(key -> key.location().getPath()).orElse("");
        int topBlock = PredictionMaterialPalette.representativeBlock(biomePath,
                (flags & ClientColumnSample.FLAG_SNOW) != 0, lavaOcean,
                (flags & ClientColumnSample.FLAG_TREE_HERE) != 0);
        int structureIndex = structureHints.index(blockX, blockZ, biomeIndex,
                biomeKey.map(ResourceKey::location).orElse(null));
        return resolveSurface(new ClientColumnSample(surface, fluidY, biomeIndex,
                topBlock,
                structureIndex,
                treeKind, treeDensity, treeHeight, fluidKind, flags, hint.groundKind(),
                PredictionMaterialPalette.dirtIndex(),
                PredictionMaterialPalette.stoneIndex(),
                spans[0], spans[1], spans[2], spans[3]), blockX, blockZ, this::surfaceY);
    }

    /**
     * Prediction displays the outer surface at every LOD. Placed features
     * generate decoration separately; neither stage needs underground spans.
     */
    public ClientColumnSample sampleForLod(int blockX, int blockZ, int stepBlocks) {
        return sampleSurface(blockX, blockZ);
    }

    /** Coarse/medium stage only; spacing alone must never select approximation. */
    public ClientColumnSample samplePreview(int x, int z, int stepBlocks) {
        if (generator == null || biomeSource == null || climate == null) {
            // Opaque integrations retain their existing LOD hook and deferral
            // semantics; a noise preview cannot replace an unknown generator.
            return stepBlocks >= 16 ? sampleForLod(x,z,stepBlocks) : sampleSurface(x,z);
        }
        return samplePreview(x,z);
    }

    public ClientColumnSample samplePreview(int x, int z) {
        if (generator == null || biomeSource == null || climate == null) return sampleSurface(x, z);
        int floor = previewSurfaceY(x, z);
        boolean empty = floor == floorY;
        var fluidState = generator.generatorSettings().value().defaultFluid();
        int fluid = !empty && floor < seaLevel && !fluidState.getFluidState().isEmpty()
                ? (lavaOcean ? 2 : 1) : 0;
        int fluidY = fluid == 0 ? floor : seaLevel;
        var biome = previewBiome(x, floor - 1, z);
        boolean snow = biome.value().coldEnoughToSnow(new net.minecraft.core.BlockPos(x,
                fluid == 0 ? floor : fluidY - 1, z));
        int flags = ClientColumnSample.FLAG_APPROXIMATE | (empty ? ClientColumnSample.FLAG_NO_SURFACE : 0)
                | (snow ? (fluid == 1 ? ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE
                : ClientColumnSample.FLAG_SNOW) : 0);
        String path = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        var sample = basicSample(floor, biomeIndices.getOrDefault(biome, ClientColumnSample.NO_BLOCK),
                fluidY, fluid, PredictionMaterialPalette.representativeBlock(path, snow, lavaOcean, false), flags);
        // Bypass overrides that request exact columns/erosion/river correction.
        return empty || surfaceMaterials == null ? sample
                : surfaceMaterials.resolvePreview(sample, x, z, this::previewSurfaceY);
    }

    int previewSurfaceY(int x, int z) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        // Opaque custom terrain must retain its own generator, never the base router.
        if (customSurface != null || finalDensity == null) return surfaceY(x, z);
        long key = (long)x << 32 | z & 0xffffffffL;
        var cache = previewHeights.get();
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        int air = ceilingY + 1, y = ceilingY, floor = floorY;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (solid(x, y, z)) {
                int solid = y;
                while (air - solid > 4) {
                    int middle = solid + (air - solid) / 2;
                    if (solid(x, middle, z)) solid = middle; else air = middle;
                }
                floor = solid + 1;
                break;
            }
            if (y == floorY) break;
            air = y;
            y = Math.max(floorY, y - 16);
        }
        cache.put(key, floor);
        while (cache.size() > 4096) cache.remove(cache.keySet().iterator().next());
        return floor;
    }

    private Holder<Biome> previewBiome(int x,int y,int z) {
        var cache = previewBiomes;
        if (cache == null) synchronized (this) {
            if (previewBiomes == null) previewBiomes = new PredictionBiomeCache(biomeSource,climate);
            cache = previewBiomes;
        }
        return cache.get(x >> 2,y >> 2,z >> 2);
    }

    int surfaceColorForLod(int x, int y, int z, boolean preview) {
        return preview && biomeSource != null ? previewBiome(x,y,z).value().getGrassColor(x,z) : surfaceColor(x,y,z);
    }
    int foliageColorForLod(int x, int y, int z, boolean preview) {
        return preview && biomeSource != null ? 0xff000000 | previewBiome(x,y,z).value().getFoliageColor() : foliageColor(x,y,z);
    }
    int waterTintForLod(int x, int y, int z, boolean preview) {
        return preview && biomeSource != null ? 0xb2000000 | previewBiome(x,y,z).value().getWaterColor() : waterTint(x,y,z);
    }

    /** Real surface rules and fluid/weather data, without underground or placement-hint scans. */
    public ClientColumnSample sampleSurface(int blockX, int blockZ) {
        // Native/custom samplers override sample and must keep that backend.
        if (generator == null || biomeSource == null || climate == null) return sample(blockX, blockZ);
        int surface = surfaceY(blockX, blockZ);
        boolean submerged = surface < seaLevel;
        int fluidKind = submerged ? (lavaOcean ? 2 : 1) : 0;
        int fluidY = submerged ? seaLevel : surface;
        Holder<Biome> biome = noiseBiome(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(surface - 1),
                QuartPos.fromBlock(blockZ));
        String biomePath = biome.unwrapKey().map(key -> key.location().getPath()).orElse("");
        boolean snow = (fluidKind == 1 && biome.value().coldEnoughToSnow(
                new net.minecraft.core.BlockPos(blockX, fluidY - 1, blockZ)))
                || (fluidKind == 0 && biome.value().coldEnoughToSnow(
                new net.minecraft.core.BlockPos(blockX, surface, blockZ)));
        int biomeIndex = biomeIndices.getOrDefault(biome, ClientColumnSample.NO_BLOCK);
        int flags = snow ? (fluidKind == 1
                ? ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE
                : ClientColumnSample.FLAG_SNOW) : 0;
        int topBlock = PredictionMaterialPalette.representativeBlock(biomePath,
                snow, lavaOcean, false);
        return resolveSurface(basicSample(surface, biomeIndex, fluidY, fluidKind, topBlock, flags),
                blockX, blockZ, this::surfaceY);
    }

    private ClientColumnSample basicSample(int surface, int biomeIndex, int fluidY,
                                            int fluidKind, int topBlock, int flags) {
        return new ClientColumnSample(surface, fluidY, biomeIndex, topBlock,
                0, 0, 0, 0, fluidKind, flags | ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    private long featureHash(int blockX, int blockZ) {
        long value = seed ^ dimensionSalt ^ ((long) blockX * 0x9E3779B97F4A7C15L)
                ^ ((long) blockZ * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        // Tree density is expressed in the same 0..1023 domain as the
        // feature placement threshold below (for example, 48 means about
        // 4.7% of columns).  The old 0..99 modulus made a density of 48
        // select nearly half of all columns and turned coarse LOD tiles into
        // a wall of detached green canopy boxes.
        return Math.floorMod(value, 1024);
    }

    public int seaLevel() {
        return seaLevel;
    }

    public boolean exactWorldgen() {
        return generator != null || customSurface != null;
    }

    PredictionFeatureStampCache featureStamps() {
        return featureStamps;
    }

    public int fluidColor() {
        return lavaOcean ? 0xE0D9572B : 0xB22D78C5;
    }

    /** Vanilla biome water multiplier; texture luminance is applied by the mesh palette. */
    public int waterTint(int blockX, int y, int blockZ) {
        if (biomeSource == null || randomState == null) return 0xB23F76E4;
        Holder<Biome> biome = noiseBiome(QuartPos.fromBlock(blockX),
                QuartPos.fromBlock(y), QuartPos.fromBlock(blockZ));
        return 0xB2000000 | biome.value().getWaterColor();
    }

    /** Returns a biome-derived land tint, leaving mesh shading to the builder. */
    public int surfaceColor(int blockX, int y, int blockZ) {
        if (biomeSource == null || randomState == null) {
            return 0;
        }
        Holder<Biome> biome = noiseBiome(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(y), QuartPos.fromBlock(blockZ));
        int grass = biome.value().getGrassColor(blockX, blockZ);
        // The seed prediction decides the biome; the visible colour must come
        // from Minecraft's own biome resolver.  Do not replace it with a
        // path-name palette: modded biomes and resource packs can use colors
        // that have no relationship to their registry path.
        return grass;
    }

    /**
     * The column biome's foliage colour (the tint vanilla applies to leaves),
     * 0 when no biome context exists.  Feature stamps use it so predicted
     * canopies match the trees the world actually generates instead of the
     * spawn biome's registry tint.
     */
    public int foliageColor(int blockX, int y, int blockZ) {
        if (biomeSource == null || randomState == null) {
            return 0;
        }
        Holder<Biome> biome = noiseBiome(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(y),
                QuartPos.fromBlock(blockZ));
        return 0xFF000000 | biome.value().getFoliageColor();
    }

    /** Two-stage density march: a coarse estimate followed by an 8-block
     * march and binary boundary refinement. This avoids constructing a chunk
     * for every LOD sample while evaluating Minecraft's real final density. */
    private int densitySurfaceY(int blockX, int blockZ) {
        if (initialDensityIsConstant) {
            return fullMarch(blockX, blockZ);
        }
        int estimate = estimateSurface(blockX, blockZ);
        int upper = Math.min(ceilingY, estimate + 40);
        if (solid(blockX, upper, blockZ)) {
            return scanUp(blockX, blockZ, upper);
        }
        int lower = Math.max(floorY, estimate - 40);
        int found = marchDown(blockX, blockZ, upper, lower);
        return found == Integer.MIN_VALUE ? fullMarch(blockX, blockZ) : found;
    }

    private int fullMarch(int blockX, int blockZ) {
        if (solid(blockX, ceilingY, blockZ)) {
            return ceilingY + 1;
        }
        int found = marchDown(blockX, blockZ, ceilingY, floorY);
        return found == Integer.MIN_VALUE ? floorY : found;
    }

    private int estimateSurface(int blockX, int blockZ) {
        int low = floorY;
        int high = ceilingY + 1;
        while (high - low > 1) {
            int mid = low + (high - low) / 2;
            if (initialDensity.compute(new DensityFunction.SinglePointContext(blockX, mid, blockZ)) > 0.0D) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean solid(int blockX, int y, int blockZ) {
        return finalDensity.compute(new DensityFunction.SinglePointContext(blockX, y, blockZ)) > 0.0D;
    }

    private int scanUp(int blockX, int blockZ, int startY) {
        int current = startY;
        while (current < ceilingY) {
            int next = Math.min(ceilingY, current + 8);
            if (!solid(blockX, next, blockZ)) {
                return refine(blockX, current, next, blockZ) + 1;
            }
            current = next;
        }
        return ceilingY + 1;
    }

    private int marchDown(int blockX, int blockZ, int startY, int stopY) {
        int previous = startY;
        for (int y = startY - 8; y >= stopY; y -= 8) {
            if (solid(blockX, y, blockZ)) {
                return refine(blockX, y, previous, blockZ) + 1;
            }
            previous = y;
        }
        return Integer.MIN_VALUE;
    }

    private int refine(int blockX, int low, int high, int blockZ) {
        while (high - low > 1) {
            int mid = low + (high - low) / 2;
            if (solid(blockX, mid, blockZ)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int[] spans(int blockX, int blockZ, int surface) {
        int[] result = {ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN};
        if (initialDensityIsConstant) {
            return result;
        }
        int estimate = estimateSurface(blockX, blockZ);
        int floor = Math.max(Math.max(estimate - 4, surface - 96), floorY);
        if (surface - estimate < 8 || surface - floor <= 2) {
            return result;
        }
        int first = firstBelow(blockX, blockZ, surface - 1, floor, false);
        if (first == Integer.MIN_VALUE) {
            return result;
        }
        int second = firstBelow(blockX, blockZ, first, floor, true);
        if (second == Integer.MIN_VALUE) {
            return new int[]{first + 1, ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN, estimate};
        }
        if (first - second >= 2) {
            int third = firstBelow(blockX, blockZ, second, floor, false);
            return new int[]{first + 1, second + 1,
                    third == Integer.MIN_VALUE ? ClientColumnSample.NO_SPAN : third + 1,
                    estimate};
        }
        return spansContinue(blockX, blockZ, floor, estimate, second);
    }

    private int[] spansContinue(int blockX, int blockZ, int floor, int estimate, int start) {
        int current = start;
        while (current != Integer.MIN_VALUE) {
            int first = firstBelow(blockX, blockZ, current, floor, false);
            if (first == Integer.MIN_VALUE) {
                return new int[]{current + 1, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, estimate};
            }
            int second = firstBelow(blockX, blockZ, first, floor, true);
            if (second == Integer.MIN_VALUE) {
                return new int[]{first + 1, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, estimate};
            }
            if (first - second >= 2) {
                int third = firstBelow(blockX, blockZ, second, floor, false);
                return new int[]{first + 1, second + 1,
                        third == Integer.MIN_VALUE ? ClientColumnSample.NO_SPAN : third + 1,
                        estimate};
            }
            current = second;
        }
        return new int[]{ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN};
    }

    private int firstBelow(int blockX, int blockZ, int startY, int stopY, boolean targetSolid) {
        int previous = startY;
        int y = floorY + Math.floorDiv(startY - 1 - floorY, 8) * 8;
        while (y > stopY) {
            if (solid(blockX, y, blockZ) != targetSolid) {
                return refineBoundary(blockX, blockZ, previous, y, targetSolid);
            }
            previous = y;
            y -= 8;
        }
        return Integer.MIN_VALUE;
    }

    private int refineBoundary(int blockX, int blockZ, int high, int low, boolean targetSolid) {
        while (high - low > 1) {
            int mid = low + (high - low) / 2;
            if (solid(blockX, mid, blockZ) == targetSolid) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    public int[] sampleChunk(int chunkX, int chunkZ) {
        int[] heights = new int[256];
        int index = 0;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                heights[index++] = surfaceY(baseX + x, baseZ + z);
            }
        }
        return heights;
    }

    private double valueNoise(double x, double z, long salt) {
        int x0 = floor(x);
        int z0 = floor(z);
        double tx = smooth(x - x0);
        double tz = smooth(z - z0);
        double a = lattice(x0, z0, salt);
        double b = lattice(x0 + 1, z0, salt);
        double c = lattice(x0, z0 + 1, salt);
        double d = lattice(x0 + 1, z0 + 1, salt);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    private double lattice(int x, int z, long salt) {
        long value = seed ^ dimensionSalt ^ salt;
        value += 0x9E3779B97F4A7C15L * x;
        value += 0xC2B2AE3D27D4EB4FL * z;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value / (double) Long.MAX_VALUE) * 0.5;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
