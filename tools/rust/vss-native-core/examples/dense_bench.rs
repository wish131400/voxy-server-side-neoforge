//! Does the cross-column `surface_top` cache pay for dense sampling?
//!
//! `surface_point` builds a fresh `Job` per column, and the four neighbour
//! positions inside one column are all distinct, so the per-job top map never
//! sees a repeat. A world-owned map survives across calls, so adjacent columns
//! can reuse each other's scans - but only when adjacent columns are actually
//! requested.
//!
//! This walks a dense block of columns one at a time, which is the shape where
//! that sharing can pay off. Compare against `--features no-shared-tops`.
use serde_json::Value;
use std::{fs, path::Path, time::Instant};
use vss_native_core::backend::World;

/// One chunk's worth of columns, one block apart, queried point by point.
/// `BASE` matches `surface_breakdown` so terrain complexity is not a confound.
const SIDE: i32 = 16;
const BASE: i32 = 100_000;

fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |s: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(s)).unwrap()).unwrap()
    };
    let mut doc = read("overworld.json");
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        doc[key] = read(file);
    }
    let world = World::new(0, 0, doc).unwrap();

    // Warm elsewhere so the measured chunk starts cold in the caches that
    // matter, but with the process already started.
    for i in 0..32 {
        std::hint::black_box(world.surface_point(-65536 + i, -65536).unwrap());
    }

    let mut checksum = 0xcbf2_9ce4_8422_2325u64;
    let start = Instant::now();
    for dz in 0..SIDE {
        for dx in 0..SIDE {
            let record = world.surface_point(BASE + dx, BASE + dz).unwrap();
            for v in record.values {
                checksum = (checksum ^ u64::from(v as u32)).wrapping_mul(0x100000001b3);
            }
        }
    }
    let elapsed = start.elapsed().as_secs_f64() * 1000.0;

    let columns = f64::from(SIDE * SIDE);
    let (scans, reuses) = vss_native_core::prof::surface_top_counts();
    println!("dense per-point columns = {columns:.0} (one chunk, stride 1)");
    println!("wall = {elapsed:.2} ms   {:.2} us/col", elapsed * 1000.0 / columns);
    println!("checksum = {checksum:016x}");
    if scans + reuses > 0 {
        println!(
            "surface_top: scans = {scans:.0}  reuses = {reuses:.0}  ({:.1}% reuse)",
            reuses as f64 / (scans + reuses) as f64 * 100.0
        );
    }
}
