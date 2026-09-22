package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Experiment only: geometry + exact seam summary, never a live cache writer.
 * Common sampling/IO, runtime resource-ID remapping and dynamic handoff are excluded. */
@EnabledIfEnvironmentVariable(named="VSS_FINISHED_MESH_BENCH",matches=".+")
class PredictionFinishedMeshCacheBenchmark {
    private static volatile Object sink;
    private record Snapshot(int axis, int terrain, int sprites, boolean down,
                            int[] words, int[] ranges, float[] morph, int minY, int maxY,
                            float[] topsY, int[] colors, boolean[] tops, long[] planes, int[] offsets, int[] walls) { }

    @Test void compareRebuildingCachedInputsWithRestoringExactFinishedPayload() throws Exception {
        PredictionVegetationTest.bootstrap();
        Path output=Path.of(System.getenv("VSS_FINISHED_MESH_BENCH"));
        Files.createDirectories(output);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());bean.setThreadAllocatedMemoryEnabled(true);
        long tid=Thread.currentThread().threadId();
        StringBuilder report=new StringBuilder("case,quads,rawBytes,zlibBytes,buildMs,restoreMs,encodeMs,bulkRestoreMs,buildAllocatedBytes,restoreAllocatedBytes,bulkAllocatedBytes\n");
        for(int scenario=0;scenario<3;scenario++) {
            int grid=scenario==0?18:66;
            var samples=new ClientColumnSample[grid*grid];
            var blocks=new HashMap<BlockPos,BlockState>();
            for(int z=0;z<grid;z++) for(int x=0;x<grid;x++) {
                int y=x<grid/3?52:68+(x*13+z*7)%11;
                samples[z*grid+x]=new ClientColumnSample(y,x<grid/3?63:y,0,
                        PredictionMaterialPalette.grassBlockIndex(),0,0,0,0,x<grid/3?1:0,0,0,
                        ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
                if(scenario==2 && x>grid/3 && x%5==0 && z%5==0) {
                    for(int dy=0;dy<5;dy++) blocks.put(new BlockPos(x,y+dy,z),Blocks.OAK_LOG.defaultBlockState());
                    for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                        blocks.put(new BlockPos(x+dx,y+5,z+dz),Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
            var vegetation=PredictionVegetation.Tile.of(blocks,0,0,grid-2,1,1);
            Snapshot expected=build(samples,grid,vegetation);
            String name=new String[]{"small-water-cliffs","detailed-water-cliffs","detailed-vegetation"}[scenario];
            byte[] raw=encode(expected,false),encoded=encode(expected,true);
            Path file=output.resolve(name+".mesh-experiment");Files.write(file,encoded);
            verify(expected,decode(Files.readAllBytes(file)));
            long[][] nanos=new long[4][31],allocated=new long[4][31];
            for(int round=-8;round<31;round++) for(int order=0;order<4;order++) {
                int mode=Math.floorMod(round+order,4);
                long before=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
                Object result=mode==0?build(samples,grid,vegetation):mode==1?decode(Files.readAllBytes(file)):mode==2?encode(expected,true):bulkDecode(Files.readAllBytes(file));
                long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(tid)-before;
                sink=result;
                if(round>=0){nanos[mode][round]=elapsed;allocated[mode][round]=bytes;}
                if(mode!=2 && (round==0 || round==30)) verify(expected,(Snapshot)result);
            }
            for(var a:nanos)Arrays.sort(a);for(var a:allocated)Arrays.sort(a);
            String row=String.format(Locale.ROOT,"%s,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%d%n",name,
                    expected.words.length/12,raw.length,encoded.length,nanos[0][15]/1e6,nanos[1][15]/1e6,nanos[2][15]/1e6,nanos[3][15]/1e6,allocated[0][15],allocated[1][15],allocated[3][15]);
            report.append(row);System.out.print("FINISHED_MESH "+row);
            // Exact bit-preserving codec; corrupt and truncated streams must fail.
            byte[] damaged=encoded.clone();damaged[damaged.length/2]^=127;
            assertThrows(IOException.class,()->decode(damaged));
            assertThrows(IOException.class,()->decode(Arrays.copyOf(encoded,encoded.length/2)));
            assertThrows(IOException.class,()->bulkDecode(damaged));
            assertThrows(IOException.class,()->bulkDecode(Arrays.copyOf(encoded,encoded.length/2)));
        }
        Files.writeString(output.resolve("benchmark.csv"),report);
    }

    private static Snapshot build(ClientColumnSample[] samples,int grid,PredictionVegetation.Tile vegetation) throws Exception {
        var mesh=PredictionMeshBuilder.build(samples,null,63,0xb22d78c5,1,grid,true,null,null,null,0,0,vegetation).compactForRendering();
        int axis=mesh.cellAxis(),n=(axis+1)*(axis+1);int[] heights=new int[n];Arrays.fill(heights,70);
        var tile=new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.withDefaultNamespace("overworld")),0,0,0),
                heights,heights,new ClientColumnSample[n],mesh,new PredictionDepthBound(48,96),0,1,axis,1);
        float[] morph=new float[n];for(int i=0;i<n;i++)morph[i]=(i%7-3)/256f;
        mesh.morph(morph);mesh.prepareGpuPayload(tile);
        var packed=mesh.gpuPayload();var seams=mesh.seamMesh();
        int[] ranges=new int[VssLodFaceGroup.COUNT*4];
        for(int i=0;i<VssLodFaceGroup.COUNT;i++) {
            ranges[i*4]=packed.terrainRangeFirst(i);ranges[i*4+1]=packed.terrainRangeCount(i);
            ranges[i*4+2]=packed.waterRangeFirst(i);ranges[i*4+3]=packed.waterRangeCount(i);
        }
        return new Snapshot(axis,packed.terrainQuadCount(),packed.spriteQuadCount(),packed.downFaces(),packed.quads(),ranges,
                packed.morph(),packed.morphMinY(),packed.morphMaxY(),(float[])field(seams,"topY"),(int[])field(seams,"topColors"),
                (boolean[])field(seams,"tops"),(long[])field(seams,"planes"),(int[])field(seams,"offsets"),(int[])field(seams,"walls"));
    }
    private static Object field(Object owner,String name)throws Exception {var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static byte[] encode(Snapshot s,boolean compress)throws IOException {
        var bytes=new ByteArrayOutputStream();var deflater=new Deflater(1);
        try(var out=new DataOutputStream(new BufferedOutputStream(compress?new DeflaterOutputStream(bytes,deflater):bytes))) {
            out.writeLong(0x5653534d45534831L);out.writeInt(s.axis);out.writeInt(s.terrain);out.writeInt(s.sprites);out.writeBoolean(s.down);
            ints(out,s.words);ints(out,s.ranges);floats(out,s.morph);out.writeInt(s.minY);out.writeInt(s.maxY);
            floats(out,s.topsY);ints(out,s.colors);out.writeInt(s.tops.length);for(boolean x:s.tops)out.writeBoolean(x);
            out.writeInt(s.planes.length);for(long x:s.planes)out.writeLong(x);ints(out,s.offsets);ints(out,s.walls);
        } finally {deflater.end();}
        return bytes.toByteArray();
    }
    private static Snapshot decode(byte[] bytes)throws IOException {
        try(var in=new DataInputStream(new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes))))) {
            if(in.readLong()!=0x5653534d45534831L)throw new IOException("identity");
            int axis=in.readInt(),terrain=in.readInt(),sprites=in.readInt();boolean down=in.readBoolean();
            int[] words=ints(in),ranges=ints(in);float[] morph=floats(in);int min=in.readInt(),max=in.readInt();
            float[] topY=floats(in);int[] colors=ints(in);boolean[] tops=new boolean[count(in)];for(int i=0;i<tops.length;i++)tops[i]=in.readBoolean();
            long[] planes=new long[count(in)];for(int i=0;i<planes.length;i++)planes[i]=in.readLong();
            int[] offsets=ints(in),walls=ints(in);if(in.read()!=-1)throw new IOException("trailing bytes");
            return new Snapshot(axis,terrain,sprites,down,words,ranges,morph,min,max,topY,colors,tops,planes,offsets,walls);
        }
    }
    private static int count(DataInputStream in)throws IOException {int n=in.readInt();if(n<0||n>4_000_000)throw new IOException("size");return n;}
    private static Snapshot bulkDecode(byte[] bytes)throws IOException {
        byte[] raw;
        try(var in=new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            raw=in.readNBytes(64*1024*1024+1);if(raw.length>64*1024*1024)throw new IOException("size");
        }
        try {
            var b=java.nio.ByteBuffer.wrap(raw);
            if(b.getLong()!=0x5653534d45534831L)throw new IOException("identity");
            int axis=b.getInt(),terrain=b.getInt(),sprites=b.getInt();boolean down=b.get()!=0;
            int[] words=bulkInts(b),ranges=bulkInts(b);float[] morph=bulkFloats(b);int min=b.getInt(),max=b.getInt();
            float[] topY=bulkFloats(b);int[] colors=bulkInts(b);int n=bulkCount(b,1);boolean[] tops=new boolean[n];
            for(int i=0;i<n;i++)tops[i]=b.get()!=0;
            n=bulkCount(b,8);long[] planes=new long[n];b.asLongBuffer().get(planes);b.position(b.position()+n*8);
            int[] offsets=bulkInts(b),walls=bulkInts(b);if(b.hasRemaining())throw new IOException("trailing");
            return new Snapshot(axis,terrain,sprites,down,words,ranges,morph,min,max,topY,colors,tops,planes,offsets,walls);
        }catch(java.nio.BufferUnderflowException e){throw new IOException(e);}
    }
    private static int bulkCount(java.nio.ByteBuffer b,int stride)throws IOException {int n=b.getInt();if(n<0||n>b.remaining()/stride)throw new IOException("size");return n;}
    private static int[] bulkInts(java.nio.ByteBuffer b)throws IOException {int n=bulkCount(b,4);int[] a=new int[n];b.asIntBuffer().get(a);b.position(b.position()+n*4);return a;}
    private static float[] bulkFloats(java.nio.ByteBuffer b)throws IOException {int n=bulkCount(b,4);float[] a=new float[n];b.asFloatBuffer().get(a);b.position(b.position()+n*4);return a;}
    private static void ints(DataOutputStream o,int[] v)throws IOException {o.writeInt(v.length);for(int x:v)o.writeInt(x);}
    private static int[] ints(DataInputStream in)throws IOException {int[] v=new int[count(in)];for(int i=0;i<v.length;i++)v[i]=in.readInt();return v;}
    private static void floats(DataOutputStream o,float[] v)throws IOException {o.writeInt(v.length);for(float x:v)o.writeInt(Float.floatToRawIntBits(x));}
    private static float[] floats(DataInputStream in)throws IOException {int[] raw=ints(in);float[] v=new float[raw.length];for(int i=0;i<v.length;i++)v[i]=Float.intBitsToFloat(raw[i]);return v;}
    private static void verify(Snapshot a,Snapshot b) {
        assertEquals(a.axis,b.axis);assertEquals(a.terrain,b.terrain);assertEquals(a.sprites,b.sprites);assertEquals(a.down,b.down);
        assertEquals(a.minY,b.minY);assertEquals(a.maxY,b.maxY);assertArrayEquals(a.words,b.words);assertArrayEquals(a.ranges,b.ranges);
        assertArrayEquals(a.morph,b.morph);assertArrayEquals(a.topsY,b.topsY);assertArrayEquals(a.colors,b.colors);assertArrayEquals(a.tops,b.tops);
        assertArrayEquals(a.planes,b.planes);assertArrayEquals(a.offsets,b.offsets);assertArrayEquals(a.walls,b.walls);
    }
}
