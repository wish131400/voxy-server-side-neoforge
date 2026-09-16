//! Point-by-point parity check against a `vss-rust-reference-2` capture.
//!
//! The capture is produced in-game by `/vssclient prediction capture`, which
//! runs the Java sampler over a 4x4 grid at four spacings and records each
//! column's biome. This example replays the same generator document through
//! the native biome source and compares.
//!
//! Only `Graph` and `BiomeSource` are exercised: the capture does not ship
//! `input_states`, so a full `World` cannot be built, but biome selection does
//! not need it.
//!
//! Usage:
//!   cargo run --release --example blueprint_parity -- <capture-dir> [columns.jsonl]

use serde_json::Value;
use std::collections::BTreeMap;
use std::fs;
use vss_native_core::climate::BiomeSource;
use vss_native_core::density::Graph;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let dir = args.next().expect("usage: blueprint_parity <capture-dir> [columns.jsonl]");
    let columns = args.next().unwrap_or_else(|| format!("{dir}/columns.jsonl"));

    let generator: Value =
        serde_json::from_str(&fs::read_to_string(format!("{dir}/generator.json"))?)?;
    let registries: Value =
        serde_json::from_str(&fs::read_to_string(format!("{dir}/registries.json"))?)?;
    let manifest: Value =
        serde_json::from_str(&fs::read_to_string(format!("{dir}/manifest.json"))?)?;

    let seed: i64 = manifest["seed"]
        .as_str()
        .unwrap_or_default()
        .parse()
        .unwrap_or(0);

    // Assemble the document the native side expects: the generator supplies
    // settings/registry payloads, the registry dump supplies the definitions.
    let mut doc = generator.clone();
    for key in ["noises", "density_functions", "biomes"] {
        if let Some(value) = registries.get(key) {
            doc[key] = value.clone();
        }
    }
    // possible_biomes only drives feature ordering; an empty list is enough
    // for biome selection and keeps this check focused.
    doc["possible_biomes"] = Value::Array(Vec::new());

    let graph = Graph::from_document(seed, &doc)?;
    let source = BiomeSource::from_document(&doc, &graph, seed)?;

    println!(
        "seed={seed} dimension={} vss_blueprint={} slices={}",
        manifest["dimension"].as_str().unwrap_or("?"),
        doc.get("vss_blueprint").is_some(),
        doc["vss_blueprint"]["slices"]
            .as_array()
            .map(|s| s.len())
            .unwrap_or(0)
    );

    let text = fs::read_to_string(&columns)?;
    let mut total = 0usize;
    let mut matched = 0usize;
    let mut mismatches: Vec<(i64, i64, i64, String, String)> = Vec::new();
    let mut by_biome: BTreeMap<String, (usize, usize)> = BTreeMap::new();

    for line in text.lines().filter(|l| !l.trim().is_empty()) {
        let row: Value = serde_json::from_str(line)?;
        let x = row["x"].as_i64().unwrap_or(0);
        let z = row["z"].as_i64().unwrap_or(0);
        // The capture writes the sampled height at the row's top-level `y`;
        // `resolved.surfaceY` is the same value inside the column record.
        let y = row["y"]
            .as_i64()
            .or_else(|| row["resolved"]["surfaceY"].as_i64())
            .unwrap_or(0);
        let expected = row["biome"].as_str().unwrap_or("").to_owned();

        // `getNoiseBiome` takes quart coordinates, and the Java reference asks
        // at (x>>2, (surfaceY-1)>>2, z>>2).
        let quart = [(x >> 2) as i32, ((y - 1) >> 2) as i32, (z >> 2) as i32];
        let mut scratch = graph.scratch((x as i32) & !15, (z as i32) & !15, 4, 8)?;
        let actual = source.sample(&graph, quart, &mut scratch, &mut None).to_owned();

        total += 1;
        let entry = by_biome.entry(expected.clone()).or_insert((0, 0));
        entry.0 += 1;
        if actual == expected {
            matched += 1;
            entry.1 += 1;
        } else if mismatches.len() < 20 {
            mismatches.push((x, y, z, expected.clone(), actual.clone()));
        }
    }

    println!("columns compared : {total}");
    println!("biome matches    : {matched}");
    if total > 0 {
        println!("match rate       : {:.1}%", matched as f64 / total as f64 * 100.0);
    }
    if !mismatches.is_empty() {
        println!("--- mismatches (first 20) ---");
        for (x, y, z, expected, actual) in &mismatches {
            println!("  x={x} y={y} z={z}  java={expected}  rust={actual}");
        }
        println!("--- per expected biome ---");
        for (biome, (seen, ok)) in &by_biome {
            println!("  {biome}: {ok}/{seen}");
        }
    }
    Ok(())
}
