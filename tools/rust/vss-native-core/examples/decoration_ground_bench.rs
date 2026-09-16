//! Includes first vegetation ground queries and actual terrain computation.
use serde_json::Value;
use std::{fs,path::Path,sync::Arc,time::Instant};
use vss_native_core::backend::World;
fn main() {
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read=|name:&str| serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap();
    let mut d=read("overworld.json");
    for(k,f) in [("block_definitions","blocks.json"),("biomes","biomes.json"),
        ("grass_colormap","grass.json"),("foliage_colormap","foliage.json")] {d[k]=read(f);}
    for dense in [false,true] {for warm in [false,true] {
        let mut times=[vec![],vec![],vec![]];let mut counts=[[0;2];3];let mut hash=[0;3];
        for round in 0..7 {for mode in if round%2==0 {[0,1,2]}else{[2,1,0]} {
            let world=Arc::new(World::new(42,0,d.clone()).unwrap());
            // Same display grid already drawn, including the neighbouring page.
            if warm {for ox in [48,56,64] {for oz in [-208,-200] {
                let pts:Vec<_>=(0..64).map(|i|(ox+i%8,oz+i/8)).collect();
                world.display_points(&pts).unwrap();
            }}}
            let before=world.work_counts();let start=Instant::now();
            let mut v=if mode==0 {world.lazy_surface_proxy(3,-13)}else{world.decoration_proxy(3,-13,mode==2)}.unwrap();
            let mut checksum=0i64;
            let points:Vec<_>=if dense {(48..64).flat_map(|x|(-208..-192).map(move |z|(x,z))).collect()}
                else {vec![(48,-208),(51,-206),(60,-198),(63,-193)]};
            for (x,z) in points.into_iter().chain([(64,-200)]) {
                let y=v.height(x,z,false);checksum=checksum.wrapping_mul(31)+y as i64;
                checksum=checksum.wrapping_mul(31)+v.get([x,y-1,z]) as i64;
            }
            let elapsed=start.elapsed().as_secs_f64()*1000.;
            if round>=2 {times[mode].push(elapsed);}
            let after=world.work_counts();counts[mode]=[after[0]-before[0],after[1]-before[1]];hash[mode]=checksum;
        }}
        assert_eq!(hash[0],hash[1],"exact paging must preserve queried output");
        for t in &mut times {t.sort_by(f64::total_cmp);}
        println!("dense={dense} displayWarm={warm} medianMs(old,exactPage,displayPage)={:?} work(chunk,point)={counts:?} checksums={hash:?}",times.map(|t|t[2]));
    }}
}
