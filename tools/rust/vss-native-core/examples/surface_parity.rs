//! End-to-end surface parity against a live capture.
//!
//! Loads the native document built by `BuildNativeDoc.java` and compares the
//! native `surface_points` output with the Java columns recorded by
//! `/vssclient prediction capture`. This is the check the static gate cannot
//! provide: it exercises the actual surface rules (`ac_simplex`,
//! `youkaishomecoming:noise`, `terrablender:merged`) rather than just their
//! codec names.
//!
//! Usage: cargo run --release --example surface_parity -- <capture-dir>

use serde_json::Value;
use std::fs;
use std::path::Path;
use vss_native_core::backend::World;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let dir = std::env::args()
        .nth(1)
        .expect("usage: surface_parity <capture-dir>");
    let dir = Path::new(&dir);

    let doc: Value = serde_json::from_str(&fs::read_to_string(dir.join("native-doc.json"))?)?;
    let manifest: Value =
        serde_json::from_str(&fs::read_to_string(dir.join("manifest.json"))?)?;
    let seed: i64 = manifest["seed"]
        .as_str()
        .unwrap_or_default()
        .parse()
        .unwrap_or(0);

    // The capture writes blocks in registry order, so the document's key order
    // is the block id order the native palette uses.
    // The document ships `input_states`, so state ids resolve through the same
    // palette the world built.
    let palette = vss_native_core::blocks::Palette::from_json(&doc["block_definitions"])?;
    let mut palette = palette;
    if let Some(states) = doc.get("input_states") {
        for state in states.as_array().ok_or("invalid input state table")? {
            palette.intern(state)?;
        }
    }
    let name_of = |id: i32| -> String { palette.state(id as u32).name.clone() };

    let world = World::new(seed, 0, doc.clone())?;

    // The columns recorded by capture come from the Java sampler *after*
    // surface-rule resolution, while `surface_points` reports the raw volume,
    // so they are not comparable. `base-columns.jsonl` holds the vanilla
    // density column (`getBaseColumn`), which is the same stage the native
    // column describes: the first non-air run gives the floor and its state the
    // top block.
    let base_text = fs::read_to_string(dir.join("base-columns.jsonl"))?;
    let mut points = Vec::new();
    let mut expected = Vec::new();
    for line in base_text.lines().filter(|l| !l.trim().is_empty()) {
        let row: Value = serde_json::from_str(line)?;
        let runs = row["runs"].as_array().ok_or("missing runs")?;
        let mut floor = i32::MIN;
        let mut top = String::from("unknown");
        // The floor is one past the *highest* solid run: caves leave lower
        // solid runs behind, so scanning from the bottom is wrong.
        for run in runs {
            let end = run[1].as_i64().unwrap_or(0) as i32;
            let state = run[2].as_str().unwrap_or("");
            // Water and lava are fluids: the native floor skips them and
            // reports the solid surface instead.
            if state.contains("minecraft:air")
                || state.contains("minecraft:water")
                || state.contains("minecraft:lava")
            {
                continue;
            }
            if end > floor {
                floor = end;
                top = state
                    .split("Name:\"")
                    .nth(1)
                    .and_then(|rest| rest.split('"').next())
                    .unwrap_or("unknown")
                    .to_owned();
            }
        }
        points.push((
            row["x"].as_i64().unwrap_or(0) as i32,
            row["z"].as_i64().unwrap_or(0) as i32,
        ));
        expected.push((floor, top));
    }

    // `values[4]` is the state the surface rules produced, because the volume
    // fed to `surface_points` already has them applied (they change material,
    // not height). Its reference is therefore `columns.jsonl`'s topBlock, not
    // the raw column.
    let columns_text = fs::read_to_string(dir.join("columns.jsonl"))?;
    let mut surface_top = std::collections::HashMap::new();
    for line in columns_text.lines().filter(|l| !l.trim().is_empty()) {
        let row: Value = serde_json::from_str(line)?;
        surface_top.insert(
            (
                row["x"].as_i64().unwrap_or(0) as i32,
                row["z"].as_i64().unwrap_or(0) as i32,
            ),
            row["topBlock"].as_str().unwrap_or("").to_owned(),
        );
    }

    let mut heights_ok = 0usize;
    let mut tops_ok = 0usize;
    let mut samples = 0usize;
    let mut height_diffs = Vec::new();
    let mut top_diffs = Vec::new();

    for (batch, span) in points.chunks(64).zip(expected.chunks(64)) {
        for ((column, (want_height, _)), point) in
            world.surface_points(batch)?.iter().zip(span).zip(batch)
        {
            samples += 1;
            let got_height = column.values[0];
            if got_height == *want_height {
                heights_ok += 1;
            } else if height_diffs.len() < 15 {
                height_diffs.push((got_height, *want_height));
            }
            if let Some(want_top) = surface_top.get(point) {
                let got_top = name_of(column.values[4]);
                if got_top == *want_top {
                    tops_ok += 1;
                } else if top_diffs.len() < 15 {
                    top_diffs.push((got_top, want_top.clone()));
                }
            }
        }
    }

    println!("columns compared : {samples}");
    println!("surfaceY matches : {heights_ok}");
    println!("topBlock matches : {tops_ok}");
    if samples > 0 {
        println!(
            "match rate       : surfaceY {:.1}%  topBlock {:.1}%",
            heights_ok as f64 / samples as f64 * 100.0,
            tops_ok as f64 / samples as f64 * 100.0
        );
    }
    if !height_diffs.is_empty() {
        println!("--- surfaceY mismatches (rust, java) ---");
        for (got, want) in &height_diffs {
            println!("  {got} vs {want}");
        }
    }
    if !top_diffs.is_empty() {
        println!("--- topBlock mismatches (rust, java) ---");
        for (got, want) in &top_diffs {
            println!("  {got} vs {want}");
        }
    }
    Ok(())
}
