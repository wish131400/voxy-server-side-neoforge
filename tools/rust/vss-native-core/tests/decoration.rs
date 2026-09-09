use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::{backend::World, blocks::Volume, decoration::Schedule};
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
fn rows(name: &str) -> Vec<Value> {
    fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/worldgen")
            .join(name),
    )
    .unwrap()
    .lines()
    .map(|s| serde_json::from_str(s).unwrap())
    .collect()
}
#[test]
fn vanilla_feature_order_and_cycles() {
    let doc = json!({"biomes":read("biomes.json")});
    for row in rows("feature-order.jsonl") {
        let possible: Vec<String> = serde_json::from_value(row["possible_biomes"].clone()).unwrap();
        let actual = Schedule::build(&doc, &possible).unwrap();
        assert_eq!(json!(actual.steps), row["steps"]);
    }
    let cyclic = json!({"biomes":{"a":{"features":[["x","y"]]},"b":{"features":[["y","x"]]}}});
    assert!(Schedule::build(&cyclic, &["a".into(), "b".into()])
        .err()
        .unwrap()
        .contains("cycle"));
}
fn document() -> Value {
    let mut doc = read("overworld.json");
    for (k, v) in read("features.json").as_object().unwrap() {
        doc[k] = v.clone();
    }
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
        ("input_states", "vegetation-states.json"),
    ] {
        doc[key] = read(file);
    }
    doc["possible_biomes"] = rows("feature-order.jsonl")[0]["possible_biomes"].clone();
    doc
}
#[test]
fn vanilla_placed_vegetation() {
    let doc = document();
    let states = read("vegetation-states.json");
    let mut cases = 0;
    for row in rows("placed-vegetation.jsonl") {
        let mut doc = doc.clone();
        doc["biome_source"] = json!({"type":"minecraft:fixed","biome":row["biome"]});
        let seed = row["seed"].as_str().unwrap().parse().unwrap();
        let backend = World::new(seed, 0, doc).unwrap();
        let mut palette = backend.palette.clone();
        let ids: Vec<_> = states
            .as_array()
            .unwrap()
            .iter()
            .map(|s| palette.intern(s).unwrap())
            .collect();
        let grass = palette.named("minecraft:grass_block").unwrap();
        let dirt = palette.named("minecraft:dirt").unwrap();
        let mut world = Volume::new([-32, -64, -32], [64, 384, 64], palette).unwrap();
        for x in -32..32 {
            for z in -32..32 {
                for y in -64..64 {
                    world.set([x, y, z], if y == 63 { grass } else { dirt });
                }
            }
        }
        for value in row["input"].as_array().unwrap() {
            world.set(
                std::array::from_fn(|i| value[i].as_i64().unwrap() as i32),
                ids[value[3].as_u64().unwrap() as usize],
            );
        }
        let mut expected = world.blocks.clone();
        world.decoration_entropy = Some(vss_native_core::random::Random::new(
            row["entropy"].as_str().unwrap().parse().unwrap(),
            0,
        ));
        for v in row["output"].as_array().unwrap() {
            let p: [i32; 3] = std::array::from_fn(|i| v[i].as_i64().unwrap() as i32);
            expected
                [((p[0] + 32) as usize * 64 + (p[2] + 32) as usize) * 384 + (p[1] + 64) as usize] =
                ids[v[3].as_u64().unwrap() as usize];
        }
        let name = row["feature"].as_str().unwrap();
        let (result, after) = backend
            .placed(
                &mut world,
                name,
                0,
                0,
                row["index"].as_i64().unwrap() as i32,
                row["step"].as_i64().unwrap() as i32,
            )
            .unwrap_or_else(|e| panic!("{name}: {e}"));
        let context = format!("{name} seed={seed} pattern={}", row["pattern"]);
        assert_eq!(result, row["placed"].as_bool().unwrap(), "{context}");
        assert_eq!(
            after.to_string(),
            row["after"].as_str().unwrap(),
            "{context}"
        );
        let differences: Vec<_> = world
            .blocks
            .iter()
            .zip(&expected)
            .enumerate()
            .filter(|(_, (a, b))| world.palette.state(**a) != world.palette.state(**b))
            .take(20)
            .map(|(i, (&a, &b))| {
                format!(
                    "pos={},{},{}: {:?} != {:?}",
                    (i / 384 / 64) as i32 - 32,
                    (i % 384) as i32 - 64,
                    (i / 384 % 64) as i32 - 32,
                    world.palette.state(a),
                    world.palette.state(b)
                )
            })
            .collect();
        assert!(
            differences.is_empty(),
            "{context}: {}",
            differences.join("\n")
        );
        cases += 1;
    }
    println!("vanilla placed features, seeds, modifiers and biome checks: {cases} cases");
}
#[test]
fn feature_error_restores_blocks_and_random() {
    use vss_native_core::{
        random::Random,
        vegetation::{Feature, PlacementContext},
    };
    let backend = World::new(0, 0, document()).unwrap();
    let mut palette = backend.palette.clone();
    let mut tree = backend.document["configured_features"]["minecraft:oak"].clone();
    tree["config"]["decorators"] = json!([{"type":"minecraft:beehive","probability":1.}]);
    let feature = Feature::compile(&tree, &backend.document, &mut palette).unwrap();
    let grass = palette.named("minecraft:grass_block").unwrap();
    let mut world = Volume::new([-16, -64, -16], [32, 384, 32], palette).unwrap();
    for x in -16..16 {
        for z in -16..16 {
            world.set([x, 63, z], grass);
        }
    }
    let before = world.blocks.clone();
    let mut r = Random::new(0, 3);
    let mut expected = r.clone();
    let mut allow = |_: &str, _| true;
    assert!(feature
        .place_transaction(
            &mut world,
            &mut r,
            [0, 64, 0],
            &mut PlacementContext::new("oak", &mut allow)
        )
        .err()
        .unwrap()
        .contains("entropy"));
    assert_eq!(world.blocks, before);
    assert!(world.published.is_empty());
    assert_eq!(r.next_long(), expected.next_long());
}

