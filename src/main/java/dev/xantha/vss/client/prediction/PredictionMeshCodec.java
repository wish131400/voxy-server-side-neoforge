package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import net.minecraft.world.level.block.Block;

/** Lossless finished geometry, independent of view-dependent masks and parent morphs. */
final class PredictionMeshCodec {
    // Rebuild walls where a surface replacement consumed the complete suspended roof.
    // Terrain and decoration sample caches remain valid.
    /** Rebuild old solid-box growing plants; raw terrain and feature caches remain valid. */
    static final int VERSION = 11, RECORD_VERSION = 9, MAX_BYTES = 16 * 1024 * 1024;
    record MeshRecord(PredictionMesh mesh, boolean surfaceCompleted,
                      dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities, byte[] baseIdentity, byte[] fullIdentity) { }
    private static final int CITY_RECORD_VERSION = 10;

    static byte[] withCityBuildings(byte[] signature, dev.xantha.vss.common.worldgen.LostCityPreview.Tile buildings) {
        if (signature == null || buildings == null) return signature;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(signature);
            dev.xantha.vss.common.worldgen.LostCityPreview.fingerprint(digest, buildings);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static byte[] signature(byte[] resources, ClientColumnSample[] samples, int[] colors, int[] foliage,
                            int[] water, int sea, int fluid, int step, boolean trees,
                            PredictionVegetation.Tile plants, PredictionSimpleVegetation.Result simple) {
        return signature(resources, samples, colors, foliage, water, sea, fluid, step, trees, plants, simple, true);
    }

    static byte[] signature(byte[] resources, ClientColumnSample[] samples, int[] colors, int[] foliage,
                            int[] water, int sea, int fluid, int step, boolean trees,
                            int decorationSettings, PredictionVegetation.Tile plants,
                            PredictionSimpleVegetation.Result simple) {
        return signature(resources, samples, colors, foliage, water, sea, fluid, step, trees,
                plants, simple, true, decorationSettings);
    }

    /**
     * Identity for the part of a mesh that is available before feature replay.
     * The arrays are the persisted surface, foliage and water tints. World
     * edits invalidate the terrain/mesh record, while the resource fingerprint
     * and colour fingerprint invalidate palette changes without replaying the
     * expensive decoration stage.
     */
    static byte[] baseSignature(byte[] resources, ClientColumnSample[] samples, int[] colors, int[] foliage,
                                int[] water, int sea, int fluid, int step, boolean trees) {
        return signature(resources, samples, colors, foliage, water, sea, fluid, step, trees,
                PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, false,
                trees ? 1 : 0);
    }

    /** Includes the enabled surface/structure switches in the pre-decoration identity. */
    static byte[] baseSignature(byte[] resources, ClientColumnSample[] samples, int[] colors, int[] foliage,
                                int[] water, int sea, int fluid, int step, boolean trees,
                                int decorationSettings) {
        return signature(resources, samples, colors, foliage, water, sea, fluid, step, trees,
                PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, false,
                decorationSettings);
    }

    private static byte[] signature(byte[] resources, ClientColumnSample[] samples, int[] colors, int[] foliage,
                                    int[] water, int sea, int fluid, int step, boolean trees,
                                    PredictionVegetation.Tile plants, PredictionSimpleVegetation.Result simple,
                                    boolean includeDecoration) {
        return signature(resources, samples, colors, foliage, water, sea, fluid, step, trees,
                plants, simple, includeDecoration, trees ? 1 : 0);
    }

    private static byte[] signature(byte[] resources, ClientColumnSample[] samples, int[] colors, int[] foliage,
                                    int[] water, int sea, int fluid, int step, boolean trees,
                                    PredictionVegetation.Tile plants, PredictionSimpleVegetation.Result simple,
                                    boolean includeDecoration, int decorationSettings) {
        if (resources == null) return null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)))) {
                out.write(resources); out.writeInt(VERSION); out.writeInt(sea); out.writeInt(fluid);
                out.writeInt(step); out.writeBoolean(trees); out.writeInt(decorationSettings); out.writeInt(samples.length);
                for (var s : samples) {
                    out.writeInt(s.surfaceY()); out.writeInt(s.fluidY()); out.writeInt(s.biomeIndex());
                    out.writeInt(s.topBlockIndex()); out.writeInt(s.structureIndex()); out.writeInt(s.treeKind());
                    out.writeInt(s.treeDensity()); out.writeInt(s.treeHeight()); out.writeInt(s.fluid());
                    out.writeInt(s.flags()); out.writeInt(s.groundFeatureKind()); out.writeInt(s.underBlockIndex());
                    out.writeInt(s.deepBlockIndex()); out.writeInt(s.surfaceBottom()); out.writeInt(s.lowerTop());
                    out.writeInt(s.lowerBottom()); out.writeInt(s.spanFloor());
                    var volume = s.volume(); out.writeInt(volume == null ? -1 : volume.size());
                    if (volume != null) for (int i=0;i<volume.size();i++) {
                        out.writeInt(volume.bottom(i));out.writeInt(volume.top(i));out.writeInt(volume.block(i));out.writeInt(volume.fluid(i));
                    }
                }
                for (int[] array : new int[][]{colors,foliage,water}) {
                    out.writeInt(array.length); for (int color : array) out.writeInt(color & 0xffffff);
                }
                if (!includeDecoration) { out.flush(); return digest.digest(); }
                out.write(plants.signatureCache().data(plants));
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

    static byte[] decorationBytes(PredictionVegetation.Tile plants) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(plants.baseX()); out.writeInt(plants.baseZ()); out.writeInt(plants.voxelSize());
            int[] cells = plants.cells().keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
            out.writeInt(cells.length);
            for (int cell : cells) {
                var values = plants.cell(cell); out.writeInt(cell); out.writeInt(values.size());
                for (var voxel : values) {
                    out.writeInt(voxel.x()); out.writeInt(voxel.y()); out.writeInt(voxel.z());
                    out.writeInt(voxel.size()); out.writeInt(Block.getId(voxel.state()));
                }
            }
            var blocks = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(plants.blocks().size());
            plants.blocks().forEach((pos, state) -> blocks.put(pos.asLong(), Block.getId(state)));
            long[] keys = blocks.keySet().toLongArray(); Arrays.sort(keys);
            out.writeInt(keys.length);
            for (long key : keys) { out.writeLong(key); out.writeInt(blocks.get(key)); }
            for (var map : List.of(plants.exteriorTops(), plants.exteriorFloors())) {
                keys = map.keySet().toLongArray(); Arrays.sort(keys); out.writeInt(keys.length);
                for (long key : keys) { out.writeLong(key); out.writeInt(map.get(key)); }
            }
        }
        return bytes.toByteArray();
    }

    static byte[] encode(PredictionMesh mesh, byte[] signature) throws IOException {
        return encode(mesh, signature, signature, true);
    }

    static byte[] encode(PredictionMesh mesh, byte[] signature, byte[] baseSignature) throws IOException {
        return encode(mesh, signature, baseSignature, true);
    }

    static byte[] encode(PredictionMesh mesh, byte[] signature, byte[] baseSignature,
                         boolean baseSafe) throws IOException {
        return encode(mesh, signature, baseSignature, baseSafe, false);
    }

    static byte[] encode(PredictionMesh mesh, byte[] signature, byte[] baseSignature,
                         boolean baseSafe, boolean surfaceCompleted) throws IOException {
        return encode(mesh, signature, baseSignature, baseSafe, surfaceCompleted, null, null);
    }

    static byte[] encode(PredictionMesh mesh, byte[] signature, byte[] baseSignature,
                         boolean baseSafe, boolean surfaceCompleted, byte[] rawIdentity,
                         dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities) throws IOException {
        byte[] cityBytes = cities == null ? null : PredictionCityMeshCache.encode(cities);
        if (cities != null && (rawIdentity == null || rawIdentity.length != 32
                || !Arrays.equals(baseSignature, withCityBuildings(rawIdentity, PredictionCityMeshCache.buildings(cities)))))
            throw new IOException("city cache identity");
        var payload=mesh.gpuPayload();
        if(payload==null || signature == null || baseSignature == null
                || signature.length != 32 || baseSignature.length != 32
                || surfaceCompleted && !baseSafe
                || mesh.retainedHeapBytes()>MAX_BYTES)throw new IOException("invalid mesh record");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(cities == null ? RECORD_VERSION : CITY_RECORD_VERSION);out.write(signature);out.write(baseSignature);out.writeBoolean(baseSafe);out.writeInt(mesh.cellAxis());
            out.writeInt(mesh.vertexCount());out.writeInt(mesh.waterVertexCount());
            out.writeInt(payload.terrainQuadCount());out.writeInt(payload.spriteQuadCount());out.writeBoolean(payload.downFaces());
            out.writeInt(payload.morphMinY());out.writeInt(payload.morphMaxY());
            int[] words = payload.cacheWords();
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
            out.writeBoolean(surfaceCompleted);
            if (cityBytes != null) {
                out.write(rawIdentity); out.write(cityBytes); out.writeInt(32 + cityBytes.length);
            }
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("mesh record too large");
        return bytes.toByteArray();
    }

    static PredictionMesh decode(byte[] bytes, byte[] signature, int expectedAxis) throws IOException {
        MeshRecord result = decodeRecord(bytes, signature, null, expectedAxis);
        return result == null ? null : result.mesh();
    }

    static PredictionMesh decodeBase(byte[] bytes, byte[] baseSignature, int expectedAxis) throws IOException {
        MeshRecord result = decodeBaseRecord(bytes, baseSignature, expectedAxis);
        return result == null ? null : result.mesh();
    }

    static MeshRecord decodeBaseRecord(byte[] bytes, byte[] baseSignature, int expectedAxis) throws IOException {
        return decodeRecord(bytes, null, baseSignature, expectedAxis);
    }

    private static MeshRecord decodeRecord(byte[] bytes, byte[] signature, byte[] baseSignature,
                                            int expectedAxis) throws IOException {
        return decodeRecord(bytes, signature, baseSignature, expectedAxis, null, null);
    }

    static MeshRecord decodeCityBaseRecord(byte[] bytes, byte[] rawIdentity, byte[] liveBase, int axis,
            java.util.function.Predicate<dev.xantha.vss.common.worldgen.LostCityPreview.Tile> validate) throws IOException {
        return decodeRecord(bytes, null, liveBase, axis, rawIdentity, validate);
    }

    private static MeshRecord decodeRecord(byte[] bytes, byte[] signature, byte[] baseSignature,
            int expectedAxis, byte[] rawIdentity,
            java.util.function.Predicate<dev.xantha.vss.common.worldgen.LostCityPreview.Tile> validate) throws IOException {
        if(bytes.length>MAX_BYTES)throw new IOException("mesh record too large");
        try {
            dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities = null;
            int geometryEnd = bytes.length;
            if (bytes.length >= 4 && ByteBuffer.wrap(bytes).getInt() == CITY_RECORD_VERSION) {
                int length = ByteBuffer.wrap(bytes).getInt(bytes.length - 4);
                if (length < 32 || length > bytes.length - 4 - 69) throw new IOException("city mesh footer");
                geometryEnd = bytes.length - 4 - length;
                if (rawIdentity != null) {
                    if (!Arrays.equals(rawIdentity, Arrays.copyOfRange(bytes, geometryEnd, geometryEnd + 32))) return null;
                    cities = PredictionCityMeshCache.decode(Arrays.copyOfRange(bytes, geometryEnd + 32, bytes.length - 4));
                    if (!validate.test(cities)) return null;
                    baseSignature = withCityBuildings(rawIdentity, PredictionCityMeshCache.buildings(cities));
                }
            }
            var stream=new ByteArrayInputStream(bytes);var header=new DataInputStream(stream);
            int recordVersion = header.readInt();
            if(recordVersion != 8 && recordVersion != RECORD_VERSION && recordVersion != CITY_RECORD_VERSION)return null;
            byte[] fullIdentity=header.readNBytes(32), storedBase=header.readNBytes(32);
            boolean storedBaseSafe=header.readBoolean();
            if(fullIdentity.length != 32 || storedBase.length != 32
                    || signature != null && !Arrays.equals(fullIdentity,signature)
                    || baseSignature != null && (!storedBaseSafe || !Arrays.equals(storedBase,baseSignature)))return null;
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
            var in=ByteBuffer.wrap(bytes);in.limit(geometryEnd);in.position(bytes.length-stream.available());
            int[] words=ints(in);if(words.length%12!=0 || terrain>words.length/12 || sprites>words.length/12)throw new IOException("mesh quads");
            for(int i=6;i<words.length;i+=12){int old=words[i]&0xffff;if(old>=256||rows[old]<0)throw new IOException("mesh unresolved material");
                // CPU seam colours use alpha=255 for flat; packed GPU quads
                // use row zero. Never sample the unallocated atlas row 255.
                int row=rows[old]==VssLodSpriteTable.FLAT?0:rows[old];
                words[i]=(words[i]&0xffff0000)|row;
            }
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
            boolean surfaceCompleted = false;
            if (recordVersion >= RECORD_VERSION) {
                int complete = in.get() & 255;
                if (complete > 1 || complete == 1 && !storedBaseSafe)throw new IOException("mesh surface completion");
                surfaceCompleted = complete == 1;
            }
            if(in.hasRemaining())throw new IOException("trailing mesh bytes");
            var packed=new PredictionPackedMesh(words,axis,terrain,tf,tc,wf,wc,down,sprites);
            packed.morph(null,min,max); // Parent and upload age belong to this session.
            return new MeshRecord(PredictionMesh.restored(vertices,waterVertices,packed,seams), surfaceCompleted, cities, storedBase, fullIdentity);
        } catch (BufferUnderflowException | IndexOutOfBoundsException malformed) { throw new IOException("truncated mesh",malformed); }
    }

    static void ints(DataOutputStream out,int[] values)throws IOException {out.writeInt(values.length);for(int value:values)out.writeInt(value);}
    static void floats(DataOutputStream out,float[] values)throws IOException {out.writeInt(values.length);for(float value:values)out.writeInt(Float.floatToRawIntBits(value));}
    static int count(ByteBuffer in,int stride)throws IOException {int n=in.getInt();if(n<0||n>in.remaining()/stride)throw new IOException("mesh array length");return n;}
    static int[] ints(ByteBuffer in)throws IOException {int n=count(in,4);int[] values=new int[n];in.asIntBuffer().get(values);in.position(in.position()+n*4);return values;}
    static float[] floats(ByteBuffer in)throws IOException {int n=count(in,4);float[] values=new float[n];in.asFloatBuffer().get(values);in.position(in.position()+n*4);return values;}
}
