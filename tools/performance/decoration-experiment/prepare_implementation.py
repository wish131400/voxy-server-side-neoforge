"""Compare corrected scalar, exact-page and rejection paths without release-only test switches."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

project = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
parser.add_argument('--source-overlay', type=Path)
args = parser.parse_args()
root = args.directory.resolve()
package = Path('dev/xantha/vss/client/prediction')
names = ['RustTerrainSampler.java', 'PredictionDecorationLevel.java', 'PredictionPlacementExecutor.java',
         'PredictionPlacementPruning.java', 'PredictionVegetation.java', 'PredictionTreeModels.java', 'RustWorldgenBackend.java']
hashes = {}
for name in names:
    source = project/'src/main/java'/package/name
    if args.source_overlay and (args.source_overlay/'src/main/java'/package/name).exists():
        source = args.source_overlay/'src/main/java'/package/name
    hashes['src/main/java/'+(package/name).as_posix()] = hashlib.sha256(source.read_bytes()).hexdigest()
    shutil.copy2(source, root/'main-java'/package/name)

p = root/'main-java'/package/'RustTerrainSampler.java'
text = p.read_text()
text = text.replace('    private int[] point(int x, int z) {',
                    '    void experimentPrepare() { }\n    private int[] point(int x, int z) {')
text = text.replace('        if(RustWorldgenBackend.surfacePoints(handle(),input,output,pending)!=pending)',
                    '        DecorationExperimentSettings.surfaceCalls++;\n'
                    '        DecorationExperimentSettings.surfacePoints+=pending;\n'
                    '        if(RustWorldgenBackend.surfacePoints(handle(),input,output,pending)!=pending)')
p.write_text(text)
p = root/'main-java'/package/'PredictionDecorationLevel.java'
text = p.read_text()
old = '''            int ox = Math.floorDiv(x,4)*4, oz = Math.floorDiv(z,4)*4;
            int[][] rows = rust.exactSurfacePage(ox,oz,4);
            for (int i=0;i<16;i++) nativeColumns[columnSlot(ox+i/4,oz+i%4)] = rows[i];'''
assert text.count(old) == 1
text = text.replace(old, '''            int n=DecorationExperimentSettings.page;
            int ox = Math.floorDiv(x,n)*n, oz = Math.floorDiv(z,n)*n;
            int[][] rows = rust.exactSurfacePage(ox,oz,n);
            for (int i=0;i<n*n;i++) nativeColumns[columnSlot(ox+i/n,oz+i%n)] = rows[i];''')
text = text.replace('        Holder<Biome> biome = context.noiseBiome(x, y, z);',
                    '        DecorationExperimentSettings.biomeQueries++;\n        Holder<Biome> biome = context.noiseBiome(x, y, z);')
p.write_text(text)
p = root/'main-java'/package/'PredictionPlacementExecutor.java'
text = p.read_text().replace('if(!rejection.rejects(run.context,pos))',
                            'if(!DecorationExperimentSettings.scan || !experimentReject(rejection,run.context,pos))')
text = text.replace('    static boolean available()', '''    private static boolean experimentReject(PredictionPlacementPruning.Rejection rule, PlacementContext context, BlockPos pos) {
        DecorationExperimentSettings.prefilterChecks++;
        boolean rejected=rule.rejects(context,pos);
        if(rejected)DecorationExperimentSettings.prefilterRejected++;
        return rejected;
    }
    static boolean available()''')
p.write_text(text)
p = root/'main-java'/package/'DecorationExperimentSettings.java'
text = p.read_text().replace('scan = mode.equals("scan")', 'scan = mode.equals("pruned") || mode.equals("scan")')
text = text.replace('page = mode.equals("page4")', 'page = mode.equals("pruned") || mode.equals("page4")')
p.write_text(text)

p = root/'main-java'/package/'PredictionTreeModels.java'
text = p.read_text().replace('if (PredictionPlacementExecutor.available())',
    'if (DecorationExperimentSettings.scan && PredictionPlacementExecutor.available())')
p.write_text(text)
p = root/'main-java'/package/'DecorationExperimentSettings.java'
text = p.read_text().replace('    static long surfaceCalls,',
    '    static long prefilterChecks, prefilterRejected;\n    static long surfaceCalls,')
text = text.replace('        surfaceCalls=surfacePoints=',
    '        prefilterChecks=prefilterRejected=0;\n        surfaceCalls=surfacePoints=')
p.write_text(text)
p = root/'harness-java'/package/'PredictionGenerationComparison.java'
text = p.read_text().replace('row.put("scans",DecorationExperimentSettings.scans);',
    'row.put("prefilterChecks",DecorationExperimentSettings.prefilterChecks);'
    'row.put("prefilterRejected",DecorationExperimentSettings.prefilterRejected);'
    'row.put("scans",DecorationExperimentSettings.scans);')
p.write_text(text)
dll = project/'src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'
shutil.copy2(dll, root/'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')
p = root/'input-manifest.json'
manifest = json.loads(p.read_text())
manifest['experimentDllSha256'] = hashlib.sha256(dll.read_bytes()).hexdigest()
manifest['implementationSources'] = hashes
p.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(root)
