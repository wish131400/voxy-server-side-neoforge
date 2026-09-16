//! Same coordinates, two call shapes: per-point vs batched.
//!
//! `surface_points` groups its input by chunk and builds one `Job` per group;
//! the per-point path builds an equivalent `Job` per column. This measures
//! whether the batch wrapper pays for itself while holding the coordinates
//! fixed.
//!
//! Two `World`s are built from the same seed so both call shapes see identical
//! cold caches and identical terrain. Comparing shapes across one `World` would
//! let whichever ran second hit the other's cache.
use serde_json::Value;
use std::{fs, path::Path, time::Instant};
use vss_native_core::backend::World;

/// Fixed grid for comparing both call shapes: origin (0,0),
/// 32-block spacing, and the same axis count.
/// With `spacing = 32` no two points share a chunk or a 4x4 lattice cell.
const SIDE: i32 = 128;
const STRIDE: i32 = 32;
/// `surface_points` rejects more than 64 points.
const BATCH: usize = 64;
const BASE: i32 = 0;
/// Disjoint warm-up region, never measured.
const WARM: i32 = -900_000;

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
    let per_point_world = World::new(0, 0, doc.clone()).unwrap();
    let batched_world = World::new(0, 0, doc).unwrap();

    let points: Vec<(i32, i32)> = (0..(SIDE * SIDE))
        .map(|i| (BASE + (i % SIDE) * STRIDE, BASE + (i / SIDE) * STRIDE))
        .collect();

    // Warm both instances on a region that is never measured, so neither call
    // shape is paying process start-up.
    for i in 0..32 {
        let x = WARM + (i % 8) * STRIDE;
        let z = WARM + (i / 8) * STRIDE;
        std::hint::black_box(per_point_world.surface_point(x, z).unwrap());
        std::hint::black_box(batched_world.surface_point(x, z).unwrap());
    }

    let mut a_checksum = 0xcbf2_9ce4_8422_2325u64;
    let start = Instant::now();
    for &(x, z) in &points {
        let record = per_point_world.surface_point(x, z).unwrap();
        for v in record.values {
            a_checksum = (a_checksum ^ u64::from(v as u32)).wrapping_mul(0x100000001b3);
        }
    }
    let a = start.elapsed().as_secs_f64() * 1000.0;

    let mut b_checksum = 0xcbf2_9ce4_8422_2325u64;
    let start = Instant::now();
    for group in points.chunks(BATCH) {
        for record in batched_world.surface_points(group).unwrap() {
            for v in record.values {
                b_checksum = (b_checksum ^ u64::from(v as u32)).wrapping_mul(0x100000001b3);
            }
        }
    }
    let b = start.elapsed().as_secs_f64() * 1000.0;

    let columns = f64::from(SIDE * SIDE);
    println!("columns = {columns:.0} per shape, stride = {STRIDE}, batch = {BATCH}");
    println!(
        "A per-point surface_point : {a:8.2} ms  {:9.2} us/col",
        a * 1000.0 / columns
    );
    println!(
        "B batched surface_points  : {b:8.2} ms  {:9.2} us/col",
        b * 1000.0 / columns
    );
    println!("A/B = {:.3}x  (>1 means batching is faster)", a / b);
    println!();
    println!("checksum A = {a_checksum:016x}");
    println!("checksum B = {b_checksum:016x}");
    println!(
        "identical  = {}",
        if a_checksum == b_checksum { "yes" } else { "NO" }
    );
}
