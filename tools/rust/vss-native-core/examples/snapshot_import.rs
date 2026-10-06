//! Strict offline import of a complete runtime RustWorldgenDocument. No fixture
//! palette, colormap, biome source or default is substituted. Run with:
//! cargo run --locked --release --example snapshot_import -- document.json seed zoom_seed
use serde_json::{json, Value};
use std::{fs, time::Instant};
use vss_native_core::{backend::World, biome::Biome};

fn run() -> Result<Value, String> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 4 {
        return Err("usage: snapshot_import document.json seed zoom_seed".into());
    }
    let seed: i64 = args[2].parse().map_err(|e| format!("seed: {e}"))?;
    let zoom_seed: i64 = args[3].parse().map_err(|e| format!("zoom_seed: {e}"))?;
    let start = Instant::now();
    let input = fs::read(&args[1]).map_err(|e| e.to_string())?;
    let original: Value = serde_json::from_slice(&input).map_err(|e| e.to_string())?;
    let mut normalized = original.clone();
    let biomes = normalized["biomes"].as_object_mut().ok_or("missing biomes")?;
    let biome_count = biomes.len();
    let mut normalized_colors = 0;
    for (name, biome) in biomes {
        // Confirm every supplied registry entry, including biomes outside the
        // current source, passes strict production color parsing.
        let parsed = Biome::from_json(biome).map_err(|e| format!("{name}: {e}"))?;
        for (key, rgb) in [("grass_color", parsed.grass),
            ("foliage_color", parsed.foliage), ("water_color", Some(parsed.water))] {
            if let Some(rgb) = rgb {
                let color = biome["effects"][key].as_i64().ok_or("color is not a Java int")?;
                let color = i32::try_from(color).map_err(|_| "color is outside Java int range")?;
                // Java tint consumers shift then mask each channel. Preserve
                // their arithmetic-shift behavior on signed ARGB inputs.
                let expected = (((color >> 16) & 255) << 16
                    | ((color >> 8) & 255) << 8 | (color & 255)) as u32;
                if rgb != expected { return Err(format!("{name}/{key}: RGB mismatch")); }
                if color as i64 != rgb as i64 { normalized_colors += 1; }
                biome["effects"][key] = json!(rgb);
            }
        }
    }
    let world = World::new(seed, zoom_seed, original).map_err(|e| format!("original import: {e}"))?;
    let import_ms = start.elapsed().as_secs_f64() * 1000.;
    let oracle = World::new(seed, zoom_seed, normalized).map_err(|e| format!("RGB oracle import: {e}"))?;
    if world.palette.states.as_ref() != oracle.palette.states.as_ref() {
        return Err("palette changed after RGB normalization".into());
    }
    let points = [(-1513,751), (-16,-16), (0,0), (15,15), (1024,-512), (4096,2048)];
    let actual = world.surface_points(&points)?;
    let expected = oracle.surface_points(&points)?;
    for (i, (&point, (actual, expected))) in points.iter().zip(actual.iter().zip(&expected)).enumerate() {
        if actual.values != expected.values {
            return Err(format!("surface RGB normalization mismatch at {point:?}: {:?} != {:?}", actual.values, expected.values));
        }
        let position = [point.0, actual.values[0], point.1];
        if world.colors_at(position)? != oracle.colors_at(position)? {
            return Err(format!("tint RGB normalization mismatch at sample {i}: {position:?}"));
        }
    }
    Ok(json!({"status":"imported", "document":args[1], "bytes":input.len(),
        "seed":seed,"zoom_seed":zoom_seed,"biomes":biome_count,
        "normalizedArgbFields":normalized_colors,"states":world.palette.states.len(),
        "importMs":import_ms,"totalMs":start.elapsed().as_secs_f64()*1000.,
        "surfaceParityPoints":points.len(),"tintParityPoints":points.len(),
        "minY":world.terrain.min_y,"height":world.terrain.height}))
}

fn main() {
    match run() {
        Ok(value) => println!("{value}"),
        Err(error) => { eprintln!("snapshot import failed: {error}"); std::process::exit(1); }
    }
}
