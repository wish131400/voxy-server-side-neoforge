package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.*;

class PredictionVillageGroundTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void surfaceExtractionRetainsRoadsIrrigationAndCutsButExcludesBuriedPieces() {
        var sampler = sampler();
        var level = new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,0,0);
        level.setBlock(new BlockPos(0,63,0),Blocks.DIRT_PATH.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(1,63,0),Blocks.FARMLAND.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(2,63,0),Blocks.WATER.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(3,63,0),Blocks.AIR.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(3,62,0),Blocks.AIR.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(3,61,0),Blocks.COBBLESTONE.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(4,30,0),Blocks.COBBLESTONE.defaultBlockState(),0,0);
        var blocks = PredictionVegetation.surfaceBlocks(level);
        assertEquals(6,blocks.size());
        assertTrue(blocks.get(new BlockPos(2,63,0)).is(Blocks.WATER));
        assertTrue(blocks.get(new BlockPos(3,62,0)).isAir());
        assertFalse(blocks.containsKey(new BlockPos(4,30,0)));
    }

    @Test void roadAndFarmlandReplaceGrassAtAllSurfaceDetailLevels() {
        for (int step : new int[]{1,2,4,8}) {
            var blocks = Map.of(new BlockPos(0,63,0),Blocks.DIRT_PATH.defaultBlockState(),
                    new BlockPos(1,63,0),Blocks.FARMLAND.defaultBlockState(),
                    new BlockPos(0,63,1),Blocks.WATER.defaultBlockState());
            var tile=PredictionVegetation.boundedTile(blocks,0,0,2*step,step,Math.max(1,step/2));
            assertTrue(tile.cells().values().stream().flatMap(List::stream).allMatch(v->v.size()==1));
            var mesh=mesh(step,tile);
            assertEquals(0,topArea(mesh,64,0,0,2,1),1e-6,"old grass must not cover the road or farm at step="+step);
            assertEquals(2,topArea(mesh,63.9375F,0,0,2,1),1e-6,"vanilla 15/16 block surfaces");
            assertEquals(6,mesh.waterVertexCount(),"irrigation exists even when the terrain column was dry");
            assertTrue(mesh.waterPositions[1] > 63 && mesh.waterPositions[1] < 64);
            assertTrue(mesh.packed().quadCount()>0);
            assertPackedArea(mesh, step, 64, 4*step*step-3);
            assertPackedArea(mesh, step, 63.9375F, 2);
        }
    }

    @Test void airCutsLowerOnlyTheirFootprintAndKeepAnExposedFloor() {
        var blocks=Map.of(new BlockPos(0,63,0),Blocks.AIR.defaultBlockState(),new BlockPos(0,62,0),Blocks.AIR.defaultBlockState());
        var mesh=mesh(4,PredictionVegetation.Tile.of(blocks,0,0,8,4,1));
        assertEquals(0,topArea(mesh,64,0,0,1,1),1e-6);
        assertEquals(1,topArea(mesh,62,0,0,1,1),1e-6);
        assertEquals(63,topArea(mesh,64,0,0,8,8),1e-6);
        // A house above the land does not cut away the ground under its footprint.
        mesh=mesh(1,PredictionVegetation.Tile.of(Map.of(new BlockPos(0,68,0),Blocks.OAK_PLANKS.defaultBlockState()),0,0,2,1,1));
        assertEquals(4,topArea(mesh,64,0,0,2,2),1e-6);
    }

    @Test void originalVillageFarmTemplateSurvivesExtractionAndMeshing() throws Exception {
        String id="minecraft:village/plains/houses/plains_small_farm_1";
        JsonObject templates=new JsonObject();
        try (var input=getClass().getResourceAsStream("/data/minecraft/structure/village/plains/houses/plains_small_farm_1.nbt")) {
            assertNotNull(input); templates.addProperty(id,Base64.getEncoder().encodeToString(input.readAllBytes()));
        }
        var template=PredictionStructureTemplates.open(templates).getOrCreate(ResourceLocation.parse(id));
        var sampler=sampler(); var level=new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,0,0);
        assertTrue(template.placeInWorld(level,new BlockPos(0,62,0),BlockPos.ZERO,new StructurePlaceSettings(),RandomSource.create(42),2));
        var blocks=PredictionVegetation.surfaceBlocks(level);
        assertTrue(blocks.values().stream().anyMatch(s->s.is(Blocks.FARMLAND)),"original farm ground survives below the former surface");
        assertTrue(blocks.values().stream().anyMatch(s->s.is(Blocks.WATER)));
        var mesh=meshWithGrid(1,PredictionVegetation.Tile.of(blocks,0,0,16,1,1),17);
        assertTrue(mesh.waterVertexCount()>0);
        for (var e:blocks.entrySet()) if (e.getValue().is(Blocks.FARMLAND) && e.getKey().getY()<64) {
            var p=e.getKey();
            assertEquals(0,topArea(mesh,64,p.getX(),p.getZ(),p.getX()+1,p.getZ()+1),1e-6,"grass covering original farmland "+p);
        }
    }

    private static PredictionMesh mesh(int step,PredictionVegetation.Tile tile) { return meshWithGrid(step,tile,3); }
    private static void assertPackedArea(PredictionMesh mesh,int step,float height,double expected) {
        var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.withDefaultNamespace("overworld"));
        var key=new PredictionTileManager.PredictionTileKey(dimension,0,0,0);
        var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                mesh,new PredictionDepthBound(60,70),0L,1L,mesh.cellAxis(),step);
        var packed=PredictionPackedMesh.pack(tile); int[] words=packed.quads(); double area=0;
        for(int q=0;q<packed.terrainQuadCount();q++) {
            int at=q*12, flags=words[at+6];
            if(((flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT)&3)!=0) continue;
            boolean fine=(flags&PredictionPackedMesh.FLAG_FINE_COORDINATES)!=0;
            double scale=fine ? 1.0/16 : 1 << ((flags >>> 16)&15);
            double y=((words[at+4]&65535)-32768)/(fine ? 16.0 : 4.0);
            if(y!=height) continue;
            double x0=(words[at]&65535)*scale,x1=(words[at]>>>16)*scale;
            double z0=(words[at+2]&65535)*scale,z1=(words[at+3]>>>16)*scale;
            area+=(x1-x0)*(z1-z0);
        }
        assertEquals(expected,area,1e-6,"GPU packing must retain exact cut footprint and 15/16 height at step="+step);
    }
    private static PredictionMesh meshWithGrid(int step,PredictionVegetation.Tile tile,int grid) {
        var samples=new ClientColumnSample[grid*grid]; Arrays.fill(samples,column());
        return PredictionMeshBuilder.build(samples,null,63,0xB23F76E4,step,grid,true,null,null,null,0,0,tile);
    }
    private static double topArea(PredictionMesh mesh,float height,float x0,float z0,float x1,float z1) {
        double area=0;
        for(int v=0;v<mesh.vertexCount();v+=6) {
            int p=v*3;
            if(mesh.normals[p+1]!=1 || mesh.positions[p+1]!=height) continue;
            float a=Math.max(x0,mesh.positions[p]),b=Math.max(z0,mesh.positions[p+2]);
            float c=Math.min(x1,mesh.positions[p+3]),d=Math.min(z1,mesh.positions[p+17]);
            if(c>a && d>b) area+=(c-a)*(d-b);
        }
        return area;
    }
    private static ClientColumnSample column() {
        return new ClientColumnSample(64,64,0,BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK),0,0,0,0,0,0,0,
                BuiltInRegistries.BLOCK.getId(Blocks.DIRT),BuiltInRegistries.BLOCK.getId(Blocks.STONE),
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
    }
    private static ClientTerrainSampler sampler() {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        return new ClientTerrainSampler(42,profile) { @Override public ClientColumnSample sample(int x,int z) { return column(); } };
    }
}
