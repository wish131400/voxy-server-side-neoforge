"""Measure spatially contiguous exact work, without changing display or decoration semantics."""
from pathlib import Path
import argparse,hashlib,json,subprocess,sys
root=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
out=parser.parse_args().output.resolve()
subprocess.run([sys.executable,str(Path(__file__).parent/'prepare.py'),str(out)],check=True)
package=out/'main-java/dev/xantha/vss/client/prediction'
p=package/'DecorationExperimentSettings.java'
t=p.read_text().replace('    static boolean nativeBiomes, scan;', '    static boolean nativeBiomes, scan, fullChunk;')
t=t.replace('    static void select(String mode) {',
    '    static void select(String mode) {\n        fullChunk=mode.equals("fullchunk");')
p.write_text(t,encoding='utf-8')
p=package/'PredictionDecorationLevel.java'
t=p.read_text()
old='''            int n=DecorationExperimentSettings.page;
            int ox = Math.floorDiv(x,n)*n, oz = Math.floorDiv(z,n)*n;
            int[][] rows = rust.exactSurfacePage(ox,oz,n);
            for (int i=0;i<n*n;i++) nativeColumns[columnSlot(ox+i/n,oz+i%n)] = rows[i];'''
new='''            if(DecorationExperimentSettings.fullChunk) {
                int ox=Math.floorDiv(x,16)*16,oz=Math.floorDiv(z,16)*16;
                for(int dx=0;dx<16;dx+=4)for(int dz=0;dz<16;dz+=4) {
                    int[][] rows=rust.exactSurfacePage(ox+dx,oz+dz,4);
                    for(int i=0;i<16;i++)nativeColumns[columnSlot(ox+dx+i/4,oz+dz+i%4)]=rows[i];
                }
            } else {
'''+old+'''
            }'''
assert t.count(old)==1
p.write_text(t.replace(old,new),encoding='utf-8')
(out/'chunk-overlay.json').write_text(json.dumps({'old':old,'new':new,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()},indent=2))
print(out)
