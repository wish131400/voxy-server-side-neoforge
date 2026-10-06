package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.util.*;
import org.junit.jupiter.api.Test;

class LostCityStreetPlanTest {
    public record Rail(String getType, int getLevel) { }
    public record Settings(boolean parkElevation, boolean parkBorder) { }
    public static class Profile { public boolean PARK_ELEVATION = true, PARK_BORDER = true; }
    public record Streets() {
        public List<String> none() { return List.of("none"); }
        public List<String> end() { return List.of("end"); }
        public List<String> straight() { return List.of("straight"); }
        public List<String> bend() { return List.of("bend"); }
        public List<String> t() { return List.of("t"); }
        public List<String> all() { return List.of("all"); }
        public List<String> full() { return List.of("full"); }
        public List<String> stair() { return List.of("stair"); }
        public List<String> connector() { return List.of("connector"); }
    }
    public record Style(Boolean getParkElevation, Boolean getParkBorder) {
        public Streets getStreetParts() { return new Streets(); }
        public Streets getLargeStreetParts() { return new Streets(); }
        public Streets getTertiaryStreetParts() { return new Streets(); }
    }
    public static class Chunk {
        public String streetType = "NORMAL", plannedRoadType = "SECONDARY";
        public Profile profile = new Profile();
        public Object provider = new Object(), parkType, fountainType, frontType, slope;
        public boolean elevated, city = true, building, primary, dontconnect;
        public int level, floors = 2, highway = -1;
        public Rail rail = new Rail("NONE", -1);
        public Settings settings;
        public Chunk w, e, n, s;
        public boolean isElevatedParkSection() { return elevated; }
        public boolean isHierarchicalOpen() { return primary; }
        public boolean isPrimaryRoad() { return primary; }
        public Object getStreetSlopeDirection() { return slope; }
        public Settings getEffectiveCitySettings() { return settings; }
        public Chunk getXmin() { return w; } public Chunk getXmax() { return e; }
        public Chunk getZmin() { return n; } public Chunk getZmax() { return s; }
        public boolean doesRoadExtendTo() { return city && !building && !elevated; }
        public int getCityLevel() { return level; } public int getNumFloors() { return floors; }
        public Object getBuildingId() { return building ? "building" : null; }
        public int getHighwayXLevel() { return highway; } public int getHighwayZLevel() { return -1; }
        public int getMaxHighwayLevel() { return highway; }
        public Rail getRailInfo() { return rail; }
        public Object hasXBridge(Object provider) { return null; }
        public Object hasZBridge(Object provider) { return null; }
        public boolean isValidFloor(int f) { return f >= 0 && f < floors; }
        public Chunk getFloor(int f) { return this; }
        public boolean getMetaBoolean(String key) { assertEquals("dontconnect", key); return dontconnect; }
    }
    public record Slope(String getRotation) { }

    @Test void allSixteenConnectionMasksUseGeneratorTopologyAndRotation() {
        String[] shape = {"none","end","end","straight","end","bend","bend","t",
                "end","bend","bend","t","straight","t","t","all"};
        int[] turns = {0,0,2,0,1,0,1,0,3,3,2,2,1,3,1,0};
        for (int mask = 0; mask < 16; mask++) assertEquals(new LostCityStreetPlan.Road(shape[mask], turns[mask]), LostCityStreetPlan.road(mask));
    }
    @Test void selectedParkAndFrontPartsKeepTheirOwnerHeightAndRotation() throws Exception {
        Chunk park = new Chunk(); park.elevated = true; park.parkType = new Object();
        var layout = LostCityStreetPlan.read(park, new Style(null, null), 0, 0, 0, true, name -> name);
        assertTrue(layout.park()); assertEquals(1, layout.elevation());
        assertSame(park.parkType, layout.parts().get(0).template()); assertEquals(1, layout.parts().get(0).y());
        park.settings = new Settings(false, false);
        layout = LostCityStreetPlan.read(park, new Style(true, true), 0, 0, 0, true, name -> name);
        assertEquals(0, layout.elevation()); assertFalse(layout.parkBorder());

        Chunk street = new Chunk(); street.streetType = "PARK"; // A 7.4 planner value overwritten during generation.
        Chunk front = new Chunk(); front.building = true; front.frontType = new Object();
        street.n = front;
        layout = LostCityStreetPlan.read(street, new Style(null, null), 1, 0, 0, true, name -> name);
        assertFalse(layout.park());
        var part = layout.parts().stream().filter(p -> p.template() == front.frontType).findFirst().orElseThrow();
        assertSame(front, part.paletteOwner()); assertEquals(1, part.y()); assertEquals(1, part.turns()); assertTrue(part.clearAir());
        front.dontconnect = true;
        layout = LostCityStreetPlan.read(street, new Style(null, null), 1, 0, 0, true, name -> name);
        assertFalse(layout.parts().stream().anyMatch(p -> p.template() == front.frontType));
        assertEquals("PARK", street.streetType, "preview never mutates planner state");
    }
    @Test void roadsUseBothLevelsAndModernPrimaryConnectionsAndSlopes() throws Exception {
        Chunk street = new Chunk(); street.w = new Chunk(); street.e = new Chunk(); street.e.level = 1;
        var layout = LostCityStreetPlan.read(street, new Style(null, null), 0, 0, 4, false, name -> name);
        assertEquals(1, layout.connections()); assertEquals("end", layout.parts().get(0).template());
        street.primary = true; street.plannedRoadType = "PRIMARY";
        layout = LostCityStreetPlan.read(street, new Style(null, null), 0, 0, 4, true, name -> name);
        assertEquals(0, layout.connections());
        assertTrue(layout.parts().stream().anyMatch(p -> "connector".equals(p.template())));
        street.slope = new Slope("ROTATE_270");
        layout = LostCityStreetPlan.read(street, new Style(null, null), 0, 0, 4, true, name -> name);
        assertEquals(1, layout.parts().size()); assertEquals("stair", layout.parts().get(0).template());
        assertEquals(3, layout.parts().get(0).turns());
    }
    @Test void highwayAndSurfaceStationsDoNotClaimTemplateSurfaces() throws Exception {
        Chunk street = new Chunk(); street.highway = 0;
        var layout = LostCityStreetPlan.read(street, new Style(null, null), 0, 0, 0, true, name -> name);
        assertFalse(layout.surface()); assertTrue(layout.parts().isEmpty());
        street.highway = -1; street.rail = new Rail("STATION_SURFACE", 0);
        assertFalse(LostCityStreetPlan.read(street, null, 0, 0, 0, true, name -> name).surface());
    }
}
