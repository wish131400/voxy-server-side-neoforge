"""Offline-only ablation of the current display path. Invoked by the JNI runner.

Instrument a frozen crate copy, never the production crate or packaged DLL.
Partial stages deliberately defer exact fallback; report candidate rejection
separately from actual fallback. Only the full stage enables adaptive sampling.
"""
import csv
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import zipfile

PROBE = r'''
use std::{cell::RefCell, collections::BTreeMap, sync::OnceLock, time::Instant};
use jni::{JNIEnv, objects::JClass, sys::jstring};
#[derive(Default)] struct Data { times: BTreeMap<&'static str,u64>, counts: BTreeMap<&'static str,u64> }
thread_local! { static DATA: RefCell<Data> = RefCell::new(Data::default()); }
pub fn mode() -> u8 {
    static MODE: OnceLock<u8> = OnceLock::new();
    *MODE.get_or_init(|| std::env::var("VSS_STAGE_PROBE").unwrap_or_else(|_|"3".into()).parse().unwrap())
}
pub fn count(name: &'static str, n: usize) { DATA.with(|d| *d.borrow_mut().counts.entry(name).or_default() += n as u64); }
pub struct Scope { name: &'static str, start: Instant }
impl Scope { pub fn new(name: &'static str) -> Self { Self {name,start:Instant::now()} } }
impl Drop for Scope { fn drop(&mut self) { let ns=self.start.elapsed().as_nanos() as u64;
    DATA.with(|d| *d.borrow_mut().times.entry(self.name).or_default() += ns); } }
#[no_mangle] pub extern "system" fn Java_StageProbe_reset(_:JNIEnv<'_>,_:JClass<'_>) {
    DATA.with(|d| *d.borrow_mut()=Data::default());
}
#[no_mangle] pub extern "system" fn Java_StageProbe_stats(env:JNIEnv<'_>,_:JClass<'_>) -> jstring {
    let text=DATA.with(|d| { let d=d.borrow(); serde_json::json!({"times_ns":d.times,"counts":d.counts}).to_string() });
    env.new_string(text).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
'''


