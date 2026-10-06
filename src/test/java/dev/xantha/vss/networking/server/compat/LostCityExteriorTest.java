package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LostCityExteriorTest {
    @Test
    void publicGreeneryKeepsHollowHedgesAndOneGroundSurface() {
        int[] states = new int[256 * 2]; Arrays.fill(states, -1);
        Arrays.fill(states, 0, 256, 7);
        for (int z = 2; z < 6; z++) for (int x = 2; x < 6; x++)
            if (x == 2 || x == 5 || z == 2 || z == 5) states[256 + z * 16 + x] = 9;
        var model = LostCityExterior.surface(states, 2, 1);
        int ground = 0, hedge = 0, sides = 0, inward = 0;
        for (int q = 0; q < model.quadCount(); q++) {
            if (model.direction(q) == 0) {
                if (model.state(q) == 7) ground += model.dx(q) * model.dz(q);
                if (model.state(q) == 9) hedge += model.dx(q) * model.dz(q);
            } else {
                assertEquals(9, model.state(q), "foundation edge walls belong to terrain");
                sides += model.dy(q) * Math.max(model.dx(q), model.dz(q));
                if (model.direction(q) == 1 && model.z(q) == 5 || model.direction(q) == 2 && model.z(q) == 3
                        || model.direction(q) == 3 && model.x(q) == 5 || model.direction(q) == 4 && model.x(q) == 3)
                    inward += model.dy(q) * Math.max(model.dx(q), model.dz(q));
            }
        }
        assertEquals(244, ground); assertEquals(12, hedge);
        assertEquals(24, sides); assertEquals(8, inward);
        int[] base = new int[256]; Arrays.fill(base, 7);
        assertEquals(1, LostCityExterior.surface(base, 1, 1).quadCount());
    }

    @Test
    void patternedSurfaceAndWorstCaseDetailHaveBoundedDistantModels() {
        int[] states = new int[256 * 64];
        for (int y = 0; y < 64; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            states[y * 256 + z * 16 + x] = (x + y + z) % 2 == 0 ? (x + z * 16 + y * 256) % 100 + 1 : -1;
        assertTrue(LostCityExterior.surface(states, 64, 1).quadCount() <= LostCityPreview.MAX_MODEL_QUADS);
        assertTrue(LostCityExterior.surface(states, 64, 4, 512).quadCount() <= 512);
        int[] road = new int[256]; Arrays.fill(road, 7);
        Arrays.fill(road, 8 * 16, 9 * 16, 9);
        var model = LostCityExterior.surface(road, 1, 1);
        int roadArea = 0;
        for (int q = 0; q < model.quadCount(); q++) if (model.state(q) == 9) roadArea += model.dx(q) * model.dz(q);
        assertEquals(16, roadArea);
    }

    @Test
    void uniformSolidKeepsOnlyFourWallsAndRoofAtEachSpacing() {
        int[] states = new int[256 * 6];
        Arrays.fill(states, 7);
        for (int spacing : new int[] {1, 2, 4}) {
            var model = LostCityExterior.mesh(states, 6, spacing);
            assertEquals(5, model.quadCount());
            assertEquals(6, model.height());
            for (int d = 0; d <= 4; d++) {
                int area = 0;
                for (int q = 0; q < model.quadCount(); q++) if (model.direction(q) == d)
                    area += d == 0 ? model.dx(q) * model.dz(q)
                            : model.dy(q) * Math.max(model.dx(q), model.dz(q));
                assertEquals(d == 0 ? 256 : 96, area);
            }
        }
    }

    @Test
    void emptyTemplateProducesNoFakeBuilding() {
        int[] states = new int[256 * 6];
        Arrays.fill(states, -1);
        assertEquals(0, LostCityExterior.mesh(states, 6, 1).quadCount());
        assertNull(LostCityExterior.silhouette(List.of()));
    }

    @Test
    void windowBandsAndSteppedRoofsKeepTheirPaletteAndFootprint() {
        int[] states = new int[256 * 6];
        for (int y = 0; y < 6; y++) Arrays.fill(states, y * 256, (y + 1) * 256, y == 2 ? 9 : 7);
        for (int y = 3; y < 6; y++) for (int z = 8; z < 16; z++)
            Arrays.fill(states, y * 256 + z * 16 + 8, y * 256 + z * 16 + 16, -1);
        var model = LostCityExterior.mesh(states, 6, 1);
        assertTrue(java.util.stream.IntStream.range(0, model.quadCount())
                .anyMatch(q -> model.direction(q) == 1 && model.state(q) == 9));
        int lowRoof = 0, highRoof = 0;
        for (int q = 0; q < model.quadCount(); q++) if (model.direction(q) == 0) {
            int area = model.dx(q) * model.dz(q);
            if (model.y(q) == 3) lowRoof += area;
            if (model.y(q) == 6) highRoof += area;
        }
        assertEquals(64, lowRoof);
        assertEquals(192, highRoof);
        var distant = LostCityExterior.silhouette(List.of(new LostCityPreview.Placement(0, model)));
        assertEquals(6, distant.height());
        assertTrue(distant.quadCount() <= model.quadCount());
    }
}
