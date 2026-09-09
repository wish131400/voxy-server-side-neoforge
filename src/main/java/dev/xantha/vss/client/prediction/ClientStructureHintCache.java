package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Deterministic structure placement hints for the predictive horizon. The
 * actual structure remains authoritative in VSS; candidates can request
 * refinement but cannot establish a structure's existence or geometry.
 */
final class ClientStructureHintCache {
    private final List<ResourceLocation> structures;
    private final List<ClientWorldgenRegistries.StructurePlacementInfo> placements;
    private final long seed;

    ClientStructureHintCache(Iterable<ResourceLocation> ids) {
        List<ResourceLocation> copy = new ArrayList<>();
        if (ids != null) {
            for (ResourceLocation id : ids) if (id != null) copy.add(id);
        }
        this.structures = List.copyOf(copy);
        this.placements = List.of();
        this.seed = 0L;
    }

    ClientStructureHintCache(Iterable<ClientWorldgenRegistries.StructurePlacementInfo> placements,
                             long seed) {
        List<ClientWorldgenRegistries.StructurePlacementInfo> copy = new ArrayList<>();
        if (placements != null) for (var placement : placements) if (placement != null) copy.add(placement);
        this.placements = List.copyOf(copy);
        this.seed = seed;
        this.structures = this.placements.stream().map(ClientWorldgenRegistries.StructurePlacementInfo::id).toList();
    }

    int index(int blockX, int blockZ, int biomeIndex) {
        if (structures.isEmpty()) return 0;
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        if (!placements.isEmpty()) {
            for (int i = 0; i < placements.size(); i++) {
                var placement = placements.get(i);
                if (!placement.biomes().isEmpty()) {
                    // The biome-index-only overload is retained for old callers;
                    // the sampler uses the ResourceLocation overload below.
                    continue;
                }
                if (isCandidate(placement, chunkX, chunkZ)) return i + 1;
            }
            return 0;
        }
        for (int i = 0; i < structures.size(); i++) {
            ResourceLocation id = structures.get(i);
            String path = id.getPath();
            int spacing = path.contains("stronghold") ? 128
                    : path.contains("village") || path.contains("pillager") ? 32
                    : path.contains("ancient") || path.contains("monument") ? 64 : 48;
            long hash = mix(((long) chunkX * 0x9E3779B97F4A7C15L)
                    ^ ((long) chunkZ * 0xC2B2AE3D27D4EB4FL)
                    ^ ((long) id.hashCode() << 17) ^ biomeIndex);
            if (Math.floorMod(hash, spacing * (long) spacing) == 0L) return i + 1;
        }
        return 0;
    }

    int index(int blockX, int blockZ, int biomeIndex, ResourceLocation biome) {
        if (placements.isEmpty()) return index(blockX, blockZ, biomeIndex);
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        for (int i = 0; i < placements.size(); i++) {
            var placement = placements.get(i);
            if (!placement.biomes().isEmpty()
                    && (biome == null || !placement.biomes().contains(biome))) continue;
            if (isCandidate(placement, chunkX, chunkZ)) return i + 1;
        }
        return 0;
    }

    private boolean isCandidate(ClientWorldgenRegistries.StructurePlacementInfo placement,
                                int chunkX, int chunkZ) {
        int spacing = Math.max(1, placement.spacing());
        int separation = Math.max(0, Math.min(spacing - 1, placement.separation()));
        var spreadType = placement.spreadType() == null
                ? net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType.LINEAR
                : placement.spreadType();
        var spread = new net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement(
                spacing, separation, spreadType, placement.salt());
        net.minecraft.world.level.ChunkPos candidate = spread.getPotentialStructureChunk(seed,
                chunkX, chunkZ);
        return candidate.x == chunkX && candidate.z == chunkZ;
    }

    ResourceLocation id(int index) {
        return index > 0 && index <= structures.size() ? structures.get(index - 1) : null;
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
