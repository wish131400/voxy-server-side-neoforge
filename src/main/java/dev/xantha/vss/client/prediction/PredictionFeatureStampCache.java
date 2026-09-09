package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.feature.FeatureSimulator;
import dev.xantha.vss.client.prediction.feature.StampMesher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/**
 * Bounded deterministic feature-stamp cache.  When a synchronized worldgen
 * registry is available, stamps are produced by actual ConfiguredFeature
 * placement code.  A small procedural fallback keeps prediction useful for
 * servers that only sent a density profile.
 */
final class PredictionFeatureStampCache {
    private static final int MAX_VARIANTS = 48;
    private static final long STAMP_SEED = 0x4D45524944494E4CL;
    private final ClientWorldgenRegistries registries;
    private final RegistryAccess access;
    private final NoiseBasedChunkGenerator generator;
    private final ClientTerrainSampler terrain;
    private final Map<Long, FeatureStamp> stamps = new ConcurrentHashMap<>();
    private final Map<Integer, ResourceLocation> featureIds = new ConcurrentHashMap<>();

    PredictionFeatureStampCache() { this(null, null, null, null); }

    PredictionFeatureStampCache(ClientWorldgenRegistries registries, RegistryAccess access,
                                NoiseBasedChunkGenerator generator, ClientTerrainSampler terrain) {
        this.registries = registries;
        this.access = access;
        this.generator = generator;
        this.terrain = terrain;
    }

    FeatureStamp get(int kind, int height) {
        return get(kind, height, STAMP_SEED ^ ((long) kind << 32) ^ height);
    }

    FeatureStamp getGround(int kind, int height, long seed) {
        int cacheKind = 100 + Math.max(0, kind);
        int clampedHeight = Math.max(1, Math.min(16, height));
        int variant = Math.floorMod(seed, MAX_VARIANTS);
        long key = ((long) cacheKind << 56) ^ ((long) clampedHeight << 32) ^ variant;
        long variantSeed = mix(STAMP_SEED ^ ((long) cacheKind << 40)
                ^ ((long) clampedHeight << 16) ^ variant * 0x9E3779B97F4A7C15L);
        return stamps.computeIfAbsent(key, ignored -> generate(cacheKind, clampedHeight,
                variantSeed, true));
    }

    FeatureStamp get(int kind, int height, long seed) {
        int clampedHeight = Math.max(3, Math.min(64, height));
        int variant = Math.floorMod(seed, MAX_VARIANTS);
        long key = ((long) kind << 56) ^ ((long) clampedHeight << 32) ^ variant;
        long variantSeed = mix(STAMP_SEED ^ ((long) kind << 40)
                ^ ((long) clampedHeight << 16) ^ variant * 0x9E3779B97F4A7C15L);
        return stamps.computeIfAbsent(key, ignored -> generate(kind, clampedHeight,
                variantSeed, false));
    }

    private FeatureStamp generate(int kind, int height, long seed, boolean groundFeature) {
        if (registries != null && access != null && generator != null && terrain != null) {
            Optional<ResourceLocation> id = featureId(kind, groundFeature);
            if (id.isPresent()) {
                Optional<ConfiguredFeature<?, ?>> feature = registries.configuredFeature(id.get());
                if (feature.isPresent()) {
                    try {
                        Optional<FeatureSimulator.Result> result = FeatureSimulator.simulateOnAnyGround(
                                feature.get(), access, generator, seed);
                        if (result.isPresent()) {
                            dev.xantha.vss.client.prediction.feature.FeatureStamp stamp = StampMesher.mesh(result.get().placed(), 64,
                                    (x, z) -> 64, 64);
                            if (!stamp.isEmpty()) return new FeatureStamp(kind, stamp);
                        }
                    } catch (RuntimeException ignored) {
                        // Modded features may require a capability unavailable
                        // in the isolated level; the procedural path remains valid.
                    }
                }
            }
        }
        return procedural(kind, height);
    }

