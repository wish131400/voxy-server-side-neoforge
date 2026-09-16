use serde_json::Value;
use std::{fs, path::Path, sync::{Arc, atomic::{AtomicUsize, Ordering}}, time::Instant};
use vss_native_core::{backend::World, blocks::{Palette, Volume}};

fn document() -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |name: &str| serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap();
    let mut doc = read("overworld.json");
    for (key, file) in [("block_definitions","blocks.json"),("biomes","biomes.json"),
        ("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] { doc[key] = read(file); }
    doc
}

#[test]
fn no_columns_until_read_and_each_chunk_loaded_once_including_negative_origin() {
    let mut palette = Palette::from_json(&document()["block_definitions"]).unwrap();
    let stone = palette.named("minecraft:stone").unwrap();
    let count = Arc::new(AtomicUsize::new(0));
    let calls = count.clone();
    let mut v = Volume::lazy_proxy([-32,-64,-48],32,32,384,palette,Box::new(move |x,z| {
        calls.fetch_add(1,Ordering::Relaxed);
        let mut c = [0;10]; c[0]=64+(x*16+z) as i32; c[1]=c[0]; c[4]=stone as i32;c[5]=c[4];c[6]=c[4];
        Ok(Arc::new(vec![c;256]))
    })).unwrap();
    assert_eq!(count.load(Ordering::Relaxed),0);
    for y in -64..320 { v.get([-32,y,-48]); }
    for x in -32..-16 { v.height(x,-48,false); }
    assert_eq!(count.load(Ordering::Relaxed),1);
    assert_eq!(v.height(-16,-48,false),80);
    assert_eq!(count.load(Ordering::Relaxed),2);
    v.begin().unwrap(); v.set([-16,100,-48],stone); v.finish(false).unwrap();
    assert_eq!(v.height(-16,-48,false),80);
    v.materialize().unwrap();
    assert_eq!(count.load(Ordering::Relaxed),4);
    assert_eq!(v.get([-16,79,-48]),stone);
    assert!(!v.is_proxy());
}

#[test]
fn missing_data_rolls_back_without_exporting_partial_air() {
    let mut palette = Palette::from_json(&document()["block_definitions"]).unwrap();
    let stone = palette.named("minecraft:stone").unwrap();
    let count=Arc::new(AtomicUsize::new(0));let calls=count.clone();
    let mut v=Volume::lazy_proxy([0,-64,0],16,16,384,palette,Box::new(move |_,_| {
        calls.fetch_add(1,Ordering::Relaxed);Err("test missing terrain".into())
    })).unwrap();
    v.begin().unwrap();v.set([0,64,0],stone);v.get([1,64,0]);
    assert!(v.finish(true).is_err());assert!(v.published.is_empty());
    assert!(v.materialize().is_err());assert!(v.is_proxy());assert!(v.blocks.is_empty());
    assert_eq!(count.load(Ordering::Relaxed),1);
    // Failed heights cannot survive a transaction boundary as cached air.
    for _ in 0..2 {
        v.begin().unwrap(); v.heightmap(1,1,0);
        assert!(v.finish(true).is_err());
    }
    let mut short=Volume::lazy_proxy([0,0,0],16,16,32,v.palette.clone(),Box::new(|_,_|Ok(Arc::new(vec![])))).unwrap();
    assert!(short.materialize().is_err());
}

#[test]
fn lazy_and_eager_worlds_match_reads_heights_edits_and_full_export() {
    let world=Arc::new(World::new(-917,0,document()).unwrap());
    let mut eager=world.surface_proxy(-1,2).unwrap();
    let mut lazy=world.lazy_surface_proxy(-1,2).unwrap();
    for x in -48..32 { for z in 0..80 {
        for y in [0,60,63,64,80,127,200] {assert_eq!(eager.get([x,y,z]),lazy.get([x,y,z]));}
        for kind in 0..4 {assert_eq!(eager.heightmap(x,z,kind),lazy.heightmap(x,z,kind));}
    }}
    let stone=eager.palette.named("minecraft:stone").unwrap();
    for v in [&mut eager,&mut lazy] {
        v.begin().unwrap();v.set([-15,100,33],stone);v.finish(true).unwrap();
        v.begin().unwrap();v.set([-15,101,33],stone);v.finish(false).unwrap();
        v.materialize().unwrap();
    }
    assert_eq!(eager.blocks,lazy.blocks);assert_eq!(eager.published,lazy.published);
}

#[test]
fn compare_cold_eager_and_lazy_queries_without_hiding_cost_in_first_read() {
    let doc=document();let mut times=[Vec::new(),Vec::new()];
    for round in 0..5 {
        let mut results=Vec::new();
        for lazy in if round%2==0 {[false,true]}else{[true,false]} {
            let world=Arc::new(World::new(42,0,doc.clone()).unwrap());
            let start=Instant::now();
            let mut v=if lazy {world.lazy_surface_proxy(3,-13)}else{world.surface_proxy(3,-13)}.unwrap();
            let creation=start.elapsed();
            let mut checksum=0i64;
            // Include actual first reads: every centre column and one neighbour.
            for x in 48..64 {for z in -208..-192 {
                let y=v.height(x,z,false);checksum+=y as i64+v.get([x,y-1,z]) as i64;
            }}
            checksum+=v.height(64,-200,false) as i64;
            let total=start.elapsed();times[usize::from(lazy)].push(total.as_secs_f64()*1000.0);
            results.push(checksum);
            println!("LAZY_PROXY round={round} lazy={lazy} creationMs={:.3} firstQueriesMs={:.3}",creation.as_secs_f64()*1000.0,total.as_secs_f64()*1000.0);
        }
        assert_eq!(results[0],results[1]);
    }
    for t in &mut times {t.sort_by(f64::total_cmp);}
    println!("LAZY_PROXY median eagerMs={:.3} lazyIncludingQueriesMs={:.3} speedup={:.2}",times[0][2],times[1][2],times[0][2]/times[1][2]);
}
