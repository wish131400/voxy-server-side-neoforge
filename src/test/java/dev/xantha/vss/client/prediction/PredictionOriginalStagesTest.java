package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.GeneratorStageCapabilities;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.block.Blocks;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionOriginalStagesTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    static class UnknownGenerator extends NoiseBasedChunkGenerator {
        int calls;
        boolean failSurface;
        UnknownGenerator() { super(TerrainPredictionFixtures.noise().getBiomeSource(), TerrainPredictionFixtures.noise().generatorSettings()); }
        @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState state, StructureManager structures, ChunkAccess chunk) {
            calls++;
            chunk.setBlockState(new BlockPos(chunk.getPos().getMinBlockX(),70,chunk.getPos().getMinBlockZ()), Blocks.STONE.defaultBlockState(),false);
            chunk.setBlockState(new BlockPos(chunk.getPos().getMinBlockX(),71,chunk.getPos().getMinBlockZ()), Blocks.WATER.defaultBlockState(),false);
            return CompletableFuture.completedFuture(chunk);
        }
        @Override public void buildSurface(ChunkAccess chunk, WorldGenerationContext context, RandomState state, StructureManager structures,
                BiomeManager biomes, Registry<Biome> registry, Blender blender) {
            chunk.setBlockState(new BlockPos(chunk.getPos().getMinBlockX(),70,chunk.getPos().getMinBlockZ()), Blocks.GRASS_BLOCK.defaultBlockState(),false);
            if (failSurface) throw new IllegalStateException("requires private world state");
        }
    }
    private PredictionOriginalStages open(UnknownGenerator generator) {
        var access = TerrainPredictionFixtures.access();
        var settings = generator.generatorSettings().value();
        var state = RandomState.create(settings, access.lookupOrThrow(Registries.NOISE), 99L);
        var profile = new DimensionProfile(Level.OVERWORLD.location(),99L,-64,384,"test:unknown","test:unknown",1L);
        return new PredictionOriginalStages(new ClientTerrainSampler(99L,profile,generator,state,
                LevelHeightAccessor.create(-64,384),settings.seaLevel(),null,access));
    }
    @Test void callsUnknownOriginalStagesAndReusesCompletedChunk() {
        var generator = new UnknownGenerator();
        assertTrue(GeneratorStageCapabilities.inspect(generator.getClass()).terrain());
        assertTrue(GeneratorStageCapabilities.inspect(generator.getClass()).isolated());
        try (var sampler = open(generator)) {
            var sample = sampler.sample(0,0);
            assertEquals(71,sample.surfaceY());
            assertEquals(72,sample.fluidY());
            assertEquals(1,sample.fluid());
            assertEquals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK),sample.topBlockIndex());
            assertEquals(-64,sampler.sample(1,0).surfaceY());
            assertFalse(sampler.sample(1,0).hasSurface());
            assertEquals(1,generator.calls,"neighbor lookup must reuse completed original stages");
        }
    }
    @Test void failedSurfaceRollsBackWritesAndKeepsTerrainWithoutRetrying() {
        var generator = new UnknownGenerator(); generator.failSurface = true;
        var sampler = open(generator);
        var sample = sampler.sample(0,0);
        assertEquals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.STONE),sample.topBlockIndex());
        assertEquals(sample,sampler.sample(0,0));
        assertEquals(1,generator.calls);
        sampler.close();
        assertThrows(java.util.concurrent.CancellationException.class,()->sampler.sample(0,0));
    }
}
