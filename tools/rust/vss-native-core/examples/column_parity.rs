//! Full-column parity against a live capture.
//!
//! `surface_parity` compares two scalars per column; this compares the entire
//! block column down to bedrock against the `runs` the capture recorded from
//! `getBaseColumn`. That makes density, aquifer and surface-rule mistakes
//! visible at every y rather than only at the surface.
//!
//! Usage: cargo run --release --example column_parity -- <capture-dir> <chunkX> <chunkZ>

use serde_json::Value;
use std::fs;
use std::path::Path;
use vss_native_core::backend::World;
use vss_native_core::blocks::Palette;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let dir = args.next().expect("usage: column_parity <capture-dir> <chunkX> <chunkZ>");
    let chunk_x: i32 = args.next().unwrap_or_else(|| "0".into()).parse()?;
    let chunk_z: i32 = args.next().unwrap_or_else(|| "0".into()).parse()?;
    let dir = Path::new(&dir);

    let doc: Value = serde_json::from_str(&fs::read_to_string(dir.join("native-doc.json"))?)?;
    let manifest: Value = serde_json::from_str(&fs::read_to_string(dir.join("manifest.json"))?)?;
    let seed: i64 = manifest["seed"]
        .as_str()
        .unwrap_or_default()
        .parse()
        .unwrap_or(0);

    // Same construction the world uses, so state ids line up.
    let mut palette = Palette::from_json(&doc["block_definitions"])?;
    for state in doc["input_states"].as_array().ok_or("missing input_states")? {
        palette.intern(state)?;
    }

    let world = World::new(seed, 0, doc)?;
    let mut volume = world.surface_region(chunk_x, chunk_z, 1)?;
    println!("region origin {:?} size {:?}", volume.origin, volume.size);

    let text = fs::read_to_string(dir.join("base-columns.jsonl"))?;
    let mut columns = 0usize;
    let mut blocks = 0usize;
    let mut mismatches = Vec::new();

    for line in text.lines().filter(|l| !l.trim().is_empty()) {
        let row: Value = serde_json::from_str(line)?;
        let x = row["x"].as_i64().unwrap_or(0) as i32;
        let z = row["z"].as_i64().unwrap_or(0) as i32;
        // Only columns this region actually covers.
        if x < volume.origin[0]
            || x >= volume.origin[0] + volume.size[0] as i32
            || z < volume.origin[2]
            || z >= volume.origin[2] + volume.size[2] as i32
        {
            continue;
        }
        columns += 1;
        for run in row["runs"].as_array().ok_or("missing runs")? {
            let start = run[0].as_i64().unwrap_or(0) as i32;
            let end = run[1].as_i64().unwrap_or(0) as i32;
            let state = run[2].as_str().unwrap_or("");
            let want = state
                .split("Name:\"")
                .nth(1)
                .and_then(|rest| rest.split('"').next())
                .unwrap_or("unknown");
            for y in start..end {
                // `getBaseColumn` stops at the raw density result. It carries
                // neither the deepslate transition (which reaches a few blocks
                // above y=0) nor the surface rule material on top, so only the
                // middle stone band is comparable.
                if !(16..=88).contains(&y) {
                    continue;
                }
                blocks += 1;
                let got = palette.state(volume.get([x, y, z])).name.clone();
                if got != want && mismatches.len() < 20 {
                    mismatches.push((x, y, z, got, want.to_owned()));
                } else if got != want {
                    mismatches.push((0, 0, 0, String::new(), String::new()));
                }
            }
        }
    }

    let failed = mismatches.iter().filter(|m| m.3 != m.4 || m.3.is_empty()).count();
    let shown: Vec<_> = mismatches.iter().filter(|m| !m.3.is_empty()).collect();
    println!("columns compared : {columns}");
    println!("blocks compared  : {blocks}");
    println!("block mismatches : {failed}");
    if blocks > 0 {
        println!(
            "match rate       : {:.2}%",
            (blocks - failed) as f64 / blocks as f64 * 100.0
        );
    }
    if !shown.is_empty() {
        println!("--- first mismatches (x,y,z rust java) ---");
        for (x, y, z, got, want) in shown.iter().take(20) {
            println!("  ({x},{y},{z}) {got} vs {want}");
        }
    }
    Ok(())
}
