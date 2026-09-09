//! Cold final-grid sampling; does not measure rendering or frame time.
use std::{fs, time::Instant};
use vss_native_core::backend::World;

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let doc = serde_json::from_str(&fs::read_to_string(&args[1]).unwrap()).unwrap();
    let spacing: i32 = args[2].parse().unwrap();
    let dense = args[3] == "dense";
    let w = World::new(0, 0, doc).unwrap();
    let start = Instant::now();
    let mut hash = 0xcbf29ce484222325u64;
    let mut longest = 0f64;
    for bz in (0..66).step_by(8) {
        for bx in (0..66).step_by(8) {
            let batch = Instant::now();
            let mut positions = vec![];
            for z in bz..(bz + 8).min(66) {
                for x in bx..(bx + 8).min(66) {
                    let (xx, zz) = ((x - 65) * spacing, (z - 65) * spacing);
                    positions.push((xx, zz));
                }
            }
            let columns = if dense {
                positions
                    .iter()
                    .map(|&(x, z)| {
                        w.surface_columns(x >> 4, z >> 4).unwrap()
                            [((x & 15) * 16 + (z & 15)) as usize]
                    })
                    .collect()
            } else {
                w.surface_points(&positions).unwrap()
            };
            for c in columns {
                for n in c.values {
                    hash = (hash ^ n as u32 as u64).wrapping_mul(0x100000001b3);
                }
            }
            longest = longest.max(batch.elapsed().as_secs_f64() * 1000.);
        }
    }
    println!("spacing={spacing}, mode={}, total_ms={:.2}, longest_batch_ms={longest:.2}, checksum={hash:016x}",
        args[3], start.elapsed().as_secs_f64() * 1000.);
}
