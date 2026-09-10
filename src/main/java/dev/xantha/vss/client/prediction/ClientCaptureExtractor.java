package dev.xantha.vss.client.prediction;

import dev.xantha.vss.api.VoxelColumnData;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Extracts the compact capture used at the authoritative/predicted boundary.
 *
 * <p>The VSS payload contains complete chunk sections, so doing this work from
 * the section data is both cheaper and more accurate than retaining the
 * predicted sample's material metadata. The walk mirrors the capture
 * contract: fluid is recorded separately, the first natural ground block is
 * the surface, and natural blocks below it supply side materials. Structures
 * and underground spans remain the responsibility of the exact renderer.</p>
 */
final class ClientCaptureExtractor {
    private ClientCaptureExtractor() {
    }

    static ClientColumnSample extract(int chunkX, int chunkZ, VoxelColumnData data,
                                      ClientTerrainSampler sampler) {
        return extractAll(chunkX, chunkZ, data, sampler)[8 + 8 * 16];
    }

    /** Extracts all 256 columns from one authoritative chunk in a single pass. */
    static ClientColumnSample[] extractAll(int chunkX, int chunkZ, VoxelColumnData data,
                                           ClientTerrainSampler sampler) {
        int minY = sampler.profile().minY();
        int maxY = minY + sampler.profile().height() - 1;
        SectionAccess access = new SectionAccess(data == null ? new VoxelColumnData.SectionData[0]
                : data.sections(), minY, maxY);
        // Prediction receives columns after transfer assembly. The replacement
        // flag controls Voxy storage cleanup, not whether fresh terrain is complete.
        boolean complete = data != null && data.completesRequest();
        ClientColumnSample[] result = new ClientColumnSample[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                result[z * 16 + x] = extractColumn(chunkX, chunkZ, x, z, access, sampler,
                        minY, maxY, complete);
            }
        }
        return result;
    }

    private static ClientColumnSample extractColumn(int chunkX, int chunkZ, int x, int z,
                                                     SectionAccess access,
                                                     ClientTerrainSampler sampler,
                                                     int minY, int maxY, boolean complete) {
        ClientColumnSample predicted = sampler.sampleSurface(chunkX * 16 + x, chunkZ * 16 + z);
        // A delta/unfinished transfer cannot prove either the top surface or
        // empty space above it. Wait for the assembled authoritative column.
        if (!complete) return predicted.withoutVegetationHints();
        int topY = Integer.MIN_VALUE;
        int fluidY = Integer.MIN_VALUE;
        int fluid = 0;
        int flags = ClientColumnSample.FLAG_CAPTURED;
        int topBlock = ClientColumnSample.NO_BLOCK;
        int underBlock = predicted.underBlockIndex();
        int deepBlock = predicted.deepBlockIndex();
        int groundFeature = 0;
        boolean fluidFound = false;

        for (int y = maxY; y >= minY; y--) {
            BlockState state = access.state(x, y, z);
            if (state == null || state.isAir()) {
                continue;
            }
            if (!fluidFound && !state.getFluidState().isEmpty()) {
                fluidFound = true;
                fluid = state.getFluidState().is(FluidTags.LAVA) ? 2 : 1;
                fluidY = y + 1;
                BlockState above = access.state(x, y + 1, z);
                if (above != null && above.is(Blocks.ICE)) {
                    flags |= ClientColumnSample.FLAG_ICE | ClientColumnSample.FLAG_SNOW;
                }
                continue;
            }
            if (isGround(state) && !elevatedPortalBlock(access, x, y, z, state, predicted)) {
                topY = y + 1;
                topBlock = blockId(state);
                underBlock = blockId(solidBelow(access, x, y - 1, z, state));
                deepBlock = blockId(solidBelow(access, x, y - 7, z, state));
                BlockState above = access.state(x, y + 1, z);
                if (above != null && above.is(Blocks.SNOW)) {
                    flags |= ClientColumnSample.FLAG_SNOW;
                }
                if (!fluidFound) {
                    fluidY = topY;
                }
                break;
            }
            if (groundFeature == 0 && isGroundFeature(state)) {
                groundFeature = 1;
            }
        }

        if (topY == Integer.MIN_VALUE) {
            return new ClientColumnSample(minY, fluidFound ? fluidY : minY,
                    predicted.biomeIndex(), ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, fluid,
                    flags | ClientColumnSample.FLAG_NO_SURFACE, 0,
                    ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        }

        int surfaceBottom = ClientColumnSample.NO_SPAN;
        int lowerTop = ClientColumnSample.NO_SPAN;
        int lowerBottom = ClientColumnSample.NO_SPAN;
        int spanFloor = ClientColumnSample.NO_SPAN;

        int effectiveFluid = fluid;
        if (effectiveFluid != 0) {
            flags &= ~ClientColumnSample.FLAG_SNOW;
        }
        return new ClientColumnSample(
                topY,
                fluidY == Integer.MIN_VALUE ? predicted.fluidY() : fluidY,
                predicted.biomeIndex(),
                topBlock,
                0,
                0,
                0,
                0,
                effectiveFluid,
                flags,
                groundFeature,
                underBlock,
                deepBlock,
                surfaceBottom,
                lowerTop,
                lowerBottom,
                spanFloor);
    }

    private static BlockState solidBelow(SectionAccess access, int x, int fromY, int z,
                                         BlockState fallback) {
        for (int y = fromY; y > fromY - 12; y--) {
            BlockState state = access.state(x, y, z);
            if (!isGround(state)) {
                continue;
            }
            return state;
        }
        return fallback;
    }

    private static boolean elevatedPortalBlock(SectionAccess access, int x, int y, int z,
                                               BlockState state, ClientColumnSample predicted) {
        if (!state.is(Blocks.OBSIDIAN) || y < predicted.surfaceY()) return false;
        BlockState below = access.state(x, y - 1, z);
        // Keep a natural obsidian surface; a frame suspended over air or a
        // vertical stack above terrain must not become a solid ground column.
        return !isGround(below) || below.is(Blocks.OBSIDIAN);
    }

    static boolean isGround(BlockState state) {
        return state != null && !state.isAir()
                && state.getFluidState().isEmpty()
                && (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.NYLIUM)
                || state.is(BlockTags.ICE)
                || (state.is(BlockTags.SNOW) && !state.is(Blocks.SNOW))
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.MUD)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.BEDROCK)
                || state.is(Blocks.BASALT) || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.END_STONE) || state.is(Blocks.CALCITE)
                || state.is(Blocks.TUFF) || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.OBSIDIAN) || state.is(Blocks.DIRT_PATH));
    }

    private static boolean isGroundFeature(BlockState state) {
        return state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.BROWN_MUSHROOM)
                || state.is(Blocks.RED_MUSHROOM);
    }

    private static int blockId(BlockState state) {
        return state == null || state.isAir() ? ClientColumnSample.NO_BLOCK
                : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(state.getBlock());
    }

    private static final class SectionAccess {
        private final Map<Integer, net.minecraft.world.level.chunk.LevelChunkSection> sections;
        private final int minSectionY;
        private final int maxSectionY;

        private SectionAccess(VoxelColumnData.SectionData[] sections, int minY, int maxY) {
            Map<Integer, net.minecraft.world.level.chunk.LevelChunkSection> indexed = new HashMap<>();
            for (VoxelColumnData.SectionData section : sections) {
                if (section != null && section.section() != null) {
                    indexed.put(section.sectionY(), section.section());
                }
            }
            this.sections = Map.copyOf(indexed);
            this.minSectionY = Math.floorDiv(minY, 16);
            this.maxSectionY = Math.floorDiv(maxY, 16);
        }

        private BlockState state(int x, int y, int z) {
            int sectionY = Math.floorDiv(y, 16);
            if (sectionY < minSectionY || sectionY > maxSectionY) {
                return Blocks.AIR.defaultBlockState();
            }
            net.minecraft.world.level.chunk.LevelChunkSection section = sections.get(sectionY);
            if (section != null) {
                return section.getBlockState(x, Math.floorMod(y, 16), z);
            }
            return Blocks.AIR.defaultBlockState();
        }
    }
}
