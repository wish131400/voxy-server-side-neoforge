"""Freeze current sources and run a real version runtime without touching release files."""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
p=argparse.ArgumentParser();p.add_argument('platform',choices=['neoforge','forge','fabric']);p.add_argument('target');p.add_argument('output',type=Path)
p.add_argument('--fabric-root',type=Path,default=ROOT.parent/'voxy-server-side-Horizon')
p.add_argument('--jdk',type=Path,required=True);p.add_argument('--rounds',type=int,default=5);p.add_argument('--warmups',type=int,default=2)
p.add_argument('--side',type=int,default=16);p.add_argument('--step',type=int,default=4);p.add_argument('--gpu',action='store_true');p.add_argument('--compile-only',action='store_true')
p.add_argument('--probe-soil',action='store_true')
p.add_argument('--jfr',action='store_true',help='Record sampled complete-path CPU/allocation stacks; profile runs are separate from timing comparisons')
p.add_argument('--hotspots',action='store_true',help='Attribute 26.3 full-path time to named features in the isolated snapshot')
p.add_argument('--exact-demand',action='store_true',help='Isolated experiment: fetch only the exact decoration column requested')
p.add_argument('--biome-absence',action='store_true',help='Isolated experiment: conservative high-count feature biome absence')
p.add_argument('--biome-answer-cache',action='store_true',help='Isolated experiment: bounded exact quart-biome answers shared across decoration proxies')
p.add_argument('--native-library',type=Path,help='Use a candidate Windows native library in the frozen snapshot only')
p.add_argument('--no-biome-pruning',action='store_true',help='Isolated control: keep original feature execution with the same native library')
a=p.parse_args();out=a.output.resolve();project=ROOT if a.platform=='neoforge' else ROOT.parent/'voxyserverside' if a.platform=='forge' else a.fabric_root.resolve()
out.mkdir(parents=True,exist_ok=False)
env=os.environ.copy();env['JAVA_HOME']=str(a.jdk)
base=[str(project/'gradlew.bat'),f'-Dorg.gradle.java.home={a.jdk}','--offline','--console=plain','--max-workers=2']
if a.platform=='fabric':base+=[f'-PmcTarget={a.target}']