def instrument(crate):
    lib = crate / 'src/lib.rs'
    lib.write_text(lib.read_text() + '\npub mod stage_probe;\n')
    (crate / 'src/stage_probe.rs').write_text(PROBE)
    path = crate / 'src/backend/display.rs'
    s = path.read_text()
    def replace(old, new, count=1):
        nonlocal s
        assert s.count(old) == count, (old[:100], s.count(old), count)
        s = s.replace(old, new)
    replace('\nuse super::*;\n', '\nuse super::*;\nuse crate::stage_probe::{mode, count, Scope};\n')
    replace('        self.check_active()?;\n        self.display_queries[0]',
            '        self.check_active()?;\n        count("requested_including_probes", points.len());\n        self.display_queries[0]')
    replace('            return self.surface_points(points);',
            '            if mode() < 3 { return Err("stage probe does not support order-sensitive graphs".into()); }\n'
            '            count("fallback_stateful_points", points.len());\n'
            '            let _scope = Scope::new("exact_fallback");\n            return self.surface_points(points);')
    replace('        if let Some(grid) = self.adaptive_display_grid(points)? {',
            '        if let Some(grid) = if mode() == 3 { self.adaptive_display_grid(points)? } else { None } {\n'
            '            count("adaptive_grids", 1);')
    replace('if biome_scratch.is_none() { biome_scratch = Some(self.terrain.graph.raw_scratch()?); }',
            'if biome_scratch.is_none() { let _scope=Scope::new("raw_setup"); biome_scratch = Some(self.terrain.graph.raw_scratch()?); }')
    replace('            let hint = self.source.sample(&t.graph, q, scratch, &mut None);\n            if !eligible || !self.display_hint_eligible(hint) {',
            '            if !eligible && mode() < 3 { return Err("stage probe cannot isolate a structure-adjusted column".into()); }\n'
            '            let hint_ok = if mode() == 0 { true } else {\n'
            '                let _scope=Scope::new("biome_hint");\n'
            '                self.display_hint_eligible(self.source.sample(&t.graph,q,scratch,&mut None))\n'
            '            };\n'
            '            if !eligible || !hint_ok { count("candidate_hint_rejections", indices.len()); }\n'
            '            if mode() == 3 && (!eligible || !hint_ok) {\n'
            '                count("fallback_hint_points", indices.len());\n'
            '                let _scope=Scope::new("exact_fallback");')
    replace('            let mut job = if eligible {', '            let mut job = { let _scope=Scope::new("job_setup"); if eligible {')
    replace('            job.display = eligible;', '            };\n            job.display = eligible;')
    # The additional block above must return the Job, not a semicolon expression.
    replace('            };\n            };\n            job.display = eligible;',
            '            }\n            };\n            job.display = eligible;')
    replace('                        None => {\n                            self.display_queries[3]',
            '                        None => {\n                            count("fallback_record_points",1);\n'
            '                            let _scope=Scope::new("exact_fallback");\n                            self.display_queries[3]')
    start = s.index('    fn display_density_record(')
    end = s.index('    pub fn display_query_stats', start)
    prefix, body, suffix = s[:start], s[start:end], s[end:]
    def body_replace(old, new, count=1):
        nonlocal body
        assert body.count(old) == count, (old[:100], body.count(old), count)
        body = body.replace(old, new)
    body_replace('        let floor = job.density_surface(x, z).map_or(t.min_y, |y| y + 1);',
                 '        let floor = { let _scope=Scope::new("density_boundary"); count("density_columns",1);\n'
                 '            job.density_surface(x,z).map_or(t.min_y,|y|y+1) };\n'
                 '        if mode() == 0 { return Ok(Some(SurfaceColumn { values: [floor,floor,0,DISPLAY,0,0,0,0,0,0] })); }')
    body_replace('        if floor != t.min_y && !self.display_liquids_supported()\n'
                 '            && !job.display_fluid_band_matches(x, z, floor, fluid_y, fluid) {\n            return Ok(None);\n        }',
                 '        if mode() >= 2 {\n'
                 '            let _scope=Scope::new("liquid_validation");\n'
                 '            if floor != t.min_y && !self.display_liquids_supported() {\n'
                 '                count("liquid_checked_columns",1);\n'
                 '                if !job.display_fluid_band_matches(x,z,floor,fluid_y,fluid) {\n'
                 '                    count("candidate_liquid_rejections",1);\n'
                 '                    if mode() == 3 { return Ok(None); }\n'
                 '                }\n            }\n        }')
    body_replace('        let name = self.source.sample(&t.graph, q, scratch, &mut None);\n'
                 '        if !self.display_biome_at(name, x, z) { return Ok(None); }',
                 '        let name = { let _scope=Scope::new("biome_floor"); self.source.sample(&t.graph,q,scratch,&mut None) };\n'
                 '        if !self.display_biome_at(name, x, z) { count("candidate_biome_rejections",1); if mode()==3 { return Ok(None); } }')
    body_replace('        if fluid != 0 {', '        if mode() >= 2 && fluid != 0 {\n            let _scope=Scope::new("biome_water");')
    body_replace('                return Ok(None);\n            }\n        }\n        let biome',
                 '                count("candidate_water_biome_rejections",1);\n'
                 '                if mode()==3 { return Ok(None); }\n            }\n        }\n        let biome')
    body_replace('        let materials = if floor == t.min_y {',
                 '        let materials = { let _scope=Scope::new("slope_materials"); if floor == t.min_y {')
    body_replace('        if floor != t.min_y && materials.iter()', '        };\n        if floor != t.min_y && materials.iter()')
    body_replace('        };\n        };\n        if floor != t.min_y', '        }\n        };\n        if floor != t.min_y')
    body_replace('            return Ok(None);\n        }\n        let row = self.record_surface',
                 '            count("candidate_material_rejections",1);\n            if mode()==3 { return Ok(None); }\n        }\n'
                 '        if mode()<3 { return Ok(Some(SurfaceColumn { values: [floor,fluid_y,fluid,DISPLAY,materials[0] as i32,materials[1] as i32,materials[2] as i32,0,0,0] })); }\n'
                 '        let _scope=Scope::new("colors_record");\n        let row = self.record_surface')
    path.write_text(prefix + body + suffix)
    # Count proof reuse in the same isolated copy; exact surface/blocks do not
    # enable reuse_density_cells, and no production instrumentation is added.
    terrain = crate / 'src/terrain.rs'
    text = terrain.read_text()
    old = '                let empty = if let Some(&empty) = self.density_cells.get(&base) { empty } else {'
    assert text.count(old) == 1
    text = text.replace(old,
        '                let empty = if let Some(&empty) = self.density_cells.get(&base) { crate::stage_probe::count("density_cell_reuses",1); empty } else {\n'
        '                    crate::stage_probe::count("density_cell_proofs",1);')
    old = '                if empty { continue; }'
    assert text.count(old) == 1
    text = text.replace(old, '                if empty { crate::stage_probe::count("density_cell_skips",1); continue; }')
    old = '        if !t.graph.has_stateful_queries() || t.graph.has_initial_height_plan() {'
    assert text.count(old) == 1
    text = text.replace(old, old + '\n            crate::stage_probe::count("initial_interval_searches",1);')
    old = '        let mut y = t.min_y + t.height;'
    assert text.count(old) == 1
    text = text.replace(old, '        crate::stage_probe::count("initial_linear_searches",1);\n' + old)
    terrain.write_text(text)
    density=crate/'src/density.rs'
    text=density.read_text()
    old='let values=if let Some((_,values))=s.column_edges[*slot].filter(|(p,_)|*p==base) { values } else {'
    assert text.count(old)==1
    text=text.replace(old,'let values=if let Some((_,values))=s.column_edges[*slot].filter(|(p,_)|*p==base) { crate::stage_probe::count("lattice_edge_reuses",1); values } else {\n'
        '                            crate::stage_probe::count("lattice_edges_loaded",1);')
    old = '                        [edge[0], edge[0], edge[1], edge[1],'
    assert text.count(old) == 1
    text = text.replace(old, '                        crate::stage_probe::count("interpolation_edge_reuses",1);\n' + old)
    density.write_text(text)


