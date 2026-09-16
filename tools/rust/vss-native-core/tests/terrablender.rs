use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::backend::World;
use vss_native_core::climate::uniqueness_grid;

/// The Java prediction ships the same zoom-stack port
/// (dev.xantha.vss.client.prediction.TerrablenderUniqueness). These rows were
/// generated from that implementation so both languages must agree exactly:
/// seed 42, region size 2, weighted regions [(0,10),(1,100),(2,100)], sampled
/// on the 9x9 grid x,z in [-256,256] step 64, row-major by z.
const JAVA_GRID42: &str = "221222212 222122222 211121112 221221112 121211111 120221211 222220211 211111122 122212112";
/// Seed 99, region size 3, weighted regions [(0,10),(1,100)], 5x5 grid
/// x,z in [-64,64] step 32. A single weighted region must fill the grid.
const JAVA_GRID99: &str = "11100 11110 11111 11111 11111";

#[test]
fn uniqueness_grid_matches_the_java_reference() {
    let grid = uniqueness_grid(42, 2, vec![(0, 10), (1, 100), (2, 100)]);
    let mut actual = String::new();
    for z in (-256..=256).step_by(64) {
        for x in (-256..=256).step_by(64) {
            actual.push_str(&grid.get(x, z).to_string());
        }
        actual.push(' ');
    }
    assert_eq!(actual.trim(), JAVA_GRID42);

    let grid = uniqueness_grid(99, 3, vec![(0, 10), (1, 100)]);
    let mut actual = String::new();
    for z in (-64..=64).step_by(32) {
        for x in (-64..=64).step_by(32) {
            actual.push_str(&grid.get(x, z).to_string());
        }
        actual.push(' ');
    }
    assert_eq!(actual.trim(), JAVA_GRID99);
}

fn base_document() -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |name: &str| -> Value {
        serde_json::from_str(&fs::read_to_string(root.join(name)).unwrap()).unwrap()
    };
    let mut doc = read("overworld.json");
    doc["block_definitions"] = read("blocks.json");
    doc["biomes"] = read("biomes.json");
    doc["grass_colormap"] = read("grass.json");
    doc["foliage_colormap"] = read("foliage.json");
    // A fixed climate target pins biome selection: the vanilla multi-noise
    // tree can only answer plains, so any other material on the surface
    // proves the positional routing took over.
    for channel in [
        "temperature",
        "vegetation",
        "continents",
        "erosion",
        "depth",
        "ridges",
    ] {
        doc["settings"]["noise_router"][channel] = json!({"type":"minecraft:constant","argument":0.0});
    }
    doc["biome_source"] = json!({"type":"minecraft:multi_noise","biomes":[
        {"biome":"minecraft:plains","parameters":{
            "temperature":[-4.0,4.0],"humidity":[-4.0,4.0],"continentalness":[-4.0,4.0],
            "erosion":[-4.0,4.0],"depth":[-4.0,4.0],"weirdness":[-4.0,4.0],"offset":[-1.0,1.0]}}]});
    doc["settings"]["surface_rule"] = json!({"type":"minecraft:sequence","sequence":[
        {"type":"minecraft:condition","if_true":{"type":"minecraft:biome",
            "biome_is":["minecraft:badlands"]},
         "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:orange_terracotta"}}},
        {"type":"minecraft:condition","if_true":{"type":"minecraft:biome",
            "biome_is":["minecraft:plains"]},
         "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:grass_block"}}}]});
    doc
}

