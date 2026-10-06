package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LostCityInfrastructurePlanTest {
    public static class Profile { public int GROUNDLEVEL = 64; public boolean HIGHWAY_SUPPORTS = true, BRIDGE_SUPPORTS = true; }
    public record Provider() { public Style getWorldStyle() { return new Style(); } }
    public record Style() { public Selector getPartSelector() { return new Selector(); } }
    public record Selector() { public Highway highwayParts() { return new Highway(); } public Rails railwayParts() { return new Rails(); } }
    public record Highway() {
        public List<String> bridge() { return List.of("bridge"); }
        public List<String> bridgeBi() { return List.of("crossing"); }
        public List<String> open() { return List.of("open"); }
        public List<String> tunnel() { return List.of("tunnel"); }
    }
    public record Rails() {
        public List<String> railsHorizontal() { return List.of("rail"); }
        public List<String> railsHorizontalWater() { return List.of("glass"); }
        public List<String> railsVertical() { return List.of("vertical"); }
        public List<String> railsVerticalWater() { return List.of("vertical-glass"); }
    }
    public record Rail(String type, int level, String direction) {
        public String getType() { return type; } public int getLevel() { return level; }
        public String getDirection() { return direction; }
    }
    public record Part(String name) { public Character getMetaChar(String key) { return 's'; } }
    public static class Chunk {
        public Provider provider = new Provider(); public Profile profile = new Profile();
        public int groundLevel = 64, waterLevel = 63;
        public int lx = -1, lz = -1, cityLevel; public boolean city;
        public Part xb, zb; public Chunk neighbour;
        public Rail rail = new Rail("NONE", -3, "WEST");
        public int getHighwayXLevel() { return lx; } public int getHighwayZLevel() { return lz; }
        public boolean isCity() { return city; } public int getCityLevel() { return cityLevel; }
        public Part hasXBridge(Provider p) { return xb; } public Part hasZBridge(Provider p) { return zb; }
        public Chunk getXmin() { return neighbour == null ? this : neighbour; } public Chunk getXmax() { return getXmin(); }
        public Chunk getZmin() { return getXmin(); } public Chunk getZmax() { return getXmin(); }
        public Rail getRailInfo() { return rail; }
    }
    @Test void nonCityHighwaysRetainHeightOrientationAndSupports() throws Exception {
        Chunk chunk = new Chunk(); chunk.lz = 2;
        var plan = LostCityInfrastructurePlan.read(chunk, -395, -244, 1, Part::new);
        assertEquals(1, plan.size()); var road = plan.get(0);
        assertEquals(new Part("bridge"), road.template()); assertEquals(76, road.y()); assertEquals(1, road.transform());
        assertEquals(2, road.pillars().size()); assertEquals(36, road.pillars().get(0).bottom());
        chunk.lx = 2;
        plan = LostCityInfrastructurePlan.read(chunk, -395, -244, 1, Part::new);
        assertEquals(1, plan.size()); assertEquals(new Part("crossing"), plan.get(0).template());
    }
    @Test void ordinaryBridgeTransposesInsteadOfRotatingAndPreservesOpenSpace() throws Exception {
        Chunk chunk = new Chunk(); chunk.zb = new Part("ordinary");
        var part = LostCityInfrastructurePlan.read(chunk, 0, 0, 1, Part::new).get(0);
        assertEquals(65, part.y()); assertEquals(5, part.transform());
        assertEquals(3, LostCityInfrastructurePlan.tx(2, 3, part.transform()));
        assertEquals(2, LostCityInfrastructurePlan.tz(2, 3, part.transform()));
        assertEquals(1, part.pillars().size()); assertEquals(2, part.pillars().get(0).width());
        chunk.lx = 0;
        var plan = LostCityInfrastructurePlan.read(chunk, 0, 0, 1, Part::new);
        assertTrue(plan.stream().noneMatch(p -> p.template().equals(chunk.zb)), "highway level zero suppresses ordinary bridge");
    }
    @Test void submergedRailVariantsAndStationExclusionFollowGenerator() throws Exception {
        Chunk chunk = new Chunk(); chunk.rail = new Rail("HORIZONTAL", -3, "EAST");
        var part = LostCityInfrastructurePlan.read(chunk, 0, 0, 1, Part::new).get(0);
        assertEquals(46, part.y()); assertEquals(new Part("glass"), part.water()); assertEquals(0, part.transform());
        chunk.neighbour = new Chunk(); chunk.neighbour.rail = new Rail("STATION_UNDERGROUND", -3, "WEST");
        assertNull(LostCityInfrastructurePlan.read(chunk, 0, 0, 1, Part::new).get(0).water());
        chunk.rail = new Rail("VERTICAL", -3, "EAST");
        part = LostCityInfrastructurePlan.read(chunk, 0, 0, 1, Part::new).get(0);
        assertEquals(new Part("vertical-glass"), part.water()); assertEquals(4, part.transform());
    }
    @Test void sixFaceModelsKeepUndersidesWithoutFillingHolesAtCoarseLod() {
        int[] voxels = new int[256 * 8]; Arrays.fill(voxels, -1);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) voxels[7 * 256 + z * 16 + x] = 1;
        for (int step : new int[]{1, 4}) {
            var mesh = LostCityExterior.infrastructure(voxels, 8, step, 512);
            assertEquals(6, mesh.quadCount());
            int bottomArea = 0;
            for (int q = 0; q < mesh.quadCount(); q++) {
                assertTrue(mesh.y(q) >= 7, "empty space below the deck must not be filled");
                if (mesh.direction(q) == 5) bottomArea += mesh.dx(q) * mesh.dz(q);
            }
            assertEquals(256, bottomArea);
        }
    }
}
