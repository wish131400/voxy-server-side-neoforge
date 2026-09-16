//! Offline display-path profile; use --features profiling and a frozen document.
use std::{fs, time::Instant};
use vss_native_core::{backend::World, prof};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let doc = serde_json::from_str(&fs::read_to_string(&args[1]).unwrap()).unwrap();
    let step: i32 = args.get(2).map_or(64, |s| s.parse().unwrap());
    let world = World::new(0, 0, doc).unwrap();
    for i in 0..32 { world.display_points(&[(-100000+i*64,-100000)]).unwrap(); }
    prof::reset();
    let start=Instant::now();
    let points: Vec<_>=(0..256).map(|i|(100000+(i%16)*step,100000+(i/16)*step)).collect();
    for batch in points.chunks(64) { std::hint::black_box(world.display_points(batch).unwrap()); }
    println!("elapsed_ms={:.3} evals={:?}",start.elapsed().as_secs_f64()*1000.,prof::evals());
    println!("blocks={} fluids={} preliminary={}",prof::block_calls(),prof::fluid_calls(),prof::preliminary_calls());
    for (name,nanos) in prof::snapshot() { println!("{name}: {:.3} ms", nanos as f64/1e6); }
    for (name,nanos) in prof::block_snapshot() { println!("block {name}: {:.3} ms", nanos as f64/1e6); }
}
