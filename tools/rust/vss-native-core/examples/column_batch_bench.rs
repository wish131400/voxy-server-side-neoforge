//! Does the sparse column path reuse work across columns, and how much?
//!
//! Three effects are measured here:
//!
//! * `surface_points` shares one `Job` (and one `Scratch`) per chunk, so
//!   columns packed into one 4x4 cell reuse density corners while scattered
//!   columns cannot;
//! * neighbouring columns share most of their steepness neighbours, so the
//!   `Job::surface_top` cache should turn repeated scans into lookups;
//! * `preview_points` takes a different, approximate route through the density
//!   graph (`Mode::Raw`, 16-block y steps, no aquifers/ores/structures).
//!
//! Bases are spaced so no trial can hit another trial's cache. Run with
//! `--features profiling` to populate the count columns.
use serde_json::Value;
use std::{fs, path::Path, time::Instant};
use vss_native_core::backend::World;

/// Batches per layout; each uses a fresh base so nothing is served by a cache.
const TRIALS: i32 = 32;
/// Bases walk from here in steps of `BASE_STRIDE`, beyond any batch's reach.
const BASE_ORIGIN: i32 = -500_000;
const BASE_STRIDE: i32 = 8_192;
/// Coordinate band reserved per layout; wider than `TRIALS * BASE_STRIDE`.
const BAND: i32 = 1_000_000;

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

    println!(
        "{:<22} {:>11} {:>11} {:>9} {:>12} {:>12} {:>10}",
        "layout", "ms/batch", "ms/column", "cold/col", "single/col", "cell/col", "top reuse"
    );
    for (layout, (label, grid, step, preview)) in [
        // The client's shape: one 8x8 batch walking a single chunk.
        ("surface, 8x8 step 1", 8, 1, false),
        // Same sixteen columns, packed into one 4x4 cell or scattered.
        ("surface, 4x4 same cell", 4, 1, false),
        ("surface, 4x4 64 apart", 4, 64, false),
        ("preview, 8x8 step 1", 8, 1, true),
        ("preview, 4x4 64 apart", 4, 64, true),
    ]
    .into_iter()
    .enumerate()
    {
        // Each layout gets its own band of coordinates. Sharing a base would let
        // an earlier layout's cached columns answer a later layout's misses.
        let origin = BASE_ORIGIN + layout as i32 * BAND;
        let count = grid * grid;
        let mut total = 0.0;
        let mut cold_total = 0u64;
        let mut evals = [0u64; 5];
        let mut checksum = 0xcbf2_9ce4_8422_2325u64;
        for trial in 0..TRIALS {
            // +8 keeps an 8x8 batch inside one chunk for the step-1 layouts.
            let bx = ((origin + trial * BASE_STRIDE) & !15) + 8;
            let bz = origin + 8;
            let points: Vec<(i32, i32)> = (0..count)
                .map(|i| (bx + (i % grid) * step, bz + (i / grid) * step))
                .collect();
            vss_native_core::prof::reset();
            let start = Instant::now();
            let records = std::hint::black_box(if preview {
                world.preview_points(&points).unwrap()
            } else {
                world.surface_points(&points).unwrap()
            });
            total += start.elapsed().as_secs_f64() * 1000.0;
            cold_total += vss_native_core::prof::columns();
            let observed = vss_native_core::prof::evals();
            for (slot, value) in evals.iter_mut().zip(observed) {
                *slot += value;
            }
            for record in &records {
                for v in record.values {
                    checksum = (checksum ^ u64::from(v as u32)).wrapping_mul(0x100000001b3);
                }
            }
        }
        let columns = f64::from(TRIALS * count);
        let (scans, reuses) = vss_native_core::prof::surface_top_counts();
        let reuse = if scans + reuses > 0 {
            format!("{:.0}%", reuses as f64 / (scans + reuses) as f64 * 100.0)
        } else {
            "-".to_string()
        };
        println!(
            "{:<22} {:>11.3} {:>11.4} {:>9.1} {:>12.0} {:>12.0} {:>10}",
            label,
            total / f64::from(TRIALS),
            total / columns,
            cold_total as f64 / columns,
            evals[1] as f64 / columns,
            evals[3] as f64 / columns,
            reuse,
        );
        std::hint::black_box(checksum);
    }
    println!();
    println!("`single` is Mode::Single evaluation, `cell` is Mode::Cell; `top reuse`");
    println!("is the share of `Job::surface_top` calls answered from its cache.");
    println!("Without `--features profiling` the count columns read 0 and `-`.");
}
