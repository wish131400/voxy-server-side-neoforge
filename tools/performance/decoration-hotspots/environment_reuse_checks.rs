//! Differential checks for the isolated prototype, never release implementation.
use serde_json::{json,Value};
use std::{fs,path::Path};
use vss_native_core::backend::World;
use vss_native_core::density::{Graph,Mode};

fn fixture(dimension:&str)->Value {
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read=|name:&str|serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap();
    let mut doc=read(&format!("{dimension}.json"));
    for (key,file) in [("block_definitions","blocks.json"),("biomes","biomes.json"),
        ("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] {doc[key]=read(file);}
    doc
}
fn pair(doc:Value,seed:i64)->(World,World) {
    let mut candidate=doc.clone();
    candidate["vss_experiment_environment_reuse"]=json!(32768);
    candidate["vss_experiment_corner_reuse"]=json!(32768);
    candidate["vss_experiment_arithmetic"]=json!(true);
    (World::new(seed,0,doc).unwrap(),World::new(seed,0,candidate).unwrap())
}
#[test]
fn exact_display_revisits_and_distant_relocation_preserve_complete_records() {
    for dimension in ["overworld","nether","end","amplified"] {
        for seed in [42,-917] {
            let (a,b)=pair(fixture(dimension),seed);
            for batch in 0..20 {
                let bx=if batch%3==0 {100000+batch*16}else{-32+batch*16};
                let bz=if batch%3==0 {-100000-batch*16}else{32-batch*16};
                let points:Vec<_>=(0..64).map(|i| {
                    let j=if batch%2==0 {i}else{63-i};(bx+j%8,bz+j/8)
                }).collect();
                for display in [false,true,false] {
                    let x=if display {a.display_points(&points)}else{a.surface_points(&points)}.unwrap();
                    let y=if display {b.display_points(&points)}else{b.surface_points(&points)}.unwrap();
                    for (i,(x,y)) in x.iter().zip(y).enumerate() {
                        assert_eq!(x.values,y.values,"{dimension} seed={seed} batch={batch} display={display} p={:?}",points[i]);
                    }
                }
            }
        }
    }
}
#[test]
fn exposed_stateful_and_modified_graphs_keep_the_original_path() {
    let original=fixture("overworld");
    let vertical=json!({"type":"minecraft:cache_2d","argument":{
        "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}});
    for density in [vertical.clone(),json!({"type":"minecraft:flat_cache","argument":vertical}),
        json!({"type":"minecraft:add","argument1":0.1,"argument2":original["settings"]["noise_router"]["final_density"]})] {
        let mut doc=original.clone();doc["settings"]["noise_router"]["final_density"]=density;
        let (a,b)=pair(doc,42);
        for p in [(0,0),(15,15),(16,16),(-1,-1),(-17,3),(100000,-100000),(0,0)] {
            assert_eq!(a.surface_point(p.0,p.1).unwrap().values,b.surface_point(p.0,p.1).unwrap().values);
        }
    }
}

#[test]
fn compact_arithmetic_preserves_all_router_roots_bit_for_bit() {
    for dimension in ["overworld","nether","end","amplified"] {
        let doc=fixture(dimension);
        let mut compiled=doc.clone();compiled["vss_experiment_arithmetic"]=json!(true);
        for seed in [42,-917] {
            let a=Graph::from_document(seed,&doc).unwrap();
            let b=Graph::from_document(seed,&compiled).unwrap();
            let mut roots:Vec<_>=a.roots.keys().cloned().collect();roots.sort();
            for mode in [Mode::Raw,Mode::Single,Mode::Block,Mode::Cell] {
                let mut x=a.scratch(-16,16,4,8).unwrap();
                let mut y=b.scratch(-16,16,4,8).unwrap();
                a.initialize_column_caches(&mut x);b.initialize_column_caches(&mut y);
                for i in 0..256 {
                    let p=[-16+(i*7)%32,-64+(i*13)%384,16+(i*11)%32];
                    for root in &roots {
                        assert_eq!(a.compute(a.roots[root],p,mode,&mut x).to_bits(),
                            b.compute(b.roots[root],p,mode,&mut y).to_bits(),
                            "{dimension} seed={seed} root={root} p={p:?}");
                    }
                }
            }
        }
    }
}
