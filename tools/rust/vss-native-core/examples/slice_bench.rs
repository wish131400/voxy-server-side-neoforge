//! Isolates the density height search: the per-point march used by
//! `Job::preliminary` versus a batched y-profile followed by a scan.
//!
//! This deliberately measures only the density evaluation - no job setup, no
//! volume, no biome sampling - so the two strategies are compared on the
//! algorithm alone.  Both walk the same lattice (min_y .. max_y step
//! cell_height) and use the same threshold, and the run asserts they agree on
//! every column before reporting a speedup.
use serde_json::Value;
use std::{fs, path::Path, time::Instant};
use vss_native_core::backend::World;
use vss_native_core::density::Mode;

const THRESHOLD: f64 = 0.390625;

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
    let seed = std::env::args()
        .nth(1)
        .map(|s| s.parse::<i64>().unwrap())
        .unwrap_or(42);
    let world = World::new(seed, 0, doc).unwrap();
    let t = &world.terrain;
    let cell = t.cell_height;
    let min_y = t.min_y;
    let max_y = t.min_y + t.height;
    let steps = ((max_y - min_y) / cell) + 1;
    println!(
        "cell_height={cell}  min_y={min_y}  height={}  steps/column={steps}",
        t.height
    );

    // Every column lands in its own 4x4 lattice cell, so nothing can be served
    // from the per-job `levels` cache.
    let mut columns = Vec::new();
    for i in 0..256 {
        columns.push(((i % 16) * 4 + 1, (i / 16) * 4 + 1));
    }

    let initial = {
        let job = t.job(0, 0, false).unwrap();
        job.initial_density_id()
    };

    // ---- A: the current per-point march ----
    let mut sink_a = 0i64;
    let start = Instant::now();
    for &(x, z) in &columns {
        let mut scratch = t
            .graph
            .scratch(x & !15, z & !15, t.cell_width, t.cell_height)
            .unwrap();
        let mut y = max_y;
        while y >= min_y {
            if t.graph.compute(initial, [x, y, z], Mode::Single, &mut scratch) > THRESHOLD {
                sink_a += y as i64;
                break;
            }
            y -= cell;
        }
    }
    let a = start.elapsed();

    // ---- B: batched y-profile, then scan the values ----
    let mut sink_b = 0i64;
    let start = Instant::now();
    for &(x, z) in &columns {
        let mut scratch = t
            .graph
            .scratch(x & !15, z & !15, t.cell_width, t.cell_height)
            .unwrap();
        let points: Vec<[i32; 3]> = (min_y..=max_y)
            .step_by(cell as usize)
            .map(|y| [x, y, z])
            .collect();
        let values = t.graph.fill_array(initial, &points, Mode::Slice, &mut scratch);
        for i in (0..values.len()).rev() {
            if values[i] > THRESHOLD {
                sink_b += points[i][1] as i64;
                break;
            }
        }
    }
    let b = start.elapsed();

    let n = columns.len() as f64;
    println!(
        "A  per-point march : {:>9.3} ms   {:.4} ms/column   sink={sink_a}",
        a.as_secs_f64() * 1000.,
        a.as_secs_f64() * 1000. / n
    );
    println!(
        "B  batched profile : {:>9.3} ms   {:.4} ms/column   sink={sink_b}",
        b.as_secs_f64() * 1000.,
        b.as_secs_f64() * 1000. / n
    );
    assert_eq!(sink_a, sink_b, "both strategies must find the same heights");
    println!("A/B = {:.3}x", a.as_secs_f64() / b.as_secs_f64());
}
