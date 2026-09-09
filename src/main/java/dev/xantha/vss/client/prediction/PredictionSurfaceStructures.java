package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/** Replays actual surface structure starts and pieces in an isolated, bounded level. */
final class PredictionSurfaceStructures {
    private volatile String lastFailure = "none";
    private final ClientTerrainSampler context;
    private final RegistryAccess access;
    private final PredictionStructureTemplates templates;
    private final ChunkGeneratorStructureState state;
    private final List<Holder<StructureSet>> sets;
    private final Map<Structure, Integer> indices = new HashMap<>();
    private final Map<Key, StructureStart> starts = new LinkedHashMap<>(64, .75F, true);
    private final java.util.Set<Structure> loggedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Structure> missingRegistryFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.LongAdder placedStarts = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failedStarts = new java.util.concurrent.atomic.LongAdder();

    PredictionSurfaceStructures(ClientTerrainSampler context) {
        this.context = context;
        this.access = context.decorationAccess();
        PredictionStructureTemplates found = null;
        ChunkGeneratorStructureState generation = null;
        List<Holder<StructureSet>> possible = List.of();
        if (access != null && context.generatorContext() != null
                && access.registry(Registries.STRUCTURE_SET).isPresent()) {
            try {
                found = PredictionStructureTemplates.open(context.structureTemplates());
                generation = ChunkGeneratorStructureState.createForFlat(context.randomStateContext(),
                        context.profile().seed(), context.biomeSourceContext(),
                        access.registryOrThrow(Registries.STRUCTURE_SET).holders()
                                .filter(holder -> holder.value().placement() instanceof RandomSpreadStructurePlacement)
                                .map(holder -> (Holder<StructureSet>) holder));
                possible = generation.possibleStructureSets().stream().filter(holder -> holder.value().structures()
                        .stream().anyMatch(entry -> supported(entry.structure().value()))).toList();
                Map<GenerationStep.Decoration,Integer> stepIndices = new java.util.EnumMap<>(GenerationStep.Decoration.class);
                for (Structure structure : access.registryOrThrow(Registries.STRUCTURE)) {
                    indices.put(structure, stepIndices.merge(structure.step(),1,Integer::sum)-1);
                }
            } catch (Exception failure) {
                if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS structure context unavailable: " + failure);
            }
        }
        this.templates = found;
        this.state = generation;
        this.sets = possible;
    }

    static boolean surface(Structure structure) {
        return structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES;
    }

    private boolean supported(Structure structure) {
        return supported(structure,context.profile().dimension().equals(net.minecraft.world.level.Level.NETHER.location()));
    }

    static boolean supported(Structure structure, boolean nether) {
        return surface(structure) || nether
                && structure.step() == GenerationStep.Decoration.UNDERGROUND_DECORATION;
    }

    boolean available() { return templates != null && !sets.isEmpty(); }

    String diagnostics() {
        return "structureSets=" + sets.size() + ",structureChunks=" + placedStarts.sum()
                + ",skippedStructures=" + failedStarts.sum() + ",disabledRegistryStructures="
                + missingRegistryFailures.size() + ",structureFailure=" + lastFailure;
    }

    void place(PredictionDecorationLevel level, int chunkX, int chunkZ, long decorationSeed) {
        place(level,chunkX,chunkZ,decorationSeed,GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal());
    }

