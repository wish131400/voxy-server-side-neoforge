package dev.xantha.vss.client.prediction;

import java.util.List;
import dev.xantha.vss.common.worldgen.TerraBlenderGrid;

/** Client bridge to the same weighted zoom stack used to inspect server End state. */
final class TerrablenderUniqueness {
    record Region(int index, int weight) { }
    interface Tree { int get(int x, int z); }
    private TerrablenderUniqueness() { }
    static Tree build(long seed, int size, List<Region> regions) {
        var tree = TerraBlenderGrid.build(seed, size,
                regions.stream().map(r -> new TerraBlenderGrid.Region(r.index(), r.weight())).toList());
        return tree::get;
    }
}
