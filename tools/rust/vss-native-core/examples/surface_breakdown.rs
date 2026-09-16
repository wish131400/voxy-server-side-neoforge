//! Section breakdown of the cold `World::surface_point` path.
//!
//! Run with `--features profiling`. Without the feature every scope compiles to
//! a no-op, so the same binary doubles as a check that the instrumentation
//! costs nothing: it prints no sections and reports the plain wall time.
//!
//! Sections with a leading two-space indent are nested inside the section
//! above them and are therefore excluded from the `attributed` total.
use serde_json::Value;
use std::{fs, path::Path, time::Instant};
use vss_native_core::backend::World;

/// Every point sits in its own chunk and its own 4x4 lattice cell, so no point
/// is served by the per-chunk or per-cell caches that the first point of a
/// group would otherwise warm.
const SIDE: i32 = 16;
const STRIDE: i32 = 64;

fn main() {
    let dense = std::env::args().any(|arg| arg == "--dense");
    let side = if dense { 66 } else { SIDE };
    let stride = if dense { 1 } else { STRIDE };
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
    // Warm allocators, noise caches and palette clones on points that are never
    // measured, so the first measured column is not paying process start-up.
    for i in 0..32 {
        std::hint::black_box(
            world
                .surface_point(-100_000 + (i % 8) * STRIDE, -100_000 + (i / 8) * STRIDE)
                .unwrap(),
        );
    }
    let points: Vec<(i32, i32)> = (0..side * side)
        .map(|i| {
            (
                100_000 + (i % side) * stride,
                100_000 + (i / side) * stride,
            )
        })
        .collect();
    vss_native_core::prof::reset();
    let start = Instant::now();
    let mut checksum = 0xcbf29ce484222325u64;
    for batch in points.chunks(if dense { 64 } else { 1 }) {
        for record in world.surface_points(batch).unwrap() {
            for v in record.values {
                checksum = (checksum ^ u64::from(v as u32)).wrapping_mul(0x100000001b3);
            }
        }
    }
    let wall = start.elapsed().as_secs_f64() * 1000.0;
    let wall_ns = wall * 1e6;
    let columns = vss_native_core::prof::columns().max(1);
    println!(
        "points={} cold columns={} rebuilds={} centre steps/column={:.1} block calls/column={:.1}",
        points.len(),
        columns,
        vss_native_core::prof::truncated_rebuilds(),
        vss_native_core::prof::centre_steps() as f64 / columns as f64,
        vss_native_core::prof::block_calls() as f64 / columns as f64,
    );
    println!(
        "wall={:.2} ms ({:.4} ms/column)",
        wall,
        wall / columns as f64
    );
    let evals = vss_native_core::prof::evals();
    println!(
        "graph.compute calls/column: raw={:.0} single={:.0} block={:.0} cell={:.0} slice={:.0}",
        evals[0] as f64 / columns as f64,
        evals[1] as f64 / columns as f64,
        evals[2] as f64 / columns as f64,
        evals[3] as f64 / columns as f64,
        evals[4] as f64 / columns as f64,
    );
    println!(
        "aquifer/column: fluid={:.0} (status cache misses) preliminary={:.0}",
        vss_native_core::prof::fluid_calls() as f64 / columns as f64,
        vss_native_core::prof::preliminary_calls() as f64 / columns as f64,
    );
    let (top_scans, top_reuses) = vss_native_core::prof::surface_top_counts();
    println!(
        "surface_top/column: scans={:.1} reuses={:.1}",
        top_scans as f64 / columns as f64,
        top_reuses as f64 / columns as f64,
    );
    // The share of the tree that carries no per-call state, and therefore the
    // ceiling for handing that layer to a GPU the way `c2me-ocl` does.
    let noise = vss_native_core::prof::noise_evals();
    let total: u64 = evals.iter().sum();
    let noise_ns = vss_native_core::prof::noise_time();
    println!(
        "stateless noise leaves/column: {:.0} ({:.1}% of calls, {:.1}% of wall)",
        noise as f64 / columns as f64,
        noise as f64 / total.max(1) as f64 * 100.0,
        noise_ns as f64 / wall_ns * 100.0,
    );
    println!("checksum: {checksum:016x}");
    if vss_native_core::prof::columns() == 0 {
        println!("\nno sections recorded: rebuild with `--features profiling`");
        return;
    }
    println!();
    println!(
        "{:<20} {:>11} {:>13} {:>9}",
        "section", "total ms", "us/column", "of wall"
    );
    let mut attributed = 0u64;
    for (name, ns) in vss_native_core::prof::snapshot() {
        if !name.starts_with("  ") {
            attributed += ns;
        }
        println!(
            "{:<20} {:>11.2} {:>13.2} {:>8.1}%",
            name,
            ns as f64 / 1e6,
            ns as f64 / 1e3 / columns as f64,
            ns as f64 / wall_ns * 100.0
        );
    }
    println!();
    let row = |label: &str, ns: f64| {
        println!(
            "{:<20} {:>11.2} {:>13.2} {:>8.1}%",
            label,
            ns / 1e6,
            ns / 1e3 / columns as f64,
            ns / wall_ns * 100.0
        );
    };
    row("attributed", attributed as f64);
    row("unattributed", wall_ns - attributed as f64);
    let calls = vss_native_core::prof::block_calls().max(1);
    println!();
    println!(
        "Job::block internals, {:.1} calls/column (four Instant::now per call):",
        calls as f64 / columns as f64
    );
    println!("{:<20} {:>11} {:>12}", "section", "total ms", "ns/call");
    for (name, ns) in vss_native_core::prof::block_snapshot() {
        println!(
            "{:<20} {:>11.2} {:>12.1}",
            name,
            ns as f64 / 1e6,
            ns as f64 / calls as f64
        );
    }
}