    void place(PredictionDecorationLevel level, int chunkX, int chunkZ, long decorationSeed, int step) {
        if (templates == null || !VSSClientConfig.CONFIG.predictionStructures) return;
        List<StructureStart> touching = new ArrayList<>();
        for (var holder : sets) {
            if (holder.value().structures().stream().noneMatch(entry -> supported(entry.structure().value())
                    && entry.structure().value().step().ordinal() == step)) continue;
            var placement = (RandomSpreadStructurePlacement) holder.value().placement();
            int spacing = placement.spacing();
            for (int rz = Math.floorDiv(chunkZ - 8, spacing); rz <= Math.floorDiv(chunkZ + 8, spacing); rz++) {
                for (int rx = Math.floorDiv(chunkX - 8, spacing); rx <= Math.floorDiv(chunkX + 8, spacing); rx++) {
                    checkCancelled();
                    ChunkPos candidate = placement.getPotentialStructureChunk(context.profile().seed(), rx * spacing, rz * spacing);
                    if (Math.abs(candidate.x - chunkX) > 8 || Math.abs(candidate.z - chunkZ) > 8) continue;
                    StructureStart start = start(holder.value(), candidate);
                    if (start.isValid() && supported(start.getStructure()) && start.getStructure().step().ordinal() == step
                            && start.getBoundingBox().intersects(chunkX * 16, chunkZ * 16,
                            chunkX * 16 + 15, chunkZ * 16 + 15)) touching.add(start);
                }
            }
        }
        touching.sort(java.util.Comparator.comparingInt((StructureStart start) -> indices.getOrDefault(start.getStructure(), 0))
                .thenComparingLong(start -> start.getChunkPos().toLong()));
        WorldgenRandom random = new WorldgenRandom(new net.minecraft.world.level.levelgen.XoroshiroRandomSource(0));
        Structure previous = null;
        for (StructureStart start : touching) {
            checkCancelled();
            if (missingRegistryFailures.contains(start.getStructure())) continue;
            if (start.getStructure() != previous) random.setFeatureSeed(decorationSeed,
                    indices.getOrDefault(start.getStructure(), 0), step);
            previous = start.getStructure();
            level.beginStructure();
            boolean success = false;
            try {
                // Some vanilla scattered pieces memoize height during postProcess.
                // Serialize access to a cached start, without locking other starts.
                synchronized (start) {
                    start.placeInChunk(level, null, context.generatorContext(), random,
                            new BoundingBox(chunkX * 16, level.getMinBuildHeight(), chunkZ * 16,
                                    chunkX * 16 + 15, level.getMaxBuildHeight() - 1, chunkZ * 16 + 15),
                            new ChunkPos(chunkX, chunkZ));
                }
                placedStarts.increment();
                success = true;
            } catch (RuntimeException failure) {
                if (PredictionMissingRegistry.permanent(failure, access)) missingRegistryFailures.add(start.getStructure());
                failedStarts.increment();
                lastFailure = failure.toString();
                if (VSSClientConfig.CONFIG.debugLogging && loggedFailures.add(start.getStructure()))
                    VSSLogger.debug("VSS surface structure skipped: " + start.getStructure() + ": " + failure);
            } finally {
                level.endFeature(success);
            }
        }
    }

    private synchronized StructureStart start(StructureSet set, ChunkPos pos) {
        Key key = new Key(set, pos.toLong());
        StructureStart cached = starts.get(key);
        if (cached != null) return cached;
        checkCancelled();
        StructureStart result = StructureStart.INVALID_START;
        if (set.placement().isStructureChunk(state, pos.x, pos.z)) {
            List<StructureSet.StructureSelectionEntry> remaining = new ArrayList<>(set.structures());
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0));
            random.setLargeFeatureSeed(context.profile().seed(), pos.x, pos.z);
            int total = remaining.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
            while (!remaining.isEmpty()) {
                int target = remaining.size() == 1 ? 0 : random.nextInt(total);
                int i = 0;
                while ((target -= remaining.get(i).weight()) >= 0) i++;
                var entry = remaining.remove(i);
                total -= entry.weight();
                // Do not choose an alternate structure: it would change the
                // seeded selection when the chosen structure is unavailable.
                if (missingRegistryFailures.contains(entry.structure().value())) break;
                try {
                    Structure structure = entry.structure().value();
                    result = structure.generate(access, context.generatorContext(), context.biomeSourceContext(),
                            context.randomStateContext(), templates, context.profile().seed(), pos, 0,
                            LevelHeightAccessor.create(context.profile().minY(), context.profile().height()), structure.biomes()::contains);
                    if (result.isValid()) break;
                } catch (RuntimeException failure) {
                    if (PredictionMissingRegistry.permanent(failure, access))
                        missingRegistryFailures.add(entry.structure().value());
                    // Missing templates cannot produce half a village; abandon this start.
                    failedStarts.increment();
                    lastFailure = failure.toString();
                    result = StructureStart.INVALID_START;
                    break;
                }
            }
        }
        starts.put(key, result);
        if (starts.size() > 512) starts.remove(starts.keySet().iterator().next());
        return result;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }
    private record Key(StructureSet set, long chunk) { }
}
