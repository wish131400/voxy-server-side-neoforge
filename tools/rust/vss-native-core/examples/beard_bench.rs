//! How much does the structure beardifier add to a cold surface column?
//!
//! `World` parses `structure_terrain` into a per-chunk `Beard`. `Beard::compute`
//! walks every piece and junction on every `Job::block` call, and a cold column
//! makes roughly 263 of those, so the cost scales with the structure count in
//! that chunk.
//!
//! Two shapes are measured. An enclosing box keeps every sampled column inside
//! the piece, which exercises the per-piece arithmetic. A small box is the
//! realistic case: a chunk's structure context covers the whole chunk while the
//! structure occupies a few blocks of it, so most columns are far from every
//! piece and only pay the loop itself.
//!
//! Read-only probe: it builds worlds and times them, and changes nothing.
use serde_json::{json, Value};
use std::{fs, path::Path, time::Instant};
use vss_native_core::backend::World;

/// Cold columns per measurement; all inside chunk (0,0) so they see the beard.
const COLUMNS: i32 = 64;
/// Piece counts to sweep. `Beard::parse` rejects more than 4096.
const SIZES: [usize; 6] = [0, 1, 4, 16, 64, 256];

fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |s: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(s)).unwrap()).unwrap()
    };
    let mut base = read("overworld.json");
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        base[key] = read(file);
    }

    for (scenario, box_) in [
        ("enclosing box", json!([-16, 0, -16, 32, 128, 32])),
        ("small box", json!([0, 60, 0, 2, 64, 2])),
    ] {
        for adjustment in ["bury", "encapsulate"] {
            println!("scenario={scenario} adjustment={adjustment}");
            println!(
                "{:>8} {:>12} {:>10} {:>14}",
                "pieces", "ms/column", "vs none", "ns/piece/col"
            );
            let mut baseline = 0.0;
            for pieces in SIZES {
                let mut doc = base.clone();
                doc["structure_terrain"] = contexts(pieces, adjustment, &box_);
                let world = World::new(0, 0, doc).unwrap();
                let start = Instant::now();
                for i in 0..COLUMNS {
                    std::hint::black_box(world.surface_point(i % 8, i / 8).unwrap());
                }
                let ms = start.elapsed().as_secs_f64() * 1000.0 / f64::from(COLUMNS);
                if pieces == 0 {
                    baseline = ms;
                }
                let per_piece = if pieces == 0 {
                    0.0
                } else {
                    (ms - baseline) * 1e6 / pieces as f64
                };
                println!(
                    "{:>8} {:>12.3} {:>9.2}x {:>14.0}",
                    pieces,
                    ms,
                    ms / baseline,
                    per_piece
                );
            }
            println!();
        }
    }
}

/// One chunk's structure context holding `pieces` copies of the same box.
fn contexts(pieces: usize, adjustment: &str, box_: &Value) -> Value {
    let piece = json!({
        "box": box_,
        "adjustment": adjustment,
        "ground_delta": 0,
    });
    json!([{
        "chunk_x": 0,
        "chunk_z": 0,
        "pieces": vec![piece; pieces],
        "junctions": [],
    }])
}
