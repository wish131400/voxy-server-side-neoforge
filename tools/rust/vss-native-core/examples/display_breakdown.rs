//! Offline stage timings; profiling disabled in packaged libraries.
use serde_json::Value;
use std::{fs,path::Path,time::Instant};
use vss_native_core::backend::World;
fn main() {
 let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
 let read=|s:&str|serde_json::from_str::<Value>(&fs::read_to_string(root.join(s)).unwrap()).unwrap();
 let mut d=read("overworld.json");
 for(k,f)in[("block_definitions","blocks.json"),("biomes","biomes.json"),("grass_colormap","grass.json"),("foliage_colormap","foliage.json")]{d[k]=read(f);}
 for step in [1,4,64] {
  let world=World::new(0,0,d.clone()).unwrap();let mut checksum=0u64;
  vss_native_core::prof::reset();let start=Instant::now();
  for n in 0..64 {let points:Vec<_>=(0..64).map(|i|(100000+n*4096+i%8*step,100000+i/8*step)).collect();
   for row in world.display_points(&points).unwrap(){for v in row.values {checksum=checksum.wrapping_mul(31).wrapping_add(v as u64);}}
  }
  println!("DISPLAY_BREAKDOWN step={step} points=4096 wallMs={:.3} checksum={checksum} stagesMs={:?} queries={:?}",start.elapsed().as_secs_f64()*1000.,vss_native_core::prof::display_snapshot().into_iter().map(|(n,v)|(n,v as f64/1e6)).collect::<Vec<_>>(),world.display_query_stats());
 }
}
