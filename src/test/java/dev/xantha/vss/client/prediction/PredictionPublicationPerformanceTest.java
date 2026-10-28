package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.util.zip.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionPublicationPerformanceTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}
    @Test void compareVoxelCompressionLevelsWithoutChangingDecodedContent() throws Exception {
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,3);
        var tile=PredictionVoxelCacheTest.tile(key,64,true);var base=tile.data().voxyTile;
        var boxes=new PredictionVoxyBoxes.Builder();var random=new java.util.Random(20261028);
        for(int i=0;i<539_773;i++) {
            int x=random.nextInt(512),z=random.nextInt(512),y=64+random.nextInt(60);
            boxes.add(x,y,z,x+1,y+1,z+1,(i%4==0?Blocks.OAK_LOG:Blocks.OAK_LEAVES).defaultBlockState());
        }
        tile.data().voxyTile=new PredictionVoxySource.Tile(key,0,0,512,64,8,base.columns(),boxes.build(),base.terrainBytes(),true,0);
        byte[] raw;
        try(var disk=new PredictionDiskCache(directory,77)) {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)) {PredictionVoxelCache.write(out,PredictionVoxelCache.Data.snapshot(tile),3,new byte[32],null,disk);}
            raw=bytes.toByteArray();
        }
        for(int warmup=0;warmup<2;warmup++) {compress(raw,1);compress(raw,6);}
        long[] nanos=new long[2];byte[][] encoded=new byte[2][];
        for(int repeat=0;repeat<5;repeat++)for(int level:new int[]{1,6}) {
            int i=level==1?0:1;long started=System.nanoTime();encoded[i]=compress(raw,level);nanos[i]+=System.nanoTime()-started;
        }
        for(var data:encoded)try(var in=new InflaterInputStream(new ByteArrayInputStream(data))){assertArrayEquals(raw,in.readAllBytes());}
        System.out.println("VSS_COMPRESSION_BENCHMARK={\"features\":539773,\"rawBytes\":"+raw.length
                +",\"level1Bytes\":"+encoded[0].length+",\"level6Bytes\":"+encoded[1].length
                +",\"level1MeanMs\":"+nanos[0]/5_000_000.0+",\"level6MeanMs\":"+nanos[1]/5_000_000.0+"}");
    }
    private static byte[] compress(byte[] raw,int level)throws IOException {
        var bytes=new ByteArrayOutputStream();var compressor=new Deflater(level);
        try(var out=new DeflaterOutputStream(bytes,compressor,32768)){out.write(raw);}finally{compressor.end();}
        return bytes.toByteArray();
    }
}
