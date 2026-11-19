package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;

/** Worker-owned flat template capture with the same verified resource catalog as exact decoration. */
class PredictionTemplateStampLevel extends FeatureStampLevel {
    private final ClientTerrainSampler context;
    private PredictionStructureTemplates templates;
    private final ChunkAccess[] treeChunks;

    net.minecraft.world.level.chunk.ChunkGenerator predictionGenerator() { return context.generatorContext(); }

    PredictionTemplateStampLevel(long seed, ClientTerrainSampler context, BlockState ground, boolean nbtTree) {
        super(seed, context.decorationAccess(), ground);
        this.context = context;
        treeChunks = nbtTree ? new ChunkAccess[16] : null;
    }

    @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        // The stamp starts at (0, 64, 0) and bounds writes to ±24 blocks.
        // OTTYG records random ticks on the chunk during placement. Keep them in
        // disposable chunks so the same feature can run without a live world.
        if (treeChunks == null || x < -2 || x > 1 || z < -2 || z > 1) {
            return super.getChunk(x, z, status, create);
        }
        int slot = (z + 2) * 4 + x + 2;
        ChunkAccess chunk = treeChunks[slot];
        if (chunk == null) {
            chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                    LevelHeightAccessor.create(getMinBuildHeight(), getHeight()),
                    registryAccess().registryOrThrow(Registries.BIOME), null) {
                @Override public BlockState getBlockState(BlockPos pos) {
                    return PredictionTemplateStampLevel.this.getBlockState(pos);
                }

                @Override public BlockState setBlockState(BlockPos pos, BlockState state, boolean moving) {
                    BlockState previous = getBlockState(pos);
                    PredictionTemplateStampLevel.this.setBlock(pos, state, 19, 0);
                    return previous;
                }
            };
            treeChunks[slot] = chunk;
        }
        return chunk;
    }

    @Override public ClientTerrainSampler predictionContext() { return context; }

    @Override public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(net.minecraft.core.BlockPos pos) {
        return context.noiseBiome(net.minecraft.core.QuartPos.fromBlock(pos.getX()),
                net.minecraft.core.QuartPos.fromBlock(pos.getY()),net.minecraft.core.QuartPos.fromBlock(pos.getZ()));
    }

    @Override public ResourceLocation predictionDimension() { return context.profile().dimension(); }

    PredictionStructureTemplates templateManager() {
        if (templates == null) {
            try { templates = PredictionStructureTemplates.open(context.structureTemplates()); }
            catch (java.io.IOException failure) {
                throw new IllegalStateException("Prediction tree template manager unavailable", failure);
            }
        }
        return templates;
    }
}
