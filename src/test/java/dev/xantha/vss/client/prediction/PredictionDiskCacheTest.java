package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionDiskCacheTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void oldSurfaceSchemaIsRejectedWhileTerrainAndNewEditsRemainReusable() throws Exception {
        var terrain=PredictionDiskCache.Key.terrain(0,0,0);
        var surface=PredictionDiskCache.Key.surface(0,0,3);
        var edits=Map.of(new BlockPos(0,63,0),Blocks.FARMLAND.defaultBlockState(),
                new BlockPos(1,63,0),Blocks.AIR.defaultBlockState(),new BlockPos(2,63,0),Blocks.WATER.defaultBlockState());
        try(var cache=new PredictionDiskCache(directory,77)) {
            try(var lease=cache.lease(terrain)) { assertTrue(cache.writeTerrain(lease,samples())); }
            try(var lease=cache.lease(surface)) { assertTrue(cache.writeSurface(lease,edits)); }
            try(var lease=cache.lease(surface)) { assertEquals(edits,cache.readSurface(lease)); }
            byte[] raw;
            try(var in=new java.util.zip.InflaterInputStream(Files.newInputStream(cache.file(surface)))) { raw=in.readAllBytes(); }
            java.nio.ByteBuffer.wrap(raw).putInt(4,1);
            try(var out=new java.util.zip.DeflaterOutputStream(Files.newOutputStream(cache.file(surface)))) { out.write(raw); }
        }
        try(var reopened=new PredictionDiskCache(directory,77)) {
            try(var lease=reopened.lease(terrain)) { assertArrayEquals(samples(),reopened.readTerrain(lease,samples().length)); }
            try(var lease=reopened.lease(surface)) { assertNull(reopened.readSurface(lease)); }
            try(var lease=reopened.lease(surface)) { assertTrue(reopened.writeSurface(lease,edits)); }
            try(var lease=reopened.lease(surface)) { assertEquals(edits,reopened.readSurface(lease)); }
        }
    }

    @Test void compressedTerrainAndStatePaletteSurviveReopen() throws Exception {
        var terrainKey = PredictionDiskCache.Key.terrain(-3, 7, 2);
        var surfaceKey = PredictionDiskCache.Key.surface(-17, 23, 3);
        var samples = samples();
        var states = Map.of(new BlockPos(-270, 71, 375), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                new BlockPos(-270, 72, 375), Blocks.OAK_LEAVES.defaultBlockState(),
                new BlockPos(-269, 71, 375), Blocks.ORANGE_TERRACOTTA.defaultBlockState());
        try (var cache = new PredictionDiskCache(directory, 77)) {
            try (var lease = cache.lease(terrainKey)) { assertTrue(cache.writeTerrain(lease, samples)); }
            try (var lease = cache.lease(surfaceKey)) { assertTrue(cache.writeSurface(lease, states)); }
            long bytes = Files.size(cache.file(terrainKey));
            assertTrue(bytes < samples.length * 68 / 4, "smooth columns should compress without storing meshes");
            System.out.println("Prediction terrain fixture: columns=" + samples.length + ", rawBytes=" + samples.length * 68 + ", diskBytes=" + bytes);
        }
        try (var reopened = new PredictionDiskCache(directory, 77)) {
            assertEquals(0, reopened.hits(), "opening must not preload disk contents into memory");
            try (var lease = reopened.lease(terrainKey)) { assertArrayEquals(samples, reopened.readTerrain(lease, samples.length)); }
            try (var lease = reopened.lease(surfaceKey)) { assertEquals(states, reopened.readSurface(lease)); }
            assertEquals(2, reopened.hits());
        }
    }

    @Test void legacyTerrainWithoutColorsRemainsReadable() throws Exception {
        var key=PredictionDiskCache.Key.terrain(0,0,2);
        try(var cache=new PredictionDiskCache(directory,77)) {
            try(var lease=cache.lease(key)){assertTrue(cache.writeTerrain(lease,samples()));}
            byte[] raw;
            try(var in=new java.util.zip.InflaterInputStream(Files.newInputStream(cache.file(key)))){raw=in.readAllBytes();}
            // Schema 1 ends after samples; schema 2 appends fingerprint + colors-present.
            raw=java.util.Arrays.copyOf(raw,raw.length-9);
            java.nio.ByteBuffer.wrap(raw).putInt(4,1);
            try(var out=new java.util.zip.DeflaterOutputStream(Files.newOutputStream(cache.file(key)))){out.write(raw);}
            try(var lease=cache.lease(key)) {
                var restored=cache.readTerrainData(lease,0);
                assertNotNull(restored);assertArrayEquals(samples(),restored.samples());
                assertFalse(restored.colorsMatch(123));
            }
        }
    }

    @Test void coarsePreviewMarginIsIncludedInDirtyInvalidation() {
        // LOD 7 spans 8192 blocks; its eight-cell preview reads x=-1024.
        assertTrue(PredictionDiskCache.affected(-64,0).contains(PredictionDiskCache.Key.terrain(0,0,7)));
        assertFalse(PredictionDiskCache.affected(-65,0).contains(PredictionDiskCache.Key.terrain(0,0,7)));
    }

    @Test void dirtyChunkInvalidatesUnloadedParentsBordersAndNeighborDecorationOnly() {
        var affected = PredictionDiskCache.affected(-1, -1);
        var terrain = PredictionDiskCache.Key.terrain(-1, -1, 0);
        var border = PredictionDiskCache.Key.terrain(0, 0, 0);
        var parent = PredictionDiskCache.Key.terrain(0, 0, 10);
        var vegetation = PredictionDiskCache.Key.surface(1, -1, 3);
        var outside = PredictionDiskCache.Key.surface(2, -1, 3);
        assertTrue(affected.containsAll(List.of(terrain, border, parent, vegetation)));
        assertFalse(affected.contains(outside));
        try (var cache = new PredictionDiskCache(directory, 77)) {
            for (var key : List.of(terrain, border, parent)) try (var lease = cache.lease(key)) { assertTrue(cache.writeTerrain(lease, samples())); }
            for (var key : List.of(vegetation, outside)) try (var lease = cache.lease(key)) { assertTrue(cache.writeSurface(lease, Map.of())); }
        }
        try (var reopened = new PredictionDiskCache(directory, 77)) {
            reopened.invalidateChunk(-1, -1); // no grid/chunk has been loaded in this session
            try (var lease = reopened.lease(parent)) { assertNull(reopened.readTerrain(lease, samples().length)); }
            reopened.flush();
        }
        try (var reopened = new PredictionDiskCache(directory, 77)) {
            for (var key : List.of(terrain, border, parent)) try (var lease = reopened.lease(key)) { assertNull(reopened.readTerrain(lease, samples().length)); }
            try (var lease = reopened.lease(vegetation)) { assertNull(reopened.readSurface(lease)); }
            try (var lease = reopened.lease(outside)) { assertEquals(Map.of(), reopened.readSurface(lease), "empty result is a valid cached result"); }
        }
    }

    @Test void adjacentSurfaceCacheFilesShareParsedBlockStates() {
        var states = Map.of(new BlockPos(1, 65, 2), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(1, 66, 2), Blocks.OAK_LEAVES.defaultBlockState());
        try (var cache = new PredictionDiskCache(directory, 77)) {
            for (int x = 0; x < 12; x++) try (var lease = cache.lease(PredictionDiskCache.Key.surface(x, 0, 3))) {
                assertTrue(cache.writeSurface(lease, states));
            }
        }
        try (var cache = new PredictionDiskCache(directory, 77)) {
            for (int x = 0; x < 12; x++) try (var lease = cache.lease(PredictionDiskCache.Key.surface(x, 0, 3))) {
                assertEquals(states, cache.readSurface(lease));
            }
            assertEquals(12, cache.hits());
            assertEquals(2, cache.stateDecodes(), "warm terrain cannot reparse the same palette in every chunk file");
        }
    }

    @Test void dirtyNotificationAndNewSessionRejectLateWriters() {
        var key = PredictionDiskCache.Key.terrain(2, 3, 1);
        try (var older = new PredictionDiskCache(directory, 77); var stale = older.lease(key)) {
            older.invalidate(List.of(key));
            older.flush();
            assertFalse(older.writeTerrain(stale, samples()), "work started before dirty cannot restore the stale file");
            try (var beforeReconnect = older.lease(key); var newer = new PredictionDiskCache(directory, 77)) {
                assertFalse(older.writeTerrain(beforeReconnect, samples()));
                try (var current = newer.lease(key)) { assertTrue(newer.writeTerrain(current, samples())); }
                older.close();
                try (var read = newer.lease(key)) { assertNotNull(newer.readTerrain(read, samples().length)); }
            }
        }
    }

    @Test void interruptedLeaseCannotCommitAfterLeavingActiveSet() {
        try (var cache = new PredictionDiskCache(directory, 77)) {
            var key = PredictionDiskCache.Key.surface(1, 2, 3);
            var cancelled = cache.lease(key);
            cancelled.close();
            cache.invalidate(List.of(key));
            assertFalse(cache.writeSurface(cancelled, Map.of()));
        }
    }

    @Test void corruptionWrongIdentityAndTruncationAreMisses() throws Exception {
        var key = PredictionDiskCache.Key.terrain(2, 3, 1);
        Path file;
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease, samples()));
            file = cache.file(key);
        }
        byte[] valid = Files.readAllBytes(file);
        try (var changed = new PredictionDiskCache(directory, 88); var lease = changed.lease(key)) {
            assertNull(changed.readTerrain(lease, samples().length));
        }
        try (var cache = new PredictionDiskCache(directory, 77)) {
            Files.write(file, Arrays.copyOf(valid, valid.length / 2));
            try (var lease = cache.lease(key)) { assertNull(cache.readTerrain(lease, samples().length)); }
            Files.write(file, valid);
            var copied = PredictionDiskCache.Key.terrain(3, 3, 1);
            Files.copy(file, cache.file(copied));
            try (var lease = cache.lease(copied)) { assertNull(cache.readTerrain(lease, samples().length)); }
            try (var lease = cache.lease(key)) { assertArrayEquals(samples(), cache.readTerrain(lease, samples().length)); }
        }
    }

    private static ClientColumnSample[] samples() {
        var samples = new ClientColumnSample[66 * 66];
        int grass = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK), dirt = BuiltInRegistries.BLOCK.getId(Blocks.DIRT), stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        for (int i = 0; i < samples.length; i++) {
            int y = 60 + i / 66 / 4;
            samples[i] = new ClientColumnSample(y, Math.max(63, y), 0, grass, 0, 0, 0, 0, y < 63 ? 1 : 0,
                    ClientColumnSample.FLAG_SURFACE_ONLY, 0, dirt, stone, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        }
        return samples;
    }
}
