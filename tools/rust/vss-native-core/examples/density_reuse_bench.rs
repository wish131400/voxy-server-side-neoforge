//! Compare identical records across builds. Run binaries serially, alternate
//! their order, and use non-profiling builds for timing. Profiling is counts only.
use serde_json::{json,Value};
use std::{fs,path::Path,time::Instant};
use vss_native_core::backend::World;
fn main() {
    let round:i32=std::env::args().nth(1).unwrap_or("0".into()).parse().unwrap();
    let batches:i32=std::env::args().nth(2).unwrap_or("64".into()).parse().unwrap();
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read=|name:&str|serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap();
    for dimension in ["overworld","nether","end","amplified"] {
        let mut doc=read(&format!("{dimension}.json"));
        for (key,file) in [("block_definitions","blocks.json"),("biomes","biomes.json"),
            ("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] {doc[key]=read(file);}
        for (name,display,step,interleaved) in [("exact-page",false,1,false),
            ("exact-interleaved",false,1,true),("display-dense",true,1,false),
            ("display-lod",true,4,false),("display-sparse",true,16,false)] {
            let world=World::new(if round<0 {0}else{-917+round as i64},0,doc.clone()).unwrap();
            let mut output=Vec::new();
            vss_native_core::prof::reset();
            let start=Instant::now();
            for batch in 0..batches {
                let bx=-500000+(batch%8)*512; let bz=500000+(batch/8)*512;
                let points:Vec<_>=(0..64).map(|i| {
                    let j=if interleaved {(i*17)%64}else{i};
                    (bx+j%8*step,bz+j/8*step)
                }).collect();
                let rows=if display {world.display_points(&points)}else{world.surface_points(&points)}.unwrap();
                output.extend(rows.into_iter().flat_map(|row|row.values));
            }
            let ms=start.elapsed().as_secs_f64()*1000.;
            let hash=output.iter().fold(0xcbf29ce484222325u64,|h,&n|(h^(n as u32 as u64)).wrapping_mul(0x100000001b3));
            println!("{}",json!({"dimension":dimension,"layout":name,"round":round,"ms":ms,
                "hash":format!("{hash:016x}"),"values":output.len(),"batches":batches,"noise":vss_native_core::prof::noise_evals(),
                "top":vss_native_core::prof::surface_top_counts(),"evals":vss_native_core::prof::evals()}));
        }
    }
}