def execute(command,name):
    with (out/name).open('w',encoding='utf-8') as log:result=subprocess.run(command,cwd=project,env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:
        print((out/name).read_text(encoding='utf-8',errors='replace')[-7000:]);raise SystemExit(result.returncode)

if a.platform=='fabric' and a.target!='1.21.11':
    execute(base+['assembleMainSources'],'assemble.log')
    source=project/f'build/{a.target}/generated/sources/main'
else:source=project/'src/main/java'
shutil.copytree(source,out/'main-java')
# Fix only the stage's external shuffle entropy for reproducible same-version
# CPU/GPU outputs. This does not consume the ordered Feature RNG stream.
stage=out/'main-java/dev/xantha/vss/client/prediction/RustVegetationStage.java'
stage_text=stage.read_text(encoding='utf-8');needle='java.util.concurrent.ThreadLocalRandom.current().nextLong()'
assert stage_text.count(needle)==1
stage.write_text(stage_text.replace(needle,'sampler.profile().seed() ^ (long)x * 341873128712L ^ (long)z * 132897987541L ^ step'),encoding='utf-8')
resources=out/'main-resources';shutil.copytree(project/'src/main/resources',resources)
if a.native_library:
    shutil.copy2(a.native_library.resolve(),resources/'META-INF/vss-natives/windows-x86_64/vss_native_core.dll')
if a.platform=='fabric':
    overlays={'1.20.1':['mc120'],'1.21.1':['mc121'],'1.21.11':[],
              '26.1':['mc26'],'26.1.1':['mc26'],'26.1.2':['mc26'],
              '26.2':['mc26','mc262'],'26.3':['mc26','mc262','mc263']}[a.target]
    for overlay in overlays:
        directory=project/'src/versions'/overlay/'resources'
        if directory.exists():shutil.copytree(directory,resources,dirs_exist_ok=True)
    metadata=resources/'fabric.mod.json';doc=json.loads(metadata.read_text(encoding='utf-8'))
    doc['entrypoints']['main'].append('dev.xantha.vss.benchmark.VersionLoadingEntrypoint')
    metadata.write_text(json.dumps(doc,indent=2),encoding='utf-8')

java=(HERE/'CrossVersionBenchmark.java').read_text(encoding='utf-8')
if a.jfr:
    java=java.replace('                var threads=(com.sun.management.ThreadMXBean)', '''                try(var recording=new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"))) {
                recording.start();
                var threads=(com.sun.management.ThreadMXBean)''')
    java=java.replace('            Files.writeString(out.resolve("complete.txt"),"success\\n");', '''                recording.stop();recording.dump(out.resolve("generation.jfr"));
                }
            Files.writeString(out.resolve("complete.txt"),"success\\n");''')
    # The recording belongs to the sampler scope, and must be closed before it.
    java=java.replace('''            }
                recording.stop();recording.dump''','''                recording.stop();recording.dump''')
    java=java.replace('''                }
            Files.writeString(out.resolve("complete.txt")''','''                }
            }
            Files.writeString(out.resolve("complete.txt")''')
if a.target=='26.3':
    java=java.replace('''        for(int dz=0;dz<grid;dz+=8)for(int dx=0;dx<grid;dx+=8)
            sampler.sampleDisplayGridInPlace(x+dx*step,z+dz*step,step,Math.min(8,grid-dx),Math.min(8,grid-dz),samples,dz*grid+dx,grid);''',
        '        sampler.sampleDisplayRegion(x,z,step,grid,samples,()->true);')
    java=java.replace('return Map.of();','return sampler.displayPerformanceSnapshot();')
    java=java.replace('private static void prepareGpu(RustTerrainSampler sampler,Path output)throws Exception{}','''private static void prepareGpu(RustTerrainSampler sampler,Path output)throws Exception{
        boolean gpu=Boolean.getBoolean("vss.bench.gpu");
        dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionVulkanAcceleration=gpu;
        if(!gpu)return;
        long start=System.nanoTime(),deadline=start+java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        sampler.sampleDisplayRegion(-511,-733,16,16,new ClientColumnSample[256],()->true);
        while(true){
            var stats=(Map<?,?>)sampler.displayPerformanceSnapshot().get("vulkan");String state=stats.get("state").toString();
            if(state.startsWith("ready:")||state.startsWith("fallback:")||state.contains("cpu-faster"))break;
            if(System.nanoTime()>deadline)throw new AssertionError("GPU init timed out: "+stats);Thread.sleep(10);
        }
        Files.writeString(output.resolve("gpu-initialization.json"),JSON.toJson(Map.of("elapsedMs",(System.nanoTime()-start)/1e6,"stats",sampler.displayPerformanceSnapshot())));
    }''')
package=out/'main-java/dev/xantha/vss'
if a.no_biome_pruning:
    level=package/'client/prediction/PredictionVegetation.java'
    text=level.read_text(encoding='utf-8')
    before='                    if (pruning.excludes(feature,level,origin)) continue;'
    assert text.count(before)==1
    level.write_text(text.replace(before,'                    // Isolated control: retain all original feature attempts.'),encoding='utf-8')
if a.exact_demand:
    level=package/'client/prediction/PredictionDecorationLevel.java'
    text=level.read_text(encoding='utf-8')
    before='''            int ox = Math.floorDiv(x, 4) * 4, oz = Math.floorDiv(z, 4) * 4;
            int[][] rows = rust.exactSurfacePage(ox, oz, 4);
            for (int i = 0; i < 16; i++) nativeColumns[columnSlot(ox + i / 4, oz + i % 4)] = rows[i];
            record = nativeColumns[slot];'''
    assert text.count(before)==1
    level.write_text(text.replace(before,'''            record = rust.surfaceRecord(x, z);
            nativeColumns[slot] = record;'''),encoding='utf-8')
if a.probe_soil:
    tree=package/'client/prediction/PredictionTreeModels.java';text=tree.read_text(encoding='utf-8')
    text=text.replace('BlockState ground = level.getBlockState(pos.below());','BlockState ground = level.getBlockState(pos.below());\n            TreeSoilAudit.record(ground,level.getBlockState(pos),pos);')
    assert 'TreeSoilAudit.record' in text
    tree.write_text(text,encoding='utf-8')
    (package/'client/prediction/TreeSoilAudit.java').write_text('''package dev.xantha.vss.client.prediction;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
final class TreeSoilAudit {
 static final Map<String,Integer> counts=new TreeMap<>();static final List<String> examples=new ArrayList<>();
 static void record(BlockState ground,BlockState above,BlockPos pos){String key=ground+" dirt="+ground.is(net.minecraft.tags.BlockTags.DIRT)+" groundFluid="+!ground.getFluidState().isEmpty()+" aboveFluid="+!above.getFluidState().isEmpty();counts.merge(key,1,Integer::sum);if(examples.size()<16)examples.add(pos+" "+key);}
 static void save(java.nio.file.Path output)throws Exception{java.nio.file.Files.writeString(output.resolve("tree-soil-audit.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("counts",counts,"examples",examples)));}
}
''',encoding='utf-8')
    java=java.replace('Files.writeString(out.resolve("complete.txt"),"success\\n");','TreeSoilAudit.save(out);\n            Files.writeString(out.resolve("complete.txt"),"success\\n");')
(package/'client/prediction/CrossVersionBenchmark.java').write_text(java,encoding='utf-8')
if a.hotspots:
    if a.target!='26.3':raise ValueError('Named feature attribution currently targets 26.3')
    from instrument_hotspots import instrument
    instrument(out)
if a.biome_absence:
    if a.target!='26.3':raise ValueError('Biome absence experiment targets 26.3')
    import sys
    subprocess.run([sys.executable,str(a.fabric_root.resolve()/'tools/performance/biome-bounds/prepare.py'),str(out)],check=True)
if a.biome_answer_cache:
    if a.platform!='fabric' or a.target!='26.3':raise ValueError('Biome answer cache experiment targets Fabric 26.3')
    from instrument_biome_answers import instrument
    instrument(out)
entry=package/'benchmark/VersionLoadingEntrypoint.java';entry.parent.mkdir(exist_ok=True)
if a.platform=='fabric':
    entry.write_text('''package dev.xantha.vss.benchmark;
public final class VersionLoadingEntrypoint implements net.fabricmc.api.ModInitializer {
 public void onInitialize(){net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server->server.execute(()->dev.xantha.vss.client.prediction.CrossVersionBenchmark.run(server)));}
}
''',encoding='utf-8')
elif a.platform=='forge':
    entry.write_text('''package dev.xantha.vss.benchmark;
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="vss")
public final class VersionLoadingEntrypoint {
 @net.minecraftforge.eventbus.api.SubscribeEvent public static void started(net.minecraftforge.event.server.ServerStartedEvent event){var server=event.getServer();server.execute(()->dev.xantha.vss.client.prediction.CrossVersionBenchmark.run(server));}
}
''',encoding='utf-8')
else:
    entry.write_text('''package dev.xantha.vss.benchmark;
@net.neoforged.fml.common.EventBusSubscriber(modid="vss")
public final class VersionLoadingEntrypoint {
 @net.neoforged.bus.api.SubscribeEvent public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){var server=event.getServer();server.execute(()->dev.xantha.vss.client.prediction.CrossVersionBenchmark.run(server));}
}
''',encoding='utf-8')
server=out/'server';server.mkdir()
(server/'eula.txt').write_text('eula=true\n')
(server/'server.properties').write_text('''server-ip=127.0.0.1
server-port=0
online-mode=false
level-seed=42
level-name=benchmark-world
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=1
sync-chunk-writes=false
max-tick-time=-1
allow-nether=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
''')
manifest={'platform':a.platform,'target':a.target,'gpu':a.gpu,'jdk':str(a.jdk),'rounds':a.rounds,'warmups':a.warmups,'side':a.side,'step':a.step,
          'soilProbe':a.probe_soil,'jfrProfile':a.jfr,'featureAttribution':a.hotspots,'exactDemandExperiment':a.exact_demand,'biomeAbsenceExperiment':a.biome_absence,'biomeAnswerCacheExperiment':a.biome_answer_cache,
          'nativeCandidateSha256':hashlib.sha256(a.native_library.read_bytes()).hexdigest() if a.native_library else None,
          'biomePruningDisabledControl':a.no_biome_pruning,
          'isolatedPatch':'Fix external native-stage shuffle entropy from world seed/coordinates/stage; no Feature RNG stream change',
          'scope':'Current frozen production generation: terrain -> ordered decoration -> Voxy source; actual server resources; no disk, scheduler, renderer or frame time',
          'sources':{f.relative_to(out).as_posix():hashlib.sha256(f.read_bytes()).hexdigest() for d in ['main-java','main-resources'] for f in (out/d).rglob('*') if f.is_file()}}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
command=base+['-I',str(HERE/(a.platform+'.gradle')),f'-PvssBenchRoot={out}',f'-PserverRunDir={Path(os.path.relpath(server,project)).as_posix()}',f'-PvssBenchRounds={a.rounds}',f'-PvssBenchWarmups={a.warmups}',f'-PvssBenchSide={a.side}',f'-PvssBenchStep={a.step}',f'-PvssBenchGpu={str(a.gpu).lower()}','compileJava' if a.compile_only else 'runServer']
execute(command,'compile.log' if a.compile_only else 'launch.log')
if not a.compile_only:
    assert not (out/'failure.txt').exists(),(out/'failure.txt').read_text()
    assert (out/'complete.txt').read_text().strip()=='success',out
print('Complete:',out,flush=True)
