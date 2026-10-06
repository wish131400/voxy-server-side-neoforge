package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionVegetationRunsTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }

    @Test void simulationLeafPropertiesDoNotForceLargerVoxels() {
        int span=48, grid=49;
        var blocks=new HashMap<BlockPos,BlockState>();
        for(int z=0;z<span;z+=2)for(int x=0;x<span;x+=2)for(int y=80;y<144;y++)
            blocks.put(new BlockPos(x,y,z),Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE,1+y%6));
        var raw=PredictionVegetation.Tile.of(blocks,0,0,span,1,1);
        var samples=new ClientColumnSample[grid*grid];
        java.util.Arrays.fill(samples,new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN));
        java.util.function.Function<PredictionVegetation.Tile,PredictionMesh> build=tile ->
                PredictionMeshBuilder.build(samples,null,63,0,1,grid,null,null,0,0,tile);
        assertTrue(build.apply(raw).vertexCount() <= 262144,
                "equivalent rendered materials may merge before triangle admission");
        var bounded=PredictionVegetation.boundedTile(blocks,0,0,span,1,1);
        assertEquals(1,bounded.voxelSize(),"leaf simulation properties must not enlarge the canopy");
        assertEquals(blocks.keySet(),bounded.blocks().keySet(),"fine foliage never fills an empty block");
        var mesh=PredictionVegetation.meshWithinBudget(raw,span,1,build);
        assertTrue(mesh.vertexCount()>0 && mesh.vertexCount()<=262144);
        assertEquals(48/2*48/2*64,blocks.size(),"retry must retain original worldgen output");
    }

    @Test void boundedMeshRetryKeepsGroundEditsAndStopsAtItsLimit() {
        var p=new BlockPos(1,64,1);
        var tile=PredictionVegetation.Tile.of(Map.of(p,Blocks.FARMLAND.defaultBlockState(),
                new BlockPos(2,80,2),Blocks.OAK_LEAVES.defaultBlockState()),0,0,16,1,1);
        var sizes=new java.util.ArrayList<Integer>();
        assertThrows(PredictionMemoryBudget.MeshLimitException.class,()->PredictionVegetation.meshWithinBudget(tile,16,1,t -> {
            sizes.add(t.voxelSize());assertEquals(Blocks.FARMLAND.defaultBlockState(),t.blocks().get(p));
            throw new PredictionMemoryBudget.MeshLimitException();
        }));
        assertEquals(java.util.List.of(1),sizes,"unchanged geometry must not repeat the same failed mesh");
    }

    @Test void mergingPreservesEveryExposedFaceAndAirGap() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int y = 70; y < 80; y++) {
            if (y != 74) blocks.put(new BlockPos(1, y, 1), Blocks.OAK_LEAVES.defaultBlockState());
            if (y % 3 == 0) blocks.put(new BlockPos(2, y, 1), Blocks.OAK_LOG.defaultBlockState());
        }
        blocks.put(new BlockPos(1, 78, 1), Blocks.BIRCH_LEAVES.defaultBlockState());
        // Force the pressure path: every exposed lower layer must survive too.
        var tile = PredictionVegetation.boundedTile(blocks, 0, 0, 4, 2, 8).withExteriorEnvelope();
        Set<String> expected = new HashSet<>(), actual = new HashSet<>();
        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.getY() < 72) continue;
            for (int face = 0; face <= 4; face++) {
                int dx = face == 3 ? -1 : face == 4 ? 1 : 0;
                int dz = face == 1 ? -1 : face == 2 ? 1 : 0;
                if (!blocks.containsKey(pos.offset(dx, face == 0 ? 1 : 0, dz)))
                    expected.add(key(pos.getX(), pos.getY(), pos.getZ(), face, entry.getValue()));
            }
        }
        for (int cell : tile.cells().keySet()) {
            for (var face : PredictionVegetationRuns.faces(tile, cell, (x,z) -> 72)) {
                for (int y = face.bottom(); y < face.top(); y++)
                    assertTrue(actual.add(key(face.x(), y, face.z(), face.direction(), face.state())),
                            "merged faces must not overlap");
            }
        }
        assertEquals(expected, actual);
    }

    @Test void denseFineCanopiesStayOneBlockAndFitExistingMeshBudget() {
        int span = 48, grid = span + 1;
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // More than 800,000 unmerged vertices; old fallback expanded these into large cubes.
        for (int z = 0; z < span; z += 2) for (int x = 0; x < span; x += 2)
            for (int y = 80; y < 144; y++) blocks.put(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState());
        var tile = PredictionVegetation.boundedTile(blocks, 0, 0, span, 1, 1);
        assertEquals(1, tile.voxelSize());
        assertEquals(blocks, tile.blocks());
        assertTrue(tile.cells().values().stream().flatMap(java.util.List::stream).allMatch(v -> v.size() == 1));
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        int[] colors = new int[samples.length];
        java.util.Arrays.fill(samples, new ClientColumnSample(64, 64, 0, ClientColumnSample.NO_BLOCK,
                0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        java.util.Arrays.fill(colors, 0xff65934a);
        var mesh = PredictionMeshBuilder.build(samples, colors, 63, 0, 1, grid, colors, null, 0, 0, tile);
        assertTrue(mesh.vertexCount() < 100_000, "merge before triangle allocation, without increasing its hard cap");
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            if (mesh.y(vertex) <= 64) continue;
            assertTrue(mesh.y(vertex) >= 80 && mesh.y(vertex) <= 144);
        }
        var packed = mesh.packed();
        assertTrue(packed.quadCount() > 0);
        for (int quad = 0; quad < packed.quadCount(); quad++) {
            if (packed.y(quad, 0) <= 64) continue;
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                minX = Math.min(minX, packed.x(quad, corner)); maxX = Math.max(maxX, packed.x(quad, corner));
                minZ = Math.min(minZ, packed.z(quad, corner)); maxZ = Math.max(maxZ, packed.z(quad, corner));
            }
            assertTrue(maxX - minX <= 1 && maxZ - minZ <= 1,
                    "packed faces must not bridge the empty space between canopies");
        }
        System.out.println("DENSE_CANOPY blocks=" + blocks.size() + ", vertices=" + mesh.vertexCount());
    }

    @Test void denseLayeredCrownsRetainLowerTiersWithinMeshBudget() {
        int span=48, grid=span+1;
        var blocks=new HashMap<BlockPos,BlockState>();
        for(int z=0;z<span;z+=6) for(int x=0;x<span;x+=6) {
            for(int y=64;y<90;y++) blocks.put(new BlockPos(x+2,y,z+2),Blocks.SPRUCE_LOG.defaultBlockState());
            for(int y=70;y<90;y+=3) for(int dz=0;dz<5;dz++) for(int dx=0;dx<5;dx++)
                if(dx!=2 || dz!=2) blocks.put(new BlockPos(x+dx,y,z+dz),Blocks.SPRUCE_LEAVES.defaultBlockState());
        }
        var tile=PredictionVegetation.boundedTile(blocks,0,0,span,1,8);
        assertEquals(blocks,tile.blocks());
        var samples=new ClientColumnSample[grid*grid];
        java.util.Arrays.fill(samples,PredictionExteriorColumnsTest.surface(64));
        var mesh=PredictionMeshBuilder.build(samples,null,63,0,1,grid,null,null,0,0,tile);
        assertTrue(mesh.vertexCount()<262144,"restored tiers must fit the existing tile limit");
        Set<Integer> tiers=new HashSet<>();
        for(int cell:tile.cells().keySet()) for(var face:PredictionVegetationRuns.faces(tile,cell,(x,z)->64))
            if(face.x()==0 && face.z()==0 && face.direction()==0) tiers.add(face.bottom());
        assertEquals(Set.of(70,73,76,79,82,85,88),tiers);
        System.out.println("LAYERED_FOREST blocks="+blocks.size()+" vertices="+mesh.vertexCount());
    }

    @Test void adjacentStatesAreNotMergedAcrossDifferentMaterials() {
        var blocks = Map.of(new BlockPos(0, 80, 0), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(0, 81, 0), Blocks.OAK_LEAVES.defaultBlockState());
        var tile = PredictionVegetation.boundedTile(blocks, 0, 0, 2, 1, 1);
        for (var face : PredictionVegetationRuns.faces(tile, 0, (x,z) -> 64))
            assertEquals(1, face.top() - face.bottom());
    }

    @Test void pressureEnvelopeKeepsOriginalPositionsSpeciesAndEveryCanopyPeak() {
        var blocks=new HashMap<BlockPos,BlockState>();
        for(int z=-8;z<8;z++)for(int x=-8;x<8;x++) {
            if(Math.floorMod(x,4)==0)continue; // Gaps must stay open even under pressure.
            for(int y=70;y<84+Math.floorMod(x+z,5);y++) {
                if(y==76)continue; // Disconnected layers must never get bridged.
                blocks.put(new BlockPos(x,y,z),x<0?Blocks.BIRCH_LEAVES.defaultBlockState():Blocks.OAK_LEAVES.defaultBlockState());
            }
        }
        blocks.put(new BlockPos(-1,65,-1),Blocks.FARMLAND.defaultBlockState());
        var tile=PredictionVegetation.boundedTile(blocks,-8,-8,16,1,8);
        assertEquals(1,tile.voxelSize());
        assertEquals(blocks,tile.blocks(),"no filled holes, shifted positions, enlarged voxels or repainted species");
        var tops=new HashMap<Long,Integer>();
        blocks.forEach((p,s)->tops.merge((long)p.getX()<<32|p.getZ()&0xffffffffL,p.getY(),Math::max));
        Set<Long> visiblePeaks=new HashSet<>();
        for(int cell:tile.cells().keySet())for(var face:PredictionVegetationRuns.faces(tile,cell,(x,z)->Integer.MIN_VALUE)) {
            int wx=face.x()-8,wz=face.z()-8;
            if(face.direction()==0)visiblePeaks.add((long)wx<<32|wz&0xffffffffL);
            for(int y=face.bottom();y<face.top();y++)
                assertEquals(face.state(),blocks.get(new BlockPos(wx,y,wz)),"every face must belong to an original block");
        }
        assertEquals(tops.keySet(),visiblePeaks,"retain every column's upper outline");
    }

    @Test void runsMatchEveryOriginalFaceAcrossCellsFloorsGapsAndMaterials() {
        var blocks = new HashMap<BlockPos, BlockState>();
        var random = new java.util.Random(48271);
        for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) for (int y = 68; y < 82; y++) {
            if (random.nextInt(5) == 0) continue;
            blocks.put(new BlockPos(x - 32, y, z - 48), random.nextBoolean()
                    ? Blocks.OAK_LEAVES.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState());
        }
        var tile = PredictionVegetation.Tile.of(blocks, -32, -48, 16, 4, 1);
        java.util.function.IntBinaryOperator floor = (x, z) -> 70 + Math.floorMod(x + z, 3);
        Set<String> expected = new HashSet<>(), actual = new HashSet<>();
        for (var voxels : tile.cells().values()) for (var voxel : voxels) {
            if (voxel.y() < floor.applyAsInt(voxel.x(), voxel.z())) continue;
            for (int face = 0; face <= 5; face++) {
                int dx = face == 3 ? -1 : face == 4 ? 1 : 0;
                int dy = face == 0 ? 1 : face == 5 ? -1 : 0;
                int dz = face == 1 ? -1 : face == 2 ? 1 : 0;
                if (!tile.occupied(voxel.x() + dx, voxel.y() + dy, voxel.z() + dz))
                    expected.add(key(voxel.x(), voxel.y(), voxel.z(), face, voxel.state()));
            }
        }
        for (int cell : tile.cells().keySet()) for (var face : PredictionVegetationRuns.faces(tile, cell, floor, true)) {
            for (int y = face.bottom(); y < face.top(); y++)
                assertTrue(actual.add(key(face.x(), y, face.z(), face.direction(), face.state())), "no overlapping runs");
        }
        assertEquals(expected, actual);
    }

    private static String key(int x, int y, int z, int direction, BlockState state) {
        return x + "," + y + "," + z + "," + direction + ":" + state;
    }
}
