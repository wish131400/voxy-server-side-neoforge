//! Identical cold batches for before/after throughput and complete record comparison.
use std::{fs, time::Instant};
use vss_native_core::backend::World;

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let doc: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&args[1]).unwrap()).unwrap();
    let seed = args[2].parse().unwrap();
    let zoom = args[3].parse().unwrap();
    for (x, z) in [(-40, 56), (8191, -4097), (-32768, 16384)] {
        for stride in [512, 64, 8, 4, 1] {
            let world = World::new(seed, zoom, doc.clone()).unwrap();
            let points: Vec<_> = (0..64)
                .map(|i| (x + i % 8 * stride, z + i / 8 * stride))
                .collect();
            let start = Instant::now();
            let records: Vec<_> = if args.get(4).is_some_and(|a| a == "preview") {
                world.preview_points(&points)
            } else { world.surface_points(&points) }
                .unwrap()
                .iter()
                .map(|v| v.values)
                .collect();
            println!(
                "{}",
                serde_json::json!({"x":x,"z":z,"stride":stride,
                "ms":start.elapsed().as_secs_f64()*1000.,"records":records})
            );
        }
    }
}
