"""Compare demand heights and early pure rejection together and separately."""
from pathlib import Path
import argparse,json,hashlib,shutil,subprocess,sys,tempfile
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
out=parser.parse_args().output.resolve()
project=Path(__file__).resolve().parents[3]
prototype=Path(__file__).parent/'prototypes'
with tempfile.TemporaryDirectory(prefix='vss-demand-') as temporary:
 source=Path(temporary)
 command=[sys.executable,str(Path(__file__).parent/'prepare.py'),str(out)]
 archived='surfaceHeights' not in (project/'src/main/java/dev/xantha/vss/client/prediction/RustWorldgenBackend.java').read_text()
 if archived:
  manifest=json.loads((prototype/'demand-manifest.json').read_text())
  assert hashlib.sha256((prototype/'demand.patch').read_bytes()).hexdigest()==manifest['patchSha256'],'Prototype patch changed'
  for name,entry in manifest['files'].items():
   baseline=project/name
   if entry['baselineSha256'] is None:
    assert not baseline.exists(),f'Unexpected new source: {name}'
   else:
    assert hashlib.sha256(baseline.read_bytes()).hexdigest()==entry['baselineSha256'],f'Baseline changed: {name}'
    target=source/name;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(baseline.read_text(encoding='utf-8').encode('utf-8'))
  # Both archived diffs and Windows checkouts can contain CRLF. Apply matching
  # LF text in the temporary input only; never change the production files.
  patch_text=(prototype/'demand.patch').read_text(encoding='utf-8')
  subprocess.run(['git','-c','core.autocrlf=false','apply','--unsafe-paths','--directory='+str(source)],
                 input=patch_text.encode('utf-8'),cwd=project,check=True)
  for name,entry in manifest['files'].items():
   assert hashlib.sha256((source/name).read_bytes()).hexdigest()==entry['normalizedPrototypeSha256'],f'Prototype changed: {name}'
  command+=['--source-overlay',str(source)]
 subprocess.run(command,check=True)
 if archived:shutil.copytree(source,out/'prototype-input')
package=out/'main-java/dev/xantha/vss/client/prediction'
patches=[]
def patch(name,old,new):
 p=package/name;t=p.read_text(encoding='utf-8');assert t.count(old)==1,(name,old,t.count(old))
 p.write_text(t.replace(old,new),encoding='utf-8');patches.append({'file':name,'old':old,'new':new})
patch('DecorationExperimentSettings.java','    static boolean nativeBiomes, scan;',
      '    static boolean nativeBiomes, scan, demandHeights, eagerBiome;')
patch('DecorationExperimentSettings.java','    static void select(String mode) {',
      '    static void select(String mode) {\n        demandHeights=mode.equals("height")||mode.equals("combined");\n        eagerBiome=mode.equals("eager")||mode.equals("combined");')
patch('PredictionDecorationLevel.java','if(!displayTerrain && (nativeColumns==null',
      'if(DecorationExperimentSettings.demandHeights && !displayTerrain && (nativeColumns==null')
patch('PredictionDecorationLevel.java','''        return context.generatorContext()!=null
                && !context.generatorContext().getBiomeGenerationSettings(getBiome(pos)).hasFeature(feature);''',
      '''        if(!DecorationExperimentSettings.eagerBiome)return cachedBiomeRejects(feature,pos);
        return context.generatorContext()!=null
                && !context.generatorContext().getBiomeGenerationSettings(getBiome(pos)).hasFeature(feature);''')
patch('RustTerrainSampler.java','RustWorldgenBackend.surfaceHeights(id,input,output,16)',
      'FeatureHotspotProbe.heights(id,input,output,16)')
(out/'demand-overlay.json').write_text(json.dumps({'patches':patches,'sources':{
 p.relative_to(out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in package.glob('*.java')}},indent=2),encoding='utf-8')
print(out)