#[test]
fn vanilla_bee_nests_with_external_entropy() {
    use vss_native_core::random::Random;
    let backend = World::new(0, 0, document()).unwrap();
    let states = read("beehive-states.json");
    let mut count = 0;
    for row in rows("beehive.jsonl") {
        let mut palette = backend.palette.clone();
        let ids: Vec<_> = states
            .as_array()
            .unwrap()
            .iter()
            .map(|s| palette.intern(s).unwrap())
            .collect();
        let grass = palette.named("minecraft:grass_block").unwrap();
        let dirt = palette.named("minecraft:dirt").unwrap();
        let mut world = Volume::new([-16, -64, -16], [32, 384, 32], palette).unwrap();
        for x in -16..16 {
            for z in -16..16 {
                for y in -64..64 {
                    world.set([x, y, z], if y == 63 { grass } else { dirt });
                }
            }
        }
        let mut expected = world.blocks.clone();
        for v in row["output"].as_array().unwrap() {
            let p: [i32; 3] = std::array::from_fn(|i| v[i].as_i64().unwrap() as i32);
            expected
                [((p[0] + 16) as usize * 32 + (p[2] + 16) as usize) * 384 + (p[1] + 64) as usize] =
                ids[v[3].as_u64().unwrap() as usize];
        }
        world.decoration_entropy = Some(Random::new(
            row["entropy"].as_str().unwrap().parse().unwrap(),
            0,
        ));
        let (placed, after) = backend
            .feature(
                &mut world,
                &row["feature"],
                row["seed"].as_str().unwrap().parse().unwrap(),
                [0, 64, 0],
            )
            .unwrap();
        assert_eq!(placed, row["placed"].as_bool().unwrap());
        assert_eq!(after.to_string(), row["after"].as_str().unwrap());
        for (a, b) in world.blocks.iter().zip(expected) {
            assert_eq!(world.palette.state(*a), world.palette.state(b));
        }
        count += 1;
    }
    println!("Vanilla bee nest and tree states with supplied shuffle entropy: {count} cases");
}

#[test]
fn bounded_feature_writes_roll_back_published_edits() {
    let backend = World::new(0, 0, document()).unwrap();
    let mut world = Volume::new([-32, -64, -32], [80, 384, 80], backend.palette.clone()).unwrap();
    world.write_bounds = Some(([-16, -64, -16], [32, 320, 32]));
    world.write_budget = 65536;
    let stone = world.palette.named("minecraft:stone").unwrap();
    let before = world.blocks.clone();
    world.begin().unwrap();
    world.set([0, 100, 0], stone);
    world.set([32, 100, 0], stone);
    assert!(world.finish(true).is_err());
    assert_eq!(world.blocks, before);
    assert!(world.published.is_empty());
}