    private Optional<ResourceLocation> featureId(int kind, boolean groundFeature) {
        ResourceLocation known = featureIds.get(kind);
        if (known != null) return Optional.of(known);
        if (registries == null) return Optional.empty();
        String[] preferred = groundFeature ? new String[]{"flower", "grass", "fern", "patch", "reed"}
                : switch (kind) {
            case 2 -> new String[]{"spruce", "pine", "taiga"};
            case 3 -> new String[]{"birch"};
            case 4 -> new String[]{"jungle", "bamboo", "mangrove"};
            default -> new String[]{"oak", "tree", "forest", "vegetation"};
        };
        // Prefer vanilla features matching the token: a tech modpack can
        // register modded features whose ids merely contain "oak"/"tree"
        // (some of them place planks and other non-tree blocks), which used
        // to turn whole predicted forests into wooden slabs.
        ResourceLocation moddedMatch = null;
        for (ResourceLocation candidate : registries.configuredFeatureIds()) {
            String path = candidate.getPath();
            for (String token : preferred) {
                if (!path.contains(token)) {
                    continue;
                }
                if ("minecraft".equals(candidate.getNamespace())) {
                    featureIds.putIfAbsent(kind, candidate);
                    return Optional.of(candidate);
                }
                if (moddedMatch == null) {
                    moddedMatch = candidate;
                }
                break;
            }
        }
        if (moddedMatch != null) {
            featureIds.putIfAbsent(kind, moddedMatch);
            return Optional.of(moddedMatch);
        }
        return Optional.empty();
    }

    private static FeatureStamp procedural(int kind, int height) {
        int radius = kind == 4 ? 3 : kind == 2 ? 2 : 3;
        int side = radius * 2 + 1;
        int[] voxels = new int[side * height * side];
        int index = 0;
        for (int y = 0; y < height; y++) {
            float t = y / (float) Math.max(1, height - 1);
            int layerRadius = y < height / 2 ? 1
                    : Math.max(1, Math.round(radius * (1.0F - t * 0.55F)));
            for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
                boolean trunk = Math.abs(x) <= 1 && Math.abs(z) <= 1;
                boolean canopy = y >= height / 3 && Math.abs(x) <= layerRadius
                        && Math.abs(z) <= layerRadius
                        && (kind != 2 || x * x + z * z <= layerRadius * layerRadius + 1);
                voxels[index++] = trunk || canopy ? 1 : 0;
            }
        }
        return new FeatureStamp(kind, radius, height, voxels, List.of(), List.of());
    }

    private static long mix(long value) {
        value ^= value >>> 30; value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27; value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    /** Compact form consumed by the VSS mesh builder. */
    record FeatureStamp(int kind, int radius, int height, int[] voxels,
                        List<Block> blocks, List<Quad> quads) {
        FeatureStamp(int kind, dev.xantha.vss.client.prediction.feature.FeatureStamp source) {
            this(kind, radiusOf(source), source.height(), new int[0],
                    source.blocks().stream().map(block -> new Block(block.x(), block.y(), block.z(), block.state())).toList(),
                    source.quads().stream().map(quad -> new Quad(quad.face(), quad.x(), quad.y(), quad.z(),
                            quad.extentA(), quad.extentB(), quad.state(), quad.light())).toList());
        }

        boolean hasGeometry() { return !blocks.isEmpty() || !quads.isEmpty(); }

        private static int radiusOf(dev.xantha.vss.client.prediction.feature.FeatureStamp stamp) {
            int radius = 1;
            for (var block : stamp.blocks()) {
                radius = Math.max(radius, Math.max(Math.abs(block.x()), Math.abs(block.z())));
            }
            return radius;
        }
    }

    record Block(int x, int y, int z, BlockState state) { }
    record Quad(int face, int x, int y, int z, int extentA, int extentB,
                BlockState state, int light) { }
}
