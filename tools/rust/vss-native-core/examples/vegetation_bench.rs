//! Fixed-input placement benchmark. Terrain sampling and GPU work are excluded.
use serde_json::{json, Value};
use std::{fs, path::Path, time::Instant};
use vss_native_core::{blocks::{Palette, Volume}, random::Random, vegetation::{Placed, PlacementContext}};

fn read(name: &str) -> Value {
    serde_json::from_str(&fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/worldgen").join(name)).unwrap()).unwrap()
}
fn main() {
    let doc = read("features.json");
    for (label, names) in [
        ("bamboo", vec!["minecraft:bamboo"]),
        ("grass_flowers", vec!["minecraft:patch_grass_plain", "minecraft:flower_default"]),
        ("jungle", vec!["minecraft:trees_jungle", "minecraft:patch_grass_jungle", "minecraft:bamboo"]),
    ] {
        let mut palette = Palette::from_json(&read("blocks.json")).unwrap();
        let features: Vec<_> = names.iter().map(|name| Placed::compile(&json!(name), &doc, &mut palette).unwrap()).collect();
        let grass = palette.named("minecraft:grass_block").unwrap() as i32;
        let dirt = palette.named("minecraft:dirt").unwrap() as i32;
        let mut times = vec![];
        let mut expected = None;
        for round in 0..9 {
            let mut elapsed = 0;
            let mut hash = 0u64;
            let mut edits = 0;
            for chunk in 0..64 {
                let columns = vec![[64, -64, 0, 0, grass, dirt, dirt, 0, 0, 0]; 80*80];
                let mut volume = Volume::proxy([-32,-64,-32],80,80,384,palette.clone(),columns).unwrap();
                volume.decoration_entropy = Some(Random::new(chunk,0));
                let start = Instant::now();
                for (index, (feature, name)) in features.iter().zip(&names).enumerate() {
                    let mut rng = Random::new(0,3);
                    let seed = rng.decoration_seed(48271,chunk as i32*16,0);
                    rng.feature_seed(seed,index as i32,9);
                    let mut allowed = |_: &str, _| true;
                    feature.place_transaction(&mut volume,&mut rng,[0,64,0],
                        &mut PlacementContext::new(name,&mut allowed)).unwrap();
                }
                elapsed += start.elapsed().as_nanos();
                for (&i,&id) in &volume.published {
                    hash = hash.wrapping_mul(1099511628211).wrapping_add(i as u64 ^ ((id as u64)<<32));
                }
                edits += volume.published.len();
            }
            assert_eq!(*expected.get_or_insert((hash,edits)),(hash,edits));
            if round>=2 { times.push(elapsed as f64/1e6); }
        }
        times.sort_by(f64::total_cmp);
        println!("{label}: chunks=64 medianMs={:.3} minMs={:.3} maxMs={:.3} checksum={:?}",times[3],times[0],times[6],expected.unwrap());
    }
}