fn region_group(biome: &str) -> Value {
    // 13 unquantized floats: six [min,max] parameter pairs plus the offset,
    // all pinned to zero so the constant-climate target sits inside the span.
    json!({"biome": biome, "points": [[
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]})
}

fn sampling_points() -> Vec<(i32, i32)> {
    let mut points = vec![];
    for z in (-1024..=1024).step_by(64) {
        for x in (-1024..=1024).step_by(64) {
            points.push((x, z));
        }
    }
    points
}

fn top_blocks(world: &World, points: &[(i32, i32)]) -> Vec<i32> {
    let mut tops = Vec::with_capacity(points.len());
    // surface_points accepts at most 64 points per batch.
    for batch in points.chunks(64) {
        for column in world.surface_points(batch).unwrap() {
            tops.push(column.values[4]);
        }
    }
    tops
}

fn with_terrablender(doc: &mut Value, regions: Value) {
    doc["vss_terrablender"] = json!({"region_size": 2, "regions": regions});
    doc["possible_biomes"] = json!(["minecraft:plains", "minecraft:badlands"]);
}

#[test]
fn terrablender_routing_selects_region_biomes_and_falls_back() {
    let vanilla = base_document();
    let reference_plains = World::new(42, 0, vanilla.clone()).unwrap();
    let mut fixed_badlands = vanilla.clone();
    fixed_badlands["biome_source"] =
        json!({"type":"minecraft:fixed","biome":"minecraft:badlands"});
    let reference_badlands = World::new(42, 0, fixed_badlands).unwrap();

    let points = sampling_points();
    let plains_tops = top_blocks(&reference_plains, &points);
    let badlands_tops = top_blocks(&reference_badlands, &points);
    assert_ne!(plains_tops[0], badlands_tops[0], "fixture must separate the biomes");

    // Region 1 wins the uniqueness grid for most columns and answers with a
    // badlands climate point; region 2 answers with the deferred placeholder
    // and must fall back to the base plains search.
    let mut mixed = vanilla.clone();
    with_terrablender(&mut mixed, json!([
        {"name":"minecraft:overworld","weight":10,"base":true,"weighted":true},
        {"name":"test:badlands","weight":100,"weighted":true,"groups":[region_group("minecraft:badlands")]},
        {"name":"test:placeholder","weight":100,"weighted":true,"groups":[region_group("terrablender:deferred_placeholder")]}
    ]));
    let mixed_tops = top_blocks(&World::new(42, 0, mixed).unwrap(), &points);
    assert!(mixed_tops.contains(&badlands_tops[0]), "routed columns must use the region biome");
    assert!(mixed_tops.contains(&plains_tops[0]), "placeholder winners must fall back to plains");
    assert_ne!(mixed_tops, plains_tops, "routing must change the vanilla-only answer");

    // A region whose points are only the placeholder never selects it.
    let mut placeholder = vanilla.clone();
    with_terrablender(&mut placeholder, json!([
        {"name":"minecraft:overworld","weight":10,"base":true,"weighted":true},
        {"name":"test:placeholder","weight":100,"weighted":true,"groups":[region_group("terrablender:deferred_placeholder")]}
    ]));
    assert_eq!(
        top_blocks(&World::new(42, 0, placeholder).unwrap(), &points),
        plains_tops,
        "placeholder-only regions must collapse to the base search"
    );

    // One weighted non-base region covers the whole grid; the base region
    // stays unweighted so it can never win a grid cell.
    let mut single = vanilla.clone();
    single["vss_terrablender"] = json!({"region_size": 3, "regions": [
        {"name":"minecraft:overworld","weight":10,"base":true,"weighted":false},
        {"name":"test:badlands","weight":100,"weighted":true,"groups":[region_group("minecraft:badlands")]}
    ]});
    single["possible_biomes"] = json!(["minecraft:plains", "minecraft:badlands"]);
    let single_tops = top_blocks(&World::new(42, 0, single).unwrap(), &points);
    assert_eq!(single_tops, badlands_tops, "a single weighted region must fill the grid");
}

#[test]
fn terrablender_routing_is_deterministic_per_seed() {
    let mut doc = base_document();
    with_terrablender(&mut doc, json!([
        {"name":"minecraft:overworld","weight":10,"base":true,"weighted":true},
        {"name":"test:badlands","weight":100,"weighted":true,"groups":[region_group("minecraft:badlands")]}
    ]));
    let points = sampling_points();
    let first = top_blocks(&World::new(42, 0, doc.clone()).unwrap(), &points);
    let second = top_blocks(&World::new(42, 0, doc.clone()).unwrap(), &points);
    assert_eq!(first, second);
    // A different world seed must be able to shuffle the grid.
    let other = top_blocks(&World::new(43, 0, doc).unwrap(), &points);
    assert_ne!(first, other, "seeds must shuffle the uniqueness grid");
}