def run(args, repo):
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    evidence = args.evidence_dir.resolve()
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    crate = out / 'diagnostic-crate'
    crate.mkdir()
    origin = repo / 'tools/rust/vss-native-core'
    source_hashes = {str(p.relative_to(origin)):sha(p) for p in (origin/'src').rglob('*.rs')}
    shutil.copytree(origin/'src', crate/'src')
    for name in ['Cargo.toml','Cargo.lock']:
        shutil.copy2(origin/name, crate/name)
    instrument(crate)
    build_env = os.environ.copy()
    build_env['CARGO_PROFILE_RELEASE_STRIP'] = 'symbols'
    with (out/'build.log').open('w') as log:
        result = subprocess.run([str(Path.home()/'.cargo/bin/cargo.exe'),'build','--manifest-path',str(crate/'Cargo.toml'),
                                 '--locked','--release','--lib'],stdout=log,stderr=subprocess.STDOUT,env=build_env)
    result.check_returncode()
    shutil.copy2(crate/'target/release/vss_native_core.dll',out/'diagnostic.dll')
    jar = repo/'build/libs/vss-0.3-neoforge-1.21.1.jar'
    with zipfile.ZipFile(jar) as z:
        (out/'shipping.dll').write_bytes(z.read('META-INF/vss-natives/windows-x86_64/vss_native_core.dll'))
    shutil.copy2(evidence/'current-comparison/shared-document.json',out/'vanilla.json')
    docs={'vanilla':out/'vanilla.json'}
    if args.extra_document:
        shutil.copy2(args.extra_document,out/'captured-derived.json')
        docs['captured-derived']=out/'captured-derived.json'
    source=out/'java';source.mkdir();classes=out/'classes';classes.mkdir()
    (source/'StageProbe.java').write_text('public class StageProbe { public static native void reset(); public static native String stats(); }')
    for name in ['DisplayGridBench','ExactGridBench']:
        text=(evidence/f'implementation/{name}.java').read_text()
        text=text.replace(name,'Stage'+name)
        text=text.replace('    long ns=0,hash=', '    if(Boolean.getBoolean("vss.stageProbe")) StageProbe.reset();\n    long ns=0,hash=')
        text=text.replace('     if(scenario==7) query(h,x,z,side,step,false);',
                          '     if(scenario==7) { query(h,x,z,side,step,false); if(Boolean.getBoolean("vss.stageProbe")) StageProbe.reset(); }')
        text=text.replace('   } finally { if(vss)',
                          '   } finally { if(Boolean.getBoolean("vss.stageProbe")) System.err.println(StageProbe.stats()); if(vss)')
        (source/('Stage'+name+'.java')).write_text(text)
    shutil.copy2(evidence/'current-comparison/MeridianNative.java',source/'MeridianNative.java')
    shutil.copy2(repo/'src/main/java/dev/xantha/vss/client/prediction/RustWorldgenBackend.java',source/'RustWorldgenBackend.java')
    subprocess.run([str(args.jdk/'bin/javac.exe'),'-d',str(classes),*map(str,source.glob('*.java'))],check=True)
    modes=['boundary','material','liquid','full','exact','shipping']
    scenarios=['tile66_step1','batch8_step1','batch8_step4','batch8_step64','tile66_warm']
    manifest=dict(time=datetime.datetime.now().isoformat(),rounds=args.rounds,source=source_hashes,
                  libraries={k:sha(out/(k+'.dll')) for k in ['diagnostic','shipping']},jar_sha256=sha(jar),
                  inputs={k:sha(v) for k,v in docs.items()},seeds={'vanilla':[0,917],'captured-derived':[0]},
                  stages=modes,scenarios=scenarios,
                  semantics='Boundary: density only, no biome hint/adaptive/fallback. Material: add biome, slope and materials using cheap sea-plane context. Liquid: add actual custom-fluid band validation and water-biome check. Partial stages report rejected candidates but defer exact fallback. Full: unchanged production display including adaptive sampling, tint and exact fallback. Exact: same isolated library surfacePoints. Shipping: packaged displayPoints. Timers reset after warmup and after warm priming. Substage timers in full are disjoint; cumulative stages cannot be blindly subtracted. Water is N/A for boundary/material. JNI queries only; fresh process/world per measurement.')
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2))
    rows=[]
    with (out/'runs.jsonl').open('w') as stream:
        for repeat in range(args.rounds):
            for world,doc in docs.items():
                for seed in ([0,917] if world=='vanilla' else [0]):
                    for scene in scenarios:
                        # Rotate and reverse so a stage is not always first/last.
                        order=modes[repeat%len(modes):]+modes[:repeat%len(modes)]
                        if (repeat+(seed!=0))%2:order=order[::-1]
                        for mode in order:
                            dest=out/'runs'/str(repeat)/world/str(seed)/scene/mode;dest.mkdir(parents=True)
                            env=os.environ.copy();env['VSS_STAGE_PROBE']=str(modes.index(mode) if mode in modes[:4] else 3)
                            library=out/('shipping.dll' if mode=='shipping' else 'diagnostic.dll')
                            command=[str(args.jdk/'bin/java.exe'),'-Xms256m','-Xmx1g',f'-Dvss.stageProbe={str(mode!="shipping").lower()}',
                                     '-cp',str(classes),'StageExactGridBench' if mode=='exact' else 'StageDisplayGridBench',
                                     'vss',str(library),str(doc),str(seed),str(dest),scene]
                            proc=subprocess.run(command,capture_output=True,text=True,env=env,timeout=180)
                            (dest/'stdout.txt').write_text(proc.stdout);(dest/'stderr.txt').write_text(proc.stderr)
                            proc.check_returncode()
                            f=proc.stdout.strip().split(',');assert len(f)==9,proc.stdout
                            stats={} if mode=='shipping' else json.loads(proc.stderr.strip().splitlines()[-1])
                            row=dict(round=repeat,world=world,seed=seed,scenario=scene,stage=mode,count=int(f[4]),
                                     init_ms=float(f[5]),ms=float(f[6]),us=float(f[7]),checksum=f[8],**stats)
                            rows.append(row);stream.write(json.dumps(row)+'\n');stream.flush()
                        print(f'round={repeat+1} world={world} seed={seed} scene={scene}',flush=True)
    summarize(out,rows)


