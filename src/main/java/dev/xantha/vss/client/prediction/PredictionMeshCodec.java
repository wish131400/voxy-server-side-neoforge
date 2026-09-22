package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import net.minecraft.world.level.block.Block;

/** Lossless finished geometry, independent of view-dependent masks and parent morphs. */
final class PredictionMeshCodec {
    // Face tint flags and snowy side states changed; rebuild old finished geometry.
    // Terrain and decoration sample caches remain valid.
    static final int VERSION = 3, MAX_BYTES = 16 * 1024 * 1024;

    static byte[] signature(byte[] resources, ClientColumnSample[] samples, int[] colors, int[] foliage,
                            int[] water, int sea, int fluid, int step, boolean trees,
                            PredictionVegetation.Tile plants, PredictionSimpleVegetation.Result simple) {
        if (resources == null) return null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)))) {
                out.write(resources); out.writeInt(VERSION); out.writeInt(sea); out.writeInt(fluid);
                out.writeInt(step); out.writeBoolean(trees); out.writeInt(samples.length);
                for (var s : samples) {
                    for (int v : new int[]{s.surfaceY(),s.fluidY(),s.biomeIndex(),s.topBlockIndex(),s.structureIndex(),
                            s.treeKind(),s.treeDensity(),s.treeHeight(),s.fluid(),s.flags(),s.groundFeatureKind(),
                            s.underBlockIndex(),s.deepBlockIndex(),s.surfaceBottom(),s.lowerTop(),s.lowerBottom(),s.spanFloor()}) out.writeInt(v);
                    var volume = s.volume(); out.writeInt(volume == null ? -1 : volume.size());
                    if (volume != null) for (int i=0;i<volume.size();i++) {
                        out.writeInt(volume.bottom(i));out.writeInt(volume.top(i));out.writeInt(volume.block(i));out.writeInt(volume.fluid(i));
                    }
                }
                for (int[] array : new int[][]{colors,foliage,water}) {
                    out.writeInt(array.length); for (int color : array) out.writeInt(color & 0xffffff);
                }
                out.writeInt(plants.baseX());out.writeInt(plants.baseZ());out.writeInt(plants.voxelSize());
                var cells = new TreeMap<>(plants.cells()); out.writeInt(cells.size());
                for (var cell : cells.entrySet()) {
                    out.writeInt(cell.getKey());out.writeInt(cell.getValue().size());
                    for (var v : cell.getValue()) {
                        out.writeInt(v.x());out.writeInt(v.y());out.writeInt(v.z());out.writeInt(v.size());out.writeInt(Block.getId(v.state()));
                    }
                }
                // Exact blocks drive face culling and mesh-budget fallback; include them as well as voxels.
                var blocks = new TreeMap<Long,Integer>();plants.blocks().forEach((p,s)->blocks.put(p.asLong(),Block.getId(s)));
                out.writeInt(blocks.size());for(var e:blocks.entrySet()){out.writeLong(e.getKey());out.writeInt(e.getValue());}
                for(var map:List.of(plants.exteriorTops(),plants.exteriorFloors())) {
                    long[] keys=map.keySet().toLongArray();Arrays.sort(keys);out.writeInt(keys.length);
                    for(long key:keys){out.writeLong(key);out.writeInt(map.get(key));}
                }
                out.writeInt(simple.forms().size());
                for (var form : simple.forms()) {
                    out.writeInt(form.cell());out.writeInt(form.x());out.writeInt(form.z());out.writeInt(form.y());
                    out.writeInt(form.height());out.writeInt(form.grassTint());
                    var tree=form.tree();out.writeBoolean(tree!=null);
                    if(tree!=null){out.writeInt(Block.getId(tree.log()));out.writeInt(Block.getId(tree.leaves()));out.writeInt(tree.ground()==null?-1:Block.getId(tree.ground()));}
                }
            }
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static byte[] encode(PredictionMesh mesh, byte[] signature) throws IOException {
        var payload=mesh.gpuPayload();
        if(payload==null || mesh.retainedHeapBytes()>MAX_BYTES)throw new IOException("mesh record too large");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(VERSION);out.write(signature);out.writeInt(mesh.cellAxis());
            out.writeInt(mesh.vertexCount());out.writeInt(mesh.waterVertexCount());
            out.writeInt(payload.terrainQuadCount());out.writeInt(payload.spriteQuadCount());out.writeBoolean(payload.downFaces());
            out.writeInt(payload.morphMinY());out.writeInt(payload.morphMaxY());
            int[] words = payload.quads();
            var used=new BitSet(256);
            for(int i=6;i<words.length;i+=12){int row=words[i]&0xffff;if(row>0 && row!=255)used.set(row);}
            mesh.seamMesh().materialRows(used);
            out.writeInt(used.cardinality());
            for(int row=used.nextSetBit(0);row>=0;row=used.nextSetBit(row+1)) {out.writeInt(row);VssLodSpriteTable.writeMaterial(out,row);}
            ints(out,words);
            for(int i=0;i<VssLodFaceGroup.COUNT;i++) {
                out.writeInt(payload.terrainRangeFirst(i));out.writeInt(payload.terrainRangeCount(i));
                out.writeInt(payload.waterRangeFirst(i));out.writeInt(payload.waterRangeCount(i));
            }
            mesh.seamMesh().writeCache(out);
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("mesh record too large");
        return bytes.toByteArray();
    }

    static PredictionMesh decode(byte[] bytes, byte[] signature, int expectedAxis) throws IOException {
        if(bytes.length>MAX_BYTES)throw new IOException("mesh record too large");
        try {
            var stream=new ByteArrayInputStream(bytes);var header=new DataInputStream(stream);
            if(header.readInt()!=VERSION || !Arrays.equals(header.readNBytes(32),signature))return null;
            int axis=header.readInt(),vertices=header.readInt(),waterVertices=header.readInt();
            int terrain=header.readInt(),sprites=header.readInt();boolean down=header.readBoolean();
            int min=header.readInt(),max=header.readInt();
            if(axis!=expectedAxis || axis<1 || axis>64 || vertices<0 || waterVertices<0 || terrain<0 || sprites<0 || min>max)
                throw new IOException("mesh dimensions");
            int n=header.readInt();if(n<0||n>254)throw new IOException("mesh material count");
            int[] rows=new int[256];Arrays.fill(rows,-1);rows[0]=0;rows[255]=255;
            for(int i=0;i<n;i++) {
                int old=header.readInt();if(old<=0||old>=255||rows[old]!=-1)throw new IOException("mesh material index");
                rows[old]=VssLodSpriteTable.readMaterial(header);
            }
            var in=ByteBuffer.wrap(bytes);in.position(bytes.length-stream.available());
            int[] words=ints(in);if(words.length%12!=0 || terrain>words.length/12 || sprites>terrain)throw new IOException("mesh quads");
            for(int i=6;i<words.length;i+=12){int old=words[i]&0xffff;if(old>=256||rows[old]<0)throw new IOException("mesh unresolved material");words[i]=(words[i]&0xffff0000)|rows[old];}
            int count=VssLodFaceGroup.COUNT;
            int[] tf=new int[count],tc=new int[count],wf=new int[count],wc=new int[count];
            int tend=0,wend=terrain;
            for(int i=0;i<count;i++) {
                tf[i]=in.getInt();tc[i]=in.getInt();wf[i]=in.getInt();wc[i]=in.getInt();
                if(tf[i]!=(tc[i]==0?0:tend)||wf[i]!=(wc[i]==0?0:wend)||tc[i]<0||wc[i]<0||tc[i]>terrain-tend||wc[i]>words.length/12-wend)throw new IOException("mesh ranges");
                tend+=tc[i];wend+=wc[i];
            }
            if(tend!=terrain||wend!=words.length/12)throw new IOException("mesh range totals");
            var seams=new PredictionSeamMesh(in,axis);
            seams.remapMaterials(rows);
            if(in.hasRemaining())throw new IOException("trailing mesh bytes");
            var packed=new PredictionPackedMesh(words,axis,terrain,tf,tc,wf,wc,down,sprites);
            packed.morph(null,min,max); // Parent and upload age belong to this session.
            return PredictionMesh.restored(vertices,waterVertices,packed,seams);
        } catch (BufferUnderflowException | IndexOutOfBoundsException malformed) { throw new IOException("truncated mesh",malformed); }
    }

    static void ints(DataOutputStream out,int[] values)throws IOException {out.writeInt(values.length);for(int value:values)out.writeInt(value);}
    static void floats(DataOutputStream out,float[] values)throws IOException {out.writeInt(values.length);for(float value:values)out.writeInt(Float.floatToRawIntBits(value));}
    static int count(ByteBuffer in,int stride)throws IOException {int n=in.getInt();if(n<0||n>in.remaining()/stride)throw new IOException("mesh array length");return n;}
    static int[] ints(ByteBuffer in)throws IOException {int n=count(in,4);int[] values=new int[n];in.asIntBuffer().get(values);in.position(in.position()+n*4);return values;}
    static float[] floats(ByteBuffer in)throws IOException {int n=count(in,4);float[] values=new float[n];in.asFloatBuffer().get(values);in.position(in.position()+n*4);return values;}
}
