"""Defer decoration-only biome identity/tint-cache preparation, without changing native query order."""
import argparse
import json
import pathlib

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=pathlib.Path)
root = parser.parse_args().directory.resolve()
package = root/'main-java/dev/xantha/vss/client/prediction'
patches = []

def patch(name, old, new):
    p = package/name
    text = p.read_text(encoding='utf-8')
    assert text.count(old) == 1, (name, old[:80], text.count(old))
    p.write_text(text.replace(old, new), encoding='utf-8')
    patches.append({'file': name, 'old': old, 'new': new})

patch('DecorationExperimentSettings.java', '    static boolean nativeBiomes, scan;', '    static boolean nativeBiomes, scan, minimal;')
patch('DecorationExperimentSettings.java', '    static void select(String mode) {',
      '    static void select(String mode) {\n        minimal = mode.startsWith("lean");')
patch('RustTerrainSampler.java', 'int[] surfaceRecord(int x, int z) { return point(x, z); }',
      'int[] surfaceRecord(int x, int z) { return DecorationExperimentSettings.minimal ? experimentLeanRecord(x,z) : point(x,z); }')
patch('RustTerrainSampler.java',
      'synchronized (points) { int[] found = points.getAndMoveToLast(key); if (found != null) return found; }',
      '''int[] found;
        synchronized(points) { found=points.getAndMoveToLast(key); }
        if(found!=null)return found.length==10 ? experimentCompleteRecord(key,x,z,found) : found;''')
patch('RustTerrainSampler.java', 'if(cached!=null) { result[target]=sampleRecord(cached,0); continue; }',
      'if(cached!=null) { result[target]=sampleRecord(cached.length==10 ? point(xx,zz) : cached,0); continue; }')
patch('RustTerrainSampler.java', '    private int[] point(int x, int z) {', '''
    private int[] experimentLeanRecord(int x,int z) {
        long key=(long)x<<32|z&0xffffffffL;
        synchronized(points) {var found=points.getAndMoveToLast(key);if(found!=null)return found;}
        ByteBuffer input=positions.get(),output=scratch.get();
        input.putInt(0,x).putInt(4,z);
        DecorationExperimentSettings.surfaceCalls++;DecorationExperimentSettings.surfacePoints++;
        if(RustWorldgenBackend.surfacePoints(handle(),input,output,1)!=1)throw new IllegalStateException("Incomplete lean record");
        int[] row=new int[10];for(int i=0;i<10;i++)row[i]=output.getInt(i*4);
        rememberPoint(key,row);return row;
    }
    private int[] experimentCompleteRecord(long key,int x,int z,int[] raw) {
        int[] data=Arrays.copyOf(raw,11);
        int[] queries=surfaceBiomePositions.get(),indices=surfaceBiomes.get();
        surfaceBiomePosition(queries,0,x,z,data[0],data[1],data[2]);
        surfaceBiomeIndices(queries,1,indices);data[10]=indices[0];
        rememberColors(x,z,data,tints.generation());rememberPoint(key,data);return data;
    }
    private int[] point(int x, int z) {''')
(root/'lean-patches.json').write_text(json.dumps(patches, ensure_ascii=False, indent=2), encoding='utf-8')
print('Applied lazy decoration metadata experiment:', root)
