//! How far off is the approximate preview path from the exact column?
//!
//! `PreviewDetailBands` gives tiles inside the fine radius (`cellAxis = 64`) the
//! exact path and everything outside it the approximate one. This probe sizes
//! what that trade actually costs, per distance band, so the radius can be
//! argued from numbers rather than from "it is far away".
//!
//! Read-only: both records come from public APIs and neither changes generation.
use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::backend::World;

const COLUMNS: i32 = 256;

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
        "{:<12} {:>6} {:>6} {:>6} {:>6} {:>8} {:>9} {:>9} {:>9}",
        "distance", "min", "p50", "p99", "max", "|d|>2", "|d|>4", "fluid!=", "top!="
    );
    for (label, from, to) in [
        ("0-512", 0, 512),
        ("768-1536", 768, 1536),
        ("1536-3072", 1536, 3072),
        ("3072-6144", 3072, 6144),
        ("6144-12288", 6144, 12288),
    ] {
        let mut deltas = Vec::with_capacity(COLUMNS as usize);
        let mut over2 = 0usize;
        let mut over4 = 0usize;
        let mut fluid_diff = 0usize;
        let mut top_diff = 0usize;
        for i in 0..COLUMNS {
            // Walk outward along x; a small z keeps the radius equal to x while
            // avoiding repeated coordinates.
            let x = from + i * (to - from) / COLUMNS;
            let z = i % 16;
            let exact = world.surface_point(x, z).unwrap();
            let approx = world.preview_points(&[(x, z)]).unwrap()[0];
            let delta = approx.values[0] - exact.values[0];
            deltas.push(delta);
            if delta.abs() > 2 {
                over2 += 1;
            }
            if delta.abs() > 4 {
                over4 += 1;
            }
            if approx.values[2] != exact.values[2] {
                fluid_diff += 1;
            }
            if approx.values[4] != exact.values[4] {
                top_diff += 1;
            }
        }
        deltas.sort_unstable();
        let pct = |n: usize| f64::from(n as i32) / f64::from(COLUMNS) * 100.0;
        println!(
            "{:<12} {:>6} {:>6} {:>6} {:>6} {:>7.0}% {:>8.0}% {:>8.0}% {:>8.0}%",
            label,
            deltas[0],
            percentile(&deltas, 0.50),
            percentile(&deltas, 0.99),
            deltas[deltas.len() - 1],
            pct(over2),
            pct(over4),
            pct(fluid_diff),
            pct(top_diff),
        );
    }
    println!();
    println!("`d` is preview floor minus exact floor, in blocks. `top!=` compares");
    println!("the surface material id; `fluid!=` compares the fluid kind.");
}

fn percentile(sorted: &[i32], p: f64) -> i32 {
    let i = ((sorted.len() - 1) as f64 * p).round() as usize;
    sorted[i.min(sorted.len() - 1)]
}
