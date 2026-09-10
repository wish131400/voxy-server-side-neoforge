use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::backend::World;

#[test]
fn stateful_surface_batches_preserve_full_depth_materials_and_neighbours() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |name: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(name)).unwrap()).unwrap()
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
    // A vertical cache_2d dependency selects the same order-sensitive kernel
    // used by terrain packs, including its full-array cache_once semantics.
    base["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:interpolated", "argument":{
        "type":"minecraft:add", "argument1":{"type":"minecraft:y_clamped_gradient",
            "from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.},
        "argument2":{"type":"minecraft:cache_once","argument":{"type":"minecraft:mul",
            "argument1":0.04,"argument2":{"type":"minecraft:cache_2d","argument":{
                "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}}}}}});
    for biome in ["plains", "snowy_plains", "frozen_ocean", "eroded_badlands"] {
        for remove_surface in [false, true] {
            let mut doc = base.clone();
            doc["biome_source"] =
                json!({"type":"minecraft:fixed","biome":format!("minecraft:{biome}")});
            if remove_surface {
                // The first output stone moves below the retained shell. This
                // must regenerate full depth without damaging later batch points.
                doc["settings"]["surface_rule"] = json!({"type":"minecraft:condition",
                    "if_true":{"type":"minecraft:y_above","anchor":{"absolute":80},
                        "surface_depth_multiplier":0,"add_stone_depth":false},
                    "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:air"}}});
            }
            for seed in [0, -917] {
                let world = World::new(seed, 0, doc.clone()).unwrap();
                let oracle = World::new(seed, 0, doc.clone()).unwrap();
                assert!(world.terrain.graph.requires_complete_column_order());
                let points = [
                    (15, 15),
                    (0, 0),
                    (4, 3),
                    (3, 4),
                    (15, 0),
                    (-1, -1),
                    (-16, -16),
                    (0, 0),
                ];
                let actual = world.surface_points(&points).unwrap();
                for ((x, z), actual) in points.into_iter().zip(actual) {
                    let expected = oracle.surface_columns(x >> 4, z >> 4).unwrap()
                        [((x & 15) * 16 + (z & 15)) as usize];
                    assert_eq!(
                        actual.values, expected.values,
                        "{biome} seed={seed} remove={remove_surface} {x},{z}"
                    );
                }
                let mut job = world.terrain.job(0, 0, false).unwrap();
                job.prepare_surface_columns_with_depth(&[(4, 4)], |_, _, _| Some(4));
                assert!(
                    job.surface_bottom(4, 4).is_some(),
                    "stateful exterior must actually truncate"
                );
            }
        }
    }
}
#[test]
fn exterior_nether_end_and_void_match_complete_columns() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |s: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(s)).unwrap()).unwrap()
    };
    for dimension in ["nether.json", "end.json"] {
        let mut doc = read(dimension);
        for (key, file) in [
            ("block_definitions", "blocks.json"),
            ("biomes", "biomes.json"),
            ("grass_colormap", "grass.json"),
            ("foliage_colormap", "foliage.json"),
        ] {
            doc[key] = read(file);
        }
        for seed in [0, 731] {
            let world = World::new(seed, 0, doc.clone()).unwrap();
            let oracle = World::new(seed, 0, doc.clone()).unwrap();
            let coords = [
                (-17, 16),
                (0, 0),
                (15, 15),
                (1024, 2048),
                (777, -919),
                (9999, -1999),
            ];
            let values = world.surface_points(&coords).unwrap();
            for ((x, z), actual) in coords.into_iter().zip(values) {
                assert_eq!(
                    actual.values,
                    oracle.surface_columns(x >> 4, z >> 4).unwrap()
                        [((x & 15) * 16 + (z & 15)) as usize]
                        .values,
                    "{dimension} seed={seed} at {x},{z}"
                );
            }
        }
    }
}
#[test]
fn sparse_queries_match_complete_surface_chunks() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |s: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(s)).unwrap()).unwrap()
    };
    let base = read("overworld.json");
    let mut count = 0;
    for biome in [
        "plains",
        "eroded_badlands",
        "frozen_ocean",
        "deep_frozen_ocean",
        "stony_peaks",
        "swamp",
        "mangrove_swamp",
        "snowy_plains",
        "badlands",
        "overworld_multinoise",
    ] {
        let mut doc = base.clone();
        if biome != "overworld_multinoise" {
            doc["biome_source"] =
                json!({"type":"minecraft:fixed","biome":format!("minecraft:{biome}")});
        }
        for (key, file) in [
            ("block_definitions", "blocks.json"),
            ("biomes", "biomes.json"),
            ("grass_colormap", "grass.json"),
            ("foliage_colormap", "foliage.json"),
        ] {
            doc[key] = read(file);
        }
        for seed in [0, 693280690516334765i64] {
            let world = World::new(seed, 0, doc.clone()).unwrap();
            let batch_world = World::new(seed, 0, doc.clone()).unwrap();
            for (cx, cz) in [(0, 0), (-13, 17)] {
                let positions = [
                    (0, 0),
                    (15, 15),
                    (1, 1),
                    (14, 14),
                    (7, 8),
                    (8, 7),
                    (0, 15),
                    (15, 0),
                    (0, 7),
                    (7, 0),
                ];
                let sparse: Vec<_> = positions
                    .iter()
                    .map(|&(x, z)| world.surface_point(cx * 16 + x, cz * 16 + z).unwrap())
                    .collect();
                let coordinates: Vec<_> = positions
                    .iter()
                    .rev()
                    .map(|&(x, z)| (cx * 16 + x, cz * 16 + z))
                    .collect();
                let batched = batch_world.surface_points(&coordinates).unwrap();
                for (point, original) in batched.iter().rev().zip(&sparse) {
                    assert_eq!(
                        point.values, original.values,
                        "grouped sparse order/cache mismatch {biome}"
                    );
                }
                let full = world.surface_columns(cx, cz).unwrap();
                if !biome.contains("frozen_ocean") && biome != "overworld_multinoise" {
                    assert!(
                        world.work_counts()[1] >= positions.len() as u64,
                        "sparse path did not execute for {biome}"
                    );
                }
                for ((x, z), point) in positions.into_iter().zip(sparse) {
                    assert_eq!(
                        point.values,
                        full[(x * 16 + z) as usize].values,
                        "{biome} seed={seed} chunk={cx},{cz} pos={x},{z}"
                    );
                    count += 1;
                }
            }
        }
    }
    println!("Sparse/full-chunk material, height, fluid, tint comparisons={count}");
}

#[test]
fn grouped_queries_preserve_duplicates_cross_chunk_order_and_cancellation() {
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
    let world = World::new(42, 0, doc.clone()).unwrap();
    let oracle = World::new(42, 0, doc).unwrap();
    let points = [
        (16, -1),
        (-17, 16),
        (0, 0),
        (16, -1),
        (-16, 15),
        (15, -16),
        (0, 0),
        (-17, 16),
    ];
    let actual = world.surface_points(&points).unwrap();
    for (v, (x, z)) in actual.iter().zip(points) {
        assert_eq!(
            v.values,
            oracle.surface_columns(x >> 4, z >> 4).unwrap()[((x & 15) * 16 + (z & 15)) as usize]
                .values
        );
    }
    assert!(world.surface_points(&[(0, 0); 65]).is_err());
    assert!(world.surface_points(&[(30_000_000, 0)]).is_err());
    world.cancel();
    assert!(
        world.surface_points(&points).is_err(),
        "cached batches must still honor cancellation"
    );
}
