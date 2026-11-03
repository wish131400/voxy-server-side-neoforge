"""Build an isolated full-runtime comparison of disk resources and environment batches."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

project = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('output', type=Path)
args = parser.parse_args()
out = args.output.resolve()
subprocess.run([sys.executable, str(project/'tools/performance/decoration-hotspots/prepare.py'), str(out)], check=True)
package = out/'main-java/dev/xantha/vss/client/prediction'
patches = []


def patch(name, old, new):
    path = package/name
    text = path.read_text(encoding='utf-8')
    assert text.count(old) == 1, (name, old[:100], text.count(old))
    path.write_text(text.replace(old, new), encoding='utf-8')
    patches.append({'file': name, 'old': old, 'new': new})


shutil.copy2(Path(__file__).with_name('DiskModuleExperiment.java'), package/'DiskModuleExperiment.java')
shutil.copy2(Path(__file__).with_name('DiskModuleFixtures.java'), package/'DiskModuleFixtures.java')
resource = out/'main-resources/vss-experiment/disk-masks.json'
resource.parent.mkdir()
masks = {str(r): [[x, 0, z] for z in range(-r, r+1) for x in range(-r, r+1)
                  if x*x+z*z <= r*r] for r in range(2, 7)}
resource.write_text(json.dumps(masks, separators=(',', ':')), encoding='utf-8')

patch('PredictionSurfaceFeatureAdapters.java',
      '            else configured.place(level, generator, random, pos);',
      '            else if (!DiskModuleExperiment.tryPlace(feature, level, generator, random, pos))\n'
      '                configured.place(level, generator, random, pos);')
patch('PredictionVegetation.java',
      '                        else PredictionSurfaceFeatureAdapters.place(feature,level,context.generatorContext(),random,origin);',
      '                        else PredictionSurfaceFeatureAdapters.place(feature,level,context.generatorContext(),random,origin);\n'
      '                        DiskModuleExperiment.randomTail(random.nextLong());')

patch('RustTerrainSampler.java', '    int[][] exactSurfacePage(int x,int z,int side) {', '''
    int[][] experimentExactBatch(int[] xs, int[] zs, int base, int count) {
        handle();
        if (count < 1 || count > 64) throw new IllegalArgumentException("Batch size");
        int[][] rows = new int[count][];
        int pending = 0;
        int[] missing = missingScratch.get();
        ByteBuffer input = positions.get(), output = scratch.get(); input.clear();
        synchronized (points) {
            for (int i=0;i<count;i++) {
                rows[i]=points.getAndMoveToLast((long)xs[base+i]<<32|zs[base+i]&0xffffffffL);
                if(rows[i]==null) {missing[pending++]=i;input.putInt(xs[base+i]).putInt(zs[base+i]);}
            }
        }
        if (pending == 0) return rows;
        long start = System.nanoTime();
        if (FeatureHotspotProbe.surface(handle(),input,output,pending)!=pending)
            throw new IllegalStateException("Incomplete module environment batch");
        DiskModuleExperiment.batch(pending,System.nanoTime()-start);
        DecorationExperimentSettings.surfaceCalls++;
        DecorationExperimentSettings.surfacePoints+=pending;
        for (int n=0;n<pending;n++) {
            int i=missing[n];int[] row=rows[i]=new int[10];
            for(int j=0;j<10;j++)row[j]=output.getInt((n*10+j)*4);
            rememberPoint((long)xs[base+i]<<32|zs[base+i]&0xffffffffL,row);
        }
        return rows;
    }
    int[][] exactSurfacePage(int x,int z,int side) {''')

patch('PredictionDecorationLevel.java', '    private int columnSlot(int x, int z) {', '''
    private static final class DiskPreparation {
        final int[] xs=new int[256],zs=new int[256],pages=new int[16];
    }
    private static final ThreadLocal<DiskPreparation> DISK_PREPARATION=ThreadLocal.withInitial(DiskPreparation::new);
    boolean experimentPrepareDisk(BlockPos origin, int[] offsets, boolean compact) {
        if (displayTerrain || interiorTerrain() || !(terrain instanceof RustTerrainSampler rust)) return false;
        // Do not prefetch outside the normal job window or change partial failure behavior.
        for(int i=0;i<offsets.length;i+=2) {
            int x=origin.getX()+offsets[i],z=origin.getZ()+offsets[i+1];
            if(x<originX-32||x>=originX+48||z<originZ-32||z>=originZ+48)return false;
        }
        if(nativeColumns==null)nativeColumns=new int[COLUMN_CACHE_SIZE][];
        var work=DISK_PREPARATION.get();
        int[] xs=work.xs,zs=work.zs,pages=work.pages;
        int count=0,pageCount=0;
        for(int i=0;i<offsets.length;i+=2) {
            int x=origin.getX()+offsets[i],z=origin.getZ()+offsets[i+1];
            if(compact) {
                int slot=columnSlot(x,z);
                if(nativeColumns[slot]==null) {xs[count]=x;zs[count++]=z;}
            } else {
                int ox=Math.floorDiv(x,4)*4,oz=Math.floorDiv(z,4)*4;
                int page=columnSlot(ox,oz);
                boolean seen=false;
                for(int j=0;j<pageCount;j++)if(pages[j]==page){seen=true;break;}
                if(seen)continue;
                pages[pageCount++]=page;
                for(int j=0;j<16;j++) {
                    int xx=ox+j/4,zz=oz+j%4,slot=columnSlot(xx,zz);
                    if(nativeColumns[slot]==null) {xs[count]=xx;zs[count++]=zz;}
                }
            }
        }
        for(int base=0;base<count;base+=64) {
            int size=Math.min(64,count-base);
            int[][] rows=rust.experimentExactBatch(xs,zs,base,size);
            for(int i=0;i<size;i++)nativeColumns[columnSlot(xs[base+i],zs[base+i])]=rows[i];
        }
        return true;
    }
    private int columnSlot(int x, int z) {''')

harness = out/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
text = harness.read_text(encoding='utf-8')
text = text.replace('                sampler.experimentPrepare();',
                    '                sampler.experimentPrepare();\n'
                    '                if (mode.equals("baseline") && !Boolean.getBoolean("vss.experiment.reuseTrace"))\n'
                    '                    DiskModuleFixtures.run(output,sampler,context);')
text = text.replace('                    FeatureHotspotProbe.beginRound(mode,round);',
                    '                    FeatureHotspotProbe.beginRound(mode,round);\n'
                    '                    DiskModuleExperiment.begin(mode);')
text = text.replace('                    FeatureHotspotProbe.save(output,mode,round);',
                    '                    FeatureHotspotProbe.save(output,mode,round);\n'
                    '                    DiskModuleExperiment.save(output,mode,round);')
harness.write_text(text, encoding='utf-8')

# An optional diagnostic records actual quart reuse. Keep it disabled in timing runs.
patch('FeatureHotspotProbe.java', '    private static long repeatedPoints;', '''
    private static final boolean REUSE_TRACE=Boolean.getBoolean("vss.experiment.reuseTrace");
    private static final Set<Long> BIOME_REQUESTS=new HashSet<>(), BIOME_LOADS=new HashSet<>();
    private static long biomeRequests,biomeLoads;
    private static long repeatedPoints;''')
patch('FeatureHotspotProbe.java',
      '        try {return context.noiseBiome(x,y,z);}',
      '        if(REUSE_TRACE){biomeRequests++;BIOME_REQUESTS.add(net.minecraft.core.BlockPos.asLong(x,y,z));}\n'
      '        try {return context.noiseBiome(x,y,z);}')
patch('FeatureHotspotProbe.java',
      '        try {return source.getNoiseBiome(x,y,z,climate);}',
      '        if(REUSE_TRACE){biomeLoads++;BIOME_LOADS.add(net.minecraft.core.BlockPos.asLong(x,y,z));}\n'
      '        try {return source.getNoiseBiome(x,y,z,climate);}')
patch('FeatureHotspotProbe.java',
      'static void beginRound(String selectedMode,int selectedRound) {reset();mode=selectedMode;round=selectedRound;oracleSkipped=0;}',
      'static void beginRound(String selectedMode,int selectedRound) {reset();mode=selectedMode;round=selectedRound;oracleSkipped=0;'
      'BIOME_REQUESTS.clear();BIOME_LOADS.clear();biomeRequests=biomeLoads=0;}')
patch('FeatureHotspotProbe.java', '        report.put("oracleSkipped",oracleSkipped);',
      '        report.put("oracleSkipped",oracleSkipped);\n'
      '        report.put("reuseTrace",REUSE_TRACE);\n'
      '        report.put("javaQuartRequests",biomeRequests);report.put("uniqueJavaQuartRequests",BIOME_REQUESTS.size());\n'
      '        report.put("javaQuartLoads",biomeLoads);report.put("uniqueJavaQuartLoads",BIOME_LOADS.size());')
manifest = {'patches': patches, 'scope': 'Isolated disk modules only. Production DLL unchanged. No oracle skipping.',
            'sources': {p.relative_to(out).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                        for directory in ['main-java', 'harness-java'] for p in (out/directory).rglob('*.java')},
            'resourceSha256': hashlib.sha256(resource.read_bytes()).hexdigest()}
(out/'module-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(out)
