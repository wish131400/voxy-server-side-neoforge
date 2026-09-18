package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

/** Opt-in replay of copied disk entries; never open a live save for writes. */
class PredictionLiveMeshReplayTest {
    @Test void replayPersistedMeshes() throws Exception {
        String dir = System.getenv("VSS_MESH_REPLAY_DIR");
        Assumptions.assumeTrue(dir != null);
        PredictionVegetationTest.bootstrap();
        try {
            var tags = new HashMap<>(BuiltInRegistries.BLOCK.getTags().collect(java.util.stream.Collectors.toMap(
                    p -> p.getFirst(), p -> p.getSecond().stream().toList())));
            tags.put(BlockTags.LEAVES, BuiltInRegistries.BLOCK.stream().filter(b -> b instanceof LeavesBlock)
                    .map(b -> (Holder<Block>) b.builtInRegistryHolder()).toList());
            tags.put(BlockTags.LOGS, BuiltInRegistries.BLOCK.stream().filter(b -> BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_log"))
                    .map(b -> (Holder<Block>) b.builtInRegistryHolder()).toList());
            BuiltInRegistries.BLOCK.bindTags(tags);
            Path root = Path.of(dir);
            var config = JsonParser.parseString(Files.readString(root.resolve("replay.json"))).getAsJsonObject();
            try (var cache = new PredictionDiskCache(root, config.get("fingerprint").getAsLong())) {
                for (var item : config.getAsJsonArray("tiles")) {
                    var pair = item.getAsJsonArray(); int tx=pair.get(0).getAsInt(), tz=pair.get(1).getAsInt();
                    PredictionDiskCache.TerrainData data;
                    try (var lease = cache.lease(PredictionDiskCache.Key.terrain(tx,tz,1))) {
                        data = cache.readTerrainData(lease,0); assertNotNull(data);
                    }
                    int[] materialColors=new int[data.samples().length];
                    for(int i=0;i<materialColors.length;i++){var c=data.samples()[i];
                        materialColors[i]=PredictionLighting.shade(PredictionMaterialPalette.colorFor(c,data.surfaceTints()[i]),c.surfaceY(),63,false,c.fluid()!=0);}
                    var blocks = new HashMap<BlockPos,BlockState>(); int missing=0;
                    for (int cz=tz*8-1;cz<=tz*8+8;cz++) for (int cx=tx*8-1;cx<=tx*8+8;cx++) {
                        try (var lease=cache.lease(PredictionDiskCache.Key.surface(cx,cz,7))) {
                            var chunk=cache.readSurface(lease); if(chunk==null){missing++;continue;}
                            chunk.forEach((p,s)->{if(p.getX()>=tx*128-1 && p.getX()<tx*128+129 && p.getZ()>=tz*128-1 && p.getZ()<tz*128+129)blocks.put(p,s);});
                        }
                    }
                    assertEquals(0,missing,"replay needs complete chunk inputs");
                    var bounded=PredictionVegetation.boundedTile(blocks,tx*128,tz*128,128,2,1);
                    var result=PredictionVegetation.meshWithinBudget(bounded,128,2,t -> PredictionMeshBuilder.build(
                            data.samples(),materialColors,63,0xb22d78c5,2,(int)Math.sqrt(data.samples().length),true,null,
                            data.foliageTints(),data.waterTints(),tx*128,tz*128,t));
                    assertTrue(result.vertexCount()>0 && result.vertexCount()<=262144);
                    PredictionMeshMemoryTest.assertPublishedCompaction(result, 2);
                    var kinds = new TreeMap<String,Integer>(); blocks.values().forEach(s->kinds.merge(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(),1,Integer::sum));
                    System.out.println("REPLAY tile="+tx+","+tz+" missing="+missing+" blocks="+blocks.size()+" kinds="+kinds);
                    for (int size : new int[]{0,1,2,4,8}) {
                        long started=System.nanoTime();
                        var tile=size==0 ? PredictionVegetation.Tile.of(blocks,tx*128,tz*128,128,2,1) : PredictionVegetation.boundedTile(blocks,tx*128,tz*128,128,2,size);
                        try {
                            var mesh=PredictionMeshBuilder.build(data.samples(),materialColors,63,0xb22d78c5,2,(int)Math.sqrt(data.samples().length),true,null,data.foliageTints(),data.waterTints(),tx*128,tz*128,tile);
                            System.out.println("REPLAY size="+size+" actual="+tile.voxelSize()+" vertices="+mesh.vertexCount()+" ms="+(System.nanoTime()-started)/1e6);
                        } catch(PredictionMemoryBudget.MeshLimitException e) {System.out.println("REPLAY size="+size+" actual="+tile.voxelSize()+" LIMIT ms="+(System.nanoTime()-started)/1e6);}
                    }
                }
            }
        } finally {PredictionVegetationTest.restoreTags();}
    }
}
