use serde_json::Value;
use std::{
    fs,
    path::{Path, PathBuf},
};
use vss_native_core::{
    biome::{Biome, ClimateColors},
    blocks::{Palette, Volume},
    surface::Surface,
    terrain::Terrain,
};
fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen")
}
fn read(name: &str) -> Value {
    serde_json::from_str(&fs::read_to_string(root().join(name)).unwrap()).unwrap()
}
fn colors() -> ClimateColors {
    ClimateColors::new(
        serde_json::from_value(read("grass.json")).unwrap(),
        serde_json::from_value(read("foliage.json")).unwrap(),
    )
    .unwrap()
}
#[test]
fn vanilla_biome_colors_and_temperature() {
    let data = read("biomes.json");
    let colors = colors();
    let mut count = 0;
    for line in fs::read_to_string(root().join("colors.jsonl"))
        .unwrap()
        .lines()
    {
        let v: Value = serde_json::from_str(line).unwrap();
        let b = Biome::from_json(&data[v["biome"].as_str().unwrap()]).unwrap();
        let p = std::array::from_fn(|i| v["pos"][i].as_i64().unwrap() as i32);
        assert_eq!(
            colors.grass(&b, p[0] as f64, p[2] as f64),
            v["grass"].as_u64().unwrap() as u32,
            "{v}"
        );
        assert_eq!(
            colors.foliage(&b),
            v["foliage"].as_u64().unwrap() as u32,
            "{v}"
        );
        assert_eq!(b.water, v["water"].as_u64().unwrap() as u32);
        assert_eq!(
            colors.temperature(&b, p) < 0.15,
            v["cold"].as_bool().unwrap(),
            "{v}"
        );
        assert_eq!(
            colors.temperature(&b, p) > 0.1,
            v["melt"].as_bool().unwrap(),
            "{v}"
        );
        count += 5;
    }
    println!("vanilla biome color/temperature comparisons={count}");
}
#[test]
fn vanilla_surface_pass() {
    let definitions = read("blocks.json");
    let states = read("surface-states.json");
    let biomes = read("biomes.json");
    let colors = colors();
    let mut count = 0;
    let mut failures = vec![];
    for line in fs::read_to_string(root().join("surface.jsonl"))
        .unwrap()
        .lines()
    {
        let row: Value = serde_json::from_str(line).unwrap();
        let settings = row["settings"].as_str().unwrap();
        let doc = read(&format!("{settings}.json"));
        let seed = row["seed"].as_str().unwrap().parse().unwrap();
        let mut terrain = Terrain::from_document(seed, &doc).unwrap();
        let mut palette = Palette::from_json(&definitions).unwrap();
        let ids: Vec<_> = states
            .as_array()
            .unwrap()
            .iter()
            .map(|v| palette.intern(v).unwrap())
            .collect();
        let surface = Surface::new(&mut terrain, &doc, &mut palette).unwrap();
        let origin = std::array::from_fn(|i| row["origin"][i].as_i64().unwrap() as i32);
        let mut world = Volume::new(
            origin,
            [16, row["height"].as_u64().unwrap() as usize, 16],
            palette,
        )
        .unwrap();
        let decode = |runs: &Value| {
            runs.as_array()
                .unwrap()
                .iter()
                .flat_map(|r| {
                    std::iter::repeat_n(
                        ids[r[1].as_u64().unwrap() as usize],
                        r[0].as_u64().unwrap() as usize,
                    )
                })
                .collect::<Vec<_>>()
        };
        world.blocks = decode(&row["input"]);
        let expected = decode(&row["output"]);
        assert_eq!(world.blocks.len(), expected.len());
        let name = row["biome"].as_str().unwrap();
        let b = Biome::from_json(&biomes[name]).unwrap();
        let mut job = terrain.job(origin[0], origin[2], false).unwrap();
        surface
            .apply_chunk(&mut job, &mut world, origin[0], origin[2], &colors, |_| {
                Ok((name, &b))
            })
            .unwrap();
        for (i, (&a, &b)) in world.blocks.iter().zip(&expected).enumerate() {
            count += 1;
            if a != b && failures.len() < 20 {
                let y = i % world.size[1];
                let z = i / world.size[1] % 16;
                let x = i / world.size[1] / 16;
                failures.push(format!(
                    "{name} seed={seed} {x},{},{z}: {:?} != {:?}",
                    origin[1] + y as i32,
                    world.palette.state(a),
                    world.palette.state(b)
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    println!("vanilla surface block comparisons={count}");
}
