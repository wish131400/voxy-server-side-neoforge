//! Differential tests for exact column values and dependency-triggered neighbour reads.
use serde_json::{json,Value};
use std::{fs,path::Path};
use vss_native_core::backend::World;
fn fixture(dimension:&str)->Value {
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read=|name:&str|serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap();
    let mut doc=read(&format!("{dimension}.json"));
    for (key,file) in [("block_definitions","blocks.json"),("biomes","biomes.json"),("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] {doc[key]=read(file);}
    doc
}
fn pair(mut doc:Value,seed:i64)->(World,World) {
    let candidate=doc.clone();doc["vss_dry_air"]=json!(false);doc["vss_aquifer_nearby"]=json!(false);
    (World::new(seed,0,doc).unwrap(),World::new(seed,0,candidate).unwrap())
}
#[test]
fn dimensions_seeds_boundaries_and_revisits_match_all_ten_fields() {
    for dimension in ["overworld","nether","end","amplified"] {for seed in [42,-917] {
        let (a,b)=pair(fixture(dimension),seed);
        for batch in 0..12 {
            let (bx,bz)=if batch%3==0{(131072+batch*16,-131072-batch*16)}else{(-32+batch*16,32-batch*16)};
            let points:Vec<_>=(0..64).map(|i|{let j=if batch%2==0{i}else{63-i};(bx+j%8,bz+j/8)}).collect();
            for display in [false,true,false] {
                let x=if display{a.display_points(&points)}else{a.surface_points(&points)}.unwrap();
                let y=if display{b.display_points(&points)}else{b.surface_points(&points)}.unwrap();
                for (i,(x,y)) in x.iter().zip(y).enumerate(){assert_eq!(x.values,y.values,"{dimension} seed={seed} batch={batch} display={display} p={:?}",points[i]);}
            }
        }
    }}
}
#[test]
fn altered_rules_namespaces_cold_secondary_and_special_geometry_preserve_records() {
    let original=fixture("overworld");
    let block=|name:&str|json!({"type":"minecraft:block","result_state":{"Name":name}});
    let condition=|c:Value,r:Value|json!({"type":"minecraft:condition","if_true":c,"then_run":r});
    let mut rules=vec![block("minecraft:grass_block"),
        json!({"type":"minecraft:sequence","sequence":[condition(json!({"type":"minecraft:steep"}),block("minecraft:stone")),block("minecraft:grass_block")]}),
        json!({"type":"minecraft:sequence","sequence":[condition(json!({"type":"minecraft:temperature"}),block("minecraft:snow_block")),block("minecraft:grass_block")]}),
        json!({"type":"minecraft:sequence","sequence":[condition(json!({"type":"minecraft:stone_depth","offset":0,"add_surface_depth":true,"secondary_depth_range":12,"surface_type":"floor"}),block("minecraft:sand")),block("minecraft:stone")]}),
        json!({"type":"minecraft:sequence","sequence":[condition(json!({"type":"minecraft:above_preliminary_surface"}),block("minecraft:grass_block")),block("minecraft:dirt")]}),
        json!({"type":"minecraft:sequence","sequence":[condition(json!({"type":"minecraft:not","invert":{"type":"minecraft:biome","biome_is":["minecraft:plains"]}}),condition(json!({"type":"minecraft:steep"}),block("minecraft:snow_block"))),block("minecraft:grass_block")]}),
        json!({"type":"minecraft:condition","if_true":{"type":"minecraft:hole"},"then_run":block("minecraft:air")})];
    rules.push(original["settings"]["surface_rule"].clone());
    rules.push(json!({"type":"terrablender:merged","base":block("minecraft:grass_block"),
        "sources":{"minecraft":condition(json!({"type":"minecraft:steep"}),block("minecraft:snow_block")),
        "custom":condition(json!({"type":"minecraft:temperature"}),block("minecraft:snow_block"))}}));
    for rule in rules {for name in ["minecraft:plains","minecraft:frozen_peaks","minecraft:frozen_ocean","minecraft:eroded_badlands"] {
        let mut doc=original.clone();doc["settings"]["surface_rule"]=rule.clone();doc["biome_source"]=json!({"type":"minecraft:fixed","biome":name});
        let (a,b)=pair(doc,42);
        for base in [(0,0),(-17,15),(131072,-131072)] {
            let points:Vec<_>=(0..32).map(|i|(base.0+i%8,base.1+i/8)).collect();
            let x=a.surface_points(&points).unwrap();let y=b.surface_points(&points).unwrap();
            for (i,(x,y)) in x.iter().zip(y).enumerate(){assert_eq!(x.values,y.values,"biome={name} p={:?}",points[i]);}
        }
    }}
}
#[test]
fn custom_namespace_uses_its_own_dependencies() {
    let mut doc=fixture("overworld");
    let custom=doc["biomes"]["minecraft:plains"].clone();
    doc["biomes"]["custom:test"]=custom;
    doc["biome_source"]=json!({"type":"minecraft:fixed","biome":"custom:test"});
    doc["settings"]["surface_rule"]=json!({"type":"terrablender:merged",
        "base":{"type":"minecraft:block","result_state":{"Name":"minecraft:grass_block"}},
        "sources":{"custom":{"type":"minecraft:sequence","sequence":[
            {"type":"minecraft:condition","if_true":{"type":"minecraft:temperature"},"then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:snow_block"}}},
            {"type":"minecraft:condition","if_true":{"type":"minecraft:stone_depth","offset":0,"add_surface_depth":true,"secondary_depth_range":12,"surface_type":"floor"},"then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:sand"}}},
            {"type":"minecraft:block","result_state":{"Name":"minecraft:dirt"}}]},
            "minecraft":{"type":"minecraft:condition","if_true":{"type":"minecraft:steep"},"then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}}}}});
    let (a,b)=pair(doc,42);
    let points:Vec<_>=(0..64).map(|i|(-17+i%8,15+i/8)).collect();
    for (x,y) in a.surface_points(&points).unwrap().iter().zip(b.surface_points(&points).unwrap()) {assert_eq!(x.values,y.values);}
}
#[test]
fn exposed_stateful_nodes_retain_original_fallback() {
    for wrapper in ["minecraft:cache_2d","minecraft:flat_cache"] {
        let mut doc=fixture("overworld");
        doc["settings"]["noise_router"]["final_density"]=json!({"type":wrapper,"argument":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}});
        let (a,b)=pair(doc,42);
        for (x,z) in [(0,0),(-17,3),(15,15),(16,16),(131072,-131072),(0,0)] {assert_eq!(a.surface_point(x,z).unwrap().values,b.surface_point(x,z).unwrap().values);}
    }
}
