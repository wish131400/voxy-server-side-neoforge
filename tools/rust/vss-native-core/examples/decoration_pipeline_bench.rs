//! Actual native plant placement including lazy ground generation and commit.
use serde_json::{json,Value};
use std::{fs,path::Path,sync::Arc,time::Instant};
use vss_native_core::{backend::World,random::Random};
fn main() {
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read=|name:&str| serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap();
    let mut d=read("overworld.json");
    for(k,f) in [("block_definitions","blocks.json"),("biomes","biomes.json"),("input_states","vegetation-states.json"),
        ("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] {d[k]=read(f);}
    for(k,v) in read("features.json").as_object().unwrap() {d[k]=v.clone();}
    d["possible_biomes"]=json!(["minecraft:bamboo_jungle"]);
    d["biome_source"]=json!({"type":"minecraft:fixed","biome":"minecraft:bamboo_jungle"});
    let mut times=[vec![],vec![],vec![]];let mut outputs=[0;3];let mut checksums=[0u64;3];
    for round in 0..7 {for mode in if round%2==0 {[0,1,2]}else{[2,1,0]} {
        let w=Arc::new(World::new(48271,0,d.clone()).unwrap());
        let schedule=w.schedule.as_ref().unwrap();
        let features:Vec<_>=schedule.steps.iter().enumerate().flat_map(|(step,names)|names.iter().enumerate()
            .filter(|(_,n)| ["minecraft:bamboo","minecraft:patch_grass_jungle"].contains(&n.as_str()))
            .map(move |(i,n)|(step,i,n.clone()))).collect();
        assert_eq!(features.len(),2);
        let start=Instant::now();let mut edits=0;let mut hash=0u64;
        for cx in 3..11 {
            let mut v=if mode==0 {w.lazy_surface_proxy(cx,-13)}else{w.decoration_proxy(cx,-13,mode==2)}.unwrap();
            v.decoration_entropy=Some(Random::new(cx as i64,0));
            for(step,i,name) in &features {w.placed(&mut v,name,cx,-13,*i as i32,*step as i32).unwrap();}
            edits+=v.published.len();
            for (&i,&id) in &v.published {hash=hash.wrapping_mul(1099511628211).wrapping_add(i as u64 ^ ((id as u64)<<32));}
        }
        if round>=2 {times[mode].push(start.elapsed().as_secs_f64()*1000.);}
        outputs[mode]=edits;checksums[mode]=hash;
    }}
    assert_eq!(checksums[0],checksums[1]);assert!(outputs.iter().all(|&n|n>0));
    for t in &mut times {t.sort_by(f64::total_cmp);}
    println!("chunks=8 bamboo+grass medianMs(old,exactPage,displayPage)={:?} outputEdits={outputs:?} hashes={checksums:?}",times.map(|t|t[2]));
}
