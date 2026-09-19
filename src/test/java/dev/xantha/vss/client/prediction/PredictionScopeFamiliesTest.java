package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionScopeFamiliesTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void matchesPairScanAcrossMixedDimensionsParentsChildrenAndRemoval() {
        var layout = VssLodLayout.of(8192, 6, true, false);
        var tiles = new HashMap<PredictionTileKey, PredictionTile>();
        var random = new Random(20260920);
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 100; i++) {
                var tile = tile(random.nextInt(64) - 32, random.nextInt(64) - 32,
                        random.nextInt(layout.levelCount()), random.nextBoolean(), random.nextBoolean(), layout);
                tiles.put(tile.key(), tile);
            }
            assertEquals(pairScan(tiles), PredictionScopeFamilies.find(tiles));
            tiles.entrySet().removeIf(entry -> random.nextInt(5) == 0);
            assertEquals(pairScan(tiles), PredictionScopeFamilies.find(tiles));
        }
        tiles.replaceAll((key, tile) -> new PredictionTile(key, tile.heights(), tile.groundHeights(),
                tile.samples(), tile.mesh(), tile.depthBound(), 0, 1, tile.cellAxis(), tile.spacingBlocks(), false));
        assertEquals(Set.of(), PredictionScopeFamilies.find(tiles), "ordinary-only view has no scoped families");
    }

    @Test void compareScopeFamilyWorkOnLargeResidentCache() {
        var layout = VssLodLayout.of(8192, 6, true, false);
        var source = new HashMap<PredictionTileKey, PredictionTile>();
        for (int lod = 0; lod < layout.levelCount(); lod++) for (int z = -16; z < 16; z++)
            for (int x = -16; x < 16; x++) {
                var tile = tile(x, z, lod, (x + 3 * z + lod) % 5 == 0, false, layout);
                source.put(tile.key(), tile);
            }
        Map<PredictionTileKey, PredictionTile> tiles = Map.copyOf(source);
        var previous = new ArrayList<Double>();
        var current = new ArrayList<Double>();
        for (int iteration = 0; iteration < 10; iteration++) {
            long start = System.nanoTime();
            Set<PredictionTileKey> expected = pairScan(tiles);
            long middle = System.nanoTime();
            Set<PredictionTileKey> actual = PredictionScopeFamilies.find(tiles);
            long end = System.nanoTime();
            assertEquals(expected, actual);
            if (iteration >= 4) {
                previous.add((middle - start) / 1e6);
                current.add((end - middle) / 1e6);
            }
        }
        Collections.sort(previous); Collections.sort(current);
        long scoped = tiles.values().stream().filter(PredictionTile::scopeOnly).count();
        System.out.printf(Locale.ROOT,
                "SCOPE_FAMILIES tiles=%d scoped=%d oldPairs=%d oldMedianMs=%.3f newMedianMs=%.3f (family index only; not FPS)%n",
                tiles.size(), scoped, scoped * tiles.size(), previous.get(3), current.get(3));
    }

    private static PredictionTile tile(int x, int z, int lod, boolean scoped, boolean nether, VssLodLayout layout) {
        var base = PredictionCoverageWorkTest.tile(x, z, lod, layout);
        var key = new PredictionTileKey(nether ? Level.NETHER : Level.OVERWORLD, x, z, lod);
        return new PredictionTile(key, base.heights(), base.groundHeights(), base.samples(), base.mesh(),
                base.depthBound(), 0, 1, base.cellAxis(), base.spacingBlocks(), scoped);
    }

    // Previous production implementation, retained only as an independent correctness/performance reference.
    private static Set<PredictionTileKey> pairScan(Map<PredictionTileKey, PredictionTile> tiles) {
        var families = new HashSet<PredictionTileKey>();
        for (var scoped : tiles.values()) {
            if (!scoped.scopeOnly()) continue;
            for (var candidate : tiles.keySet()) {
                var parent = candidate.lod() >= scoped.key().lod() ? candidate : scoped.key();
                var child = candidate.lod() >= scoped.key().lod() ? scoped.key() : candidate;
                int shift = parent.lod() - child.lod();
                if (parent.dimension().equals(child.dimension()) && (child.tileX() >> shift) == parent.tileX()
                        && (child.tileZ() >> shift) == parent.tileZ()) families.add(candidate);
            }
        }
        return Set.copyOf(families);
    }
}
