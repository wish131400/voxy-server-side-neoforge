//! Blueprint `blueprint:modded` biome slice parity.
//!
//! The slices themselves never reach the vanilla codecs (`ModdedBiomeSource`'s
//! codec carries only `original_biome_source`), so these tests drive the
//! backend through the `vss_blueprint` snapshot section, exactly as a client
//! would after receiving the server's generator payload.
use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::backend::World;

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
    // Pin every climate channel so the wrapped multi-noise tree can only ever
    // answer plains; any other surface material then proves a slice answered.
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

/// Wraps the document's biome source in `blueprint:modded` and attaches the
/// snapshot section the client backend reads.
fn with_blueprint(doc: &mut Value, original: Value, slices: Value) {
    doc["biome_source"] = json!({
        "type": "blueprint:modded",
        "original_biome_source": original.clone(),
    });
    doc["vss_blueprint"] = json!({
        "original_biome_source": original,
        "slices": slices,
        "size": 8,
        "slices_seed": 12345,
        "slices_zoom_seed": 67890,
    });
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

#[test]
fn wrapped_terrablender_keeps_regions_then_applies_blueprint_overlays() {
    let mut direct = base_document();
    direct["possible_biomes"] = json!(["minecraft:plains", "minecraft:badlands"]);
    let regions = json!({"region_size":2,"regions":[
        {"base":true,"weighted":true,"weight":10},
        {"base":false,"weighted":true,"weight":10,"groups":[
            {"biome":"minecraft:badlands","points":[[0,0,0,0,0,0,0,0,0,0,0,0,0]]}]}]});
    direct["vss_terrablender"] = regions.clone();
    let points = sampling_points();
    let reference = top_blocks(&World::new(42, 0, direct.clone()).unwrap(), &points);
    let vanilla = top_blocks(&World::new(42, 0, base_document()).unwrap(), &points);
    assert_ne!(reference, vanilla, "the region routing must affect materials");

    let mut wrapped = direct.clone();
    wrapped.as_object_mut().unwrap().remove("vss_terrablender");
    with_blueprint(&mut wrapped, direct["biome_source"].clone(), json!([
        {"name":"test:original","slice":{"weight":1,"provider":{"type":"blueprint:original"}}}]));
    wrapped["vss_blueprint"]["original_terrablender"] = regions;
    let world = World::new(42, 0, wrapped.clone()).unwrap();
    assert!(world.uses_terrablender_routing());
    assert_eq!(top_blocks(&world, &points), reference);

    // An overlay must see the TerraBlender result. Applying the region after
    // Blueprint, or unwrapping Blueprint entirely, would leave badlands here.
    wrapped["vss_blueprint"]["slices"] = json!([
        {"name":"test:original","slice":{"weight":1,"provider":{"type":"blueprint:original"}}},
        {"name":"test:overlay","slice":{"weight":100,"provider":{"type":"blueprint:overlay",
         "overlays":[{"matches_biomes":["minecraft:badlands"],
         "biome_source":{"type":"minecraft:fixed","biome":"minecraft:plains"}}]}}}]);
    let overlaid = top_blocks(&World::new(42, 0, wrapped.clone()).unwrap(), &points);
    assert_ne!(overlaid, reference);
    assert!(overlaid.iter().zip(&reference).zip(&vanilla).all(|((a,b),v)| a == b || a == v));
    wrapped["vss_blueprint"]["original_terrablender"] = json!({});
    assert!(World::new(42, 0, wrapped).is_err(), "invalid nested routing must not silently use the base");
}

fn top_blocks(world: &World, points: &[(i32, i32)]) -> Vec<i32> {
    let mut tops = Vec::with_capacity(points.len());
    for batch in points.chunks(64) {
        for column in world.surface_points(batch).unwrap() {
            tops.push(column.values[4]);
        }
    }
    tops
}

/// A slice whose provider always forwards: Blueprint's `original` provider
/// answers with the wrapped source and never with the placeholder, so it can
/// neither be retired nor change the vanilla answer.
#[test]
fn original_provider_slice_preserves_the_wrapped_source() {
    let vanilla = base_document();
    let points = sampling_points();
    let reference = top_blocks(&World::new(42, 0, vanilla.clone()).unwrap(), &points);

    let mut doc = vanilla.clone();
    with_blueprint(
        &mut doc,
        vanilla["biome_source"].clone(),
        json!([{"name":"test:originals","slice":{
            "weight":100,"provider":{"type":"blueprint:original"}}}]),
    );
    let wrapped = top_blocks(&World::new(42, 0, doc).unwrap(), &points);
    assert_eq!(
        wrapped, reference,
        "an original-provider slice must be transparent"
    );
}

/// An overlay slice replaces the wrapped source's answer when it lands inside
/// `matches_biomes`, and is retired (falling through to the wrapped source)
/// when it does not.
#[test]
fn overlay_slice_replaces_matching_biomes_and_retires_otherwise() {
    let vanilla = base_document();
    let points = sampling_points();
    let plains = top_blocks(&World::new(42, 0, vanilla.clone()).unwrap(), &points);

    let mut badlands_doc = vanilla.clone();
    badlands_doc["biome_source"] =
        json!({"type":"minecraft:fixed","biome":"minecraft:badlands"});
    let badlands = top_blocks(&World::new(42, 0, badlands_doc).unwrap(), &points);
    assert_ne!(plains, badlands, "fixture must separate the two biomes");

    // Matching overlay: the wrapper answers plains, the overlay swaps in
    // badlands. Paired with a weight-100 original slice so retirement of the
    // overlay always leaves a live slice behind, as it does in practice.
    let mut matching = vanilla.clone();
    with_blueprint(
        &mut matching,
        vanilla["biome_source"].clone(),
        json!([
            {"name":"test:originals","slice":{
                "weight":100,"provider":{"type":"blueprint:original"}}},
            {"name":"test:swap","slice":{"weight":100,"provider":{
                "type":"blueprint:overlay",
                "overlays":[{"matches_biomes":["minecraft:plains"],
                    "biome_source":{"type":"minecraft:fixed","biome":"minecraft:badlands"}}]}}}
        ]),
    );
    // Slice choice is positional and random, so with equal weights roughly
    // half the columns take the overlay and half stay on the original slice.
    // What must hold is that the overlay actually wins columns and that the
    // mixture differs from the original-only answer.
    let matched = top_blocks(&World::new(42, 0, matching).unwrap(), &points);
    assert!(
        matched.iter().any(|top| *top != plains[0]),
        "a matching overlay must win at least one column"
    );
    assert_ne!(matched, plains, "the overlay must change the column mixture");

    // Non-matching overlay: every column answers with the placeholder, so the
    // slice is retired and the original slice carries the column instead.
    let mut unmatched = vanilla.clone();
    with_blueprint(
        &mut unmatched,
        vanilla["biome_source"].clone(),
        json!([
            {"name":"test:originals","slice":{
                "weight":100,"provider":{"type":"blueprint:original"}}},
            {"name":"test:swap","slice":{"weight":100,"provider":{
                "type":"blueprint:overlay",
                "overlays":[{"matches_biomes":["minecraft:desert"],
                    "biome_source":{"type":"minecraft:fixed","biome":"minecraft:badlands"}}]}}}
        ]),
    );
    assert_eq!(
        top_blocks(&World::new(42, 0, unmatched).unwrap(), &points),
        plains,
        "an unmatched overlay must retire and fall back to the wrapped source"
    );
}

/// A `multi_noise` provider resolves against its own table, and `areas`
/// rewrites biome ids at load time exactly as Blueprint's codec does.
#[test]
fn multi_noise_provider_maps_areas_and_answers_from_its_own_tree() {
    let vanilla = base_document();
    let points = sampling_points();
    let plains = top_blocks(&World::new(42, 0, vanilla.clone()).unwrap(), &points);

    let blank = json!({
        "temperature":[-4.0,4.0],"humidity":[-4.0,4.0],"continentalness":[-4.0,4.0],
        "erosion":[-4.0,4.0],"depth":[-4.0,4.0],"weirdness":[-4.0,4.0],"offset":[-1.0,1.0]});

    let mut doc = vanilla.clone();
    with_blueprint(
        &mut doc,
        vanilla["biome_source"].clone(),
        json!([
            {"name":"test:originals","slice":{
                "weight":100,"provider":{"type":"blueprint:original"}}},
            {"name":"test:areas","slice":{"weight":100,"provider":{
                "type":"blueprint:multi_noise",
                "areas":{"test:area":"minecraft:badlands"},
                "only_map_from_areas":true,
                "biomes":[{"biome":"test:area","parameters":blank.clone()}]}}}
        ]),
    );
    let areas = top_blocks(&World::new(42, 0, doc).unwrap(), &points);
    assert!(
        areas.iter().any(|top| *top != plains[0]),
        "the areas-mapped multi-noise slice must win at least one column"
    );
    assert_ne!(areas, plains, "the mapped slice must change the column mixture");
}

/// A wrapper with no snapshot must be refused rather than silently treated as
/// a plain forwarder.
#[test]
fn wrapper_without_a_snapshot_is_refused() {
    let mut doc = base_document();
    doc["biome_source"] = json!({
        "type": "blueprint:modded",
        "original_biome_source": doc["biome_source"].clone(),
    });
    assert!(
        World::new(42, 0, doc).is_err(),
        "a blueprint wrapper without vss_blueprint must fail closed"
    );
}
