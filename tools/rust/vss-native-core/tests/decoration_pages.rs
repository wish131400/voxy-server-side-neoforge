use serde_json::{json, Value};
use std::{fs,path::Path,sync::{Arc,atomic::{AtomicUsize,Ordering}}};
use vss_native_core::{backend::World,blocks::{Palette,Volume}};
fn document() -> Value {
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read=|name:&str| serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap();
    let mut d=read("overworld.json");
    for(k,f) in [("block_definitions","blocks.json"),("biomes","biomes.json"),
        ("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] {d[k]=read(f);}
    d
}
#[test]
fn small_pages_load_sixteen_columns_once_and_preserve_axis_order() {
    let mut palette=Palette::from_json(&document()["block_definitions"]).unwrap();
    let stone=palette.named("minecraft:stone").unwrap();
    let count=Arc::new(AtomicUsize::new(0));let calls=count.clone();
    let mut v=Volume::paged_proxy([-32,-64,-48],80,80,384,palette,4,Box::new(move |x,z| {
        calls.fetch_add(16,Ordering::Relaxed);
        Ok(Arc::new((0..16).map(|i| {
            let y=60+(x*4+i/4+z*4+i%4) as i32;
            [y,y,0,0,stone as i32,stone as i32,stone as i32,0,0,0]
        }).collect()))
    })).unwrap();
    assert_eq!(count.load(Ordering::Relaxed),0);
    for x in -32..-28 {for z in -48..-44 {
        assert_eq!(v.height(x,z,false),60+(x+32)+(z+48));
    }}
    assert_eq!(count.load(Ordering::Relaxed),16);
    assert_eq!(v.height(-28,-48,false),64);
    assert_eq!(count.load(Ordering::Relaxed),32);
    v.begin().unwrap();v.set([-28,130,-48],stone);v.finish(false).unwrap();
    assert_eq!(v.height(-28,-48,false),64);
}
#[test]
fn exact_pages_match_old_proxy_and_visual_queries_do_not_poison_exact_cache() {
    let d=document();let world=Arc::new(World::new(42,0,d.clone()).unwrap());
    let oracle=Arc::new(World::new(42,0,d).unwrap());
    let mut old=oracle.lazy_surface_proxy(-1,-1).unwrap();
    let mut exact=world.decoration_proxy(-1,-1,false).unwrap();
    let mut visual=world.decoration_proxy(-1,-1,true).unwrap();
    for (x,z) in [(-17,-17),(-16,-16),(-1,-1),(0,0),(3,-7)] {
        for kind in 0..4 {assert_eq!(old.heightmap(x,z,kind),exact.heightmap(x,z,kind));}
        for y in [0,60,64,100,200] {assert_eq!(old.get([x,y,z]),exact.get([x,y,z]));}
        visual.height(x,z,false);
    }
    let points=[(-17,-17),(-16,-16),(3,-7)];
    assert_eq!(world.surface_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>(),
        oracle.surface_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>());
    world.cancel();assert!(world.decoration_proxy(-1,-1,true).is_err());
}
#[test]
fn visual_pages_keep_exact_structure_and_custom_liquid_fallbacks() {
    for kind in 0..3 {
        let mut d=document();
        match kind {
            0=>d["structure_terrain"]=json!([{"chunk_x":-1,"chunk_z":-1,"pieces":[],"junctions":[]}]),
            1=>d["settings"]["noise_router"]["fluid_level_floodedness"]=json!(100.),
            _=>d["settings"]["noise_router"]["final_density"]=json!({"type":"minecraft:cache_2d",
                "argument":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}}),
        }
        let w=Arc::new(World::new(42,0,d).unwrap());
        let mut visual=w.decoration_proxy(-1,-1,true).unwrap();
        let mut exact=w.decoration_proxy(-1,-1,false).unwrap();
        for x in -16..-12 {for z in -16..-12 {
            for k in 0..4 {assert_eq!(visual.heightmap(x,z,k),exact.heightmap(x,z,k));}
            for y in [30,60,63,64,100,200] {assert_eq!(visual.get([x,y,z]),exact.get([x,y,z]));}
        }}
    }
}
