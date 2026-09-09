use serde_json::Value;
use std::{fs, path::Path};
use vss_native_core::{
    blocks::{Palette, Volume},
    random::Random,
    vegetation::{Feature, PlacementContext},
};
fn read(name: &str) -> Value {
    serde_json::from_str(
        &fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("tests/fixtures/worldgen")
                .join(name),
        )
        .unwrap(),
    )
    .unwrap()
}
#[test]
fn vanilla_vegetation_features() {
    let definitions = read("blocks.json");
    let doc = read("features.json");
    let states = read("vegetation-states.json");
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let mut count = 0;
    let mut failures = vec![];
    for line in fs::read_to_string(root.join("vegetation.jsonl"))
        .unwrap()
        .lines()
    {
        let row: Value = serde_json::from_str(line).unwrap();
        let name = row["feature"].as_str().unwrap();
        let seed = row["seed"].as_str().unwrap().parse().unwrap();
        let mut palette = Palette::from_json(&definitions).unwrap();
        let ids: Vec<_> = states
            .as_array()
            .unwrap()
            .iter()
            .map(|s| palette.intern(s).unwrap())
            .collect();
        let grass = palette.named("minecraft:grass_block").unwrap();
        let dirt = palette.named("minecraft:dirt").unwrap();
        let feature = Feature::compile(&row["feature"], &doc, &mut palette)
            .unwrap_or_else(|e| panic!("{name}: {e}"));
        let mut world = Volume::new([-32, -64, -32], [64, 384, 64], palette).unwrap();
        for x in -32..32 {
            for z in -32..32 {
                for y in -64..64 {
                    world.set([x, y, z], if y == 63 { grass } else { dirt });
                }
            }
        }
        for v in row["input"].as_array().unwrap() {
            world.set(
                std::array::from_fn(|i| v[i].as_i64().unwrap() as i32),
                ids[v[3].as_u64().unwrap() as usize],
            );
        }
        let mut expected = world.blocks.clone();
        for v in row["output"].as_array().unwrap() {
            let x = v[0].as_i64().unwrap() as i32;
            let y = v[1].as_i64().unwrap() as i32;
            let z = v[2].as_i64().unwrap() as i32;
            expected[((x + 32) as usize * 64 + (z + 32) as usize) * 384 + (y + 64) as usize] =
                ids[v[3].as_u64().unwrap() as usize];
        }
        let mut random = Random::new(seed, 3);
        let mut allow = |_: &str, _| true;
        let mut context = PlacementContext::new(name, &mut allow);
        let result = feature
            .place_transaction(&mut world, &mut random, [0, 64, 0], &mut context)
            .unwrap();
        let after = random.next_long();
        if (result != row["placed"].as_bool().unwrap()
            || after.to_string() != row["after"].as_str().unwrap())
            && failures.len() < 20
        {
            failures.push(format!(
                "{name} seed={seed} pattern={} result {result}; random {after} != {}",
                row["pattern"], row["after"]
            ));
        }
        for (i, (&a, &b)) in world.blocks.iter().zip(&expected).enumerate() {
            count += 1;
            if a != b && failures.len() < 20 {
                failures.push(format!(
                    "{name} seed={seed} pattern={} pos=({}, {}, {}) {:?} != {:?}",
                    row["pattern"],
                    i / (64 * 384) as usize as usize,
                    i % 384,
                    i / 384 % 64,
                    world.palette.state(a),
                    world.palette.state(b)
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    println!("vanilla vegetation voxel comparisons={count}");
}