def summarize(out, rows):
    summary=[];differences=[]
    for world,seed,scene in sorted({(r['world'],r['seed'],r['scenario']) for r in rows}):
        entries=[r for r in rows if (r['world'],r['seed'],r['scenario'])==(world,seed,scene)]
        stages={}
        for mode in ['boundary','material','liquid','full','exact','shipping']:
            group=[r for r in entries if r['stage']==mode]
            assert len({r['checksum'] for r in group})==1,(world,seed,scene,mode,'unstable output')
            timer_names={k for r in group for k in r.get('times_ns',{})}
            count_names={k for r in group for k in r.get('counts',{})}
            # Counts must be deterministic too; no hidden cache-state drift.
            for name in count_names:assert len({r.get('counts',{}).get(name,0) for r in group})==1
            stages[mode]=dict(median_us=statistics.median(r['us'] for r in group),min_us=min(r['us'] for r in group),
                              max_us=max(r['us'] for r in group),checksum=group[0]['checksum'],
                              phase_us_per_request={k:statistics.median(r.get('times_ns',{}).get(k,0)/1000/r['count'] for r in group) for k in timer_names},
                              counts={k:group[0].get('counts',{}).get(k,0) for k in count_names})
        assert stages['full']['checksum']==stages['shipping']['checksum'],(world,seed,scene,'instrumentation changed full output')
        summary.append(dict(world=world,seed=seed,scenario=scene,count=entries[0]['count'],stages=stages))
        dest=out/'runs/0'/world/str(seed)/scene
        def records(stage):return list(csv.DictReader(next((dest/stage).glob('*.tsv')).open(),delimiter='\t'))
        oracle=records('exact')
        for mode in ['boundary','material','liquid','full']:
            candidate=records(mode);assert len(candidate)==len(oracle)
            assert all((a['x'],a['z'])==(b['x'],b['z']) for a,b in zip(candidate,oracle))
            delta=[abs(int(a['height'])-int(b['height'])) for a,b in zip(candidate,oracle)]
            both_wet=[(a,b) for a,b in zip(candidate,oracle) if a['fluid']==b['fluid'] and int(a['fluid'])!=0]
            water_delta=[abs(int(a['waterY'])-int(b['waterY'])) for a,b in both_wet]
            differences.append(dict(world=world,seed=seed,scenario=scene,stage=mode,count=len(delta),
                height_changed=sum(v!=0 for v in delta),height_mae=statistics.mean(delta),height_max=max(delta),
                water=None if mode in ['boundary','material'] else dict(
                    combined_changed=sum((a['waterY'],a['fluid'])!=(b['waterY'],b['fluid']) for a,b in zip(candidate,oracle)),
                    presence_changed=sum((int(a['fluid'])!=0)!=(int(b['fluid'])!=0) for a,b in zip(candidate,oracle)),
                    kind_changed=sum(a['fluid']!=b['fluid'] for a,b in zip(candidate,oracle)),
                    same_kind_wet_columns=len(both_wet),same_kind_level_changed=sum(v!=0 for v in water_delta),
                    same_kind_level_mae=statistics.mean(water_delta) if water_delta else None,
                    same_kind_level_max=max(water_delta) if water_delta else None),
                materials=None if mode=='boundary' else {k:sum(a[k]!=b[k] for a,b in zip(candidate,oracle)) for k in ['top','under','deep']}))
    (out/'summary.json').write_text(json.dumps(summary,indent=2))
    (out/'differences.json').write_text(json.dumps(differences,indent=2))
    print('All stages deterministic; instrumented full output equals packaged output in every scenario.',flush=True)
