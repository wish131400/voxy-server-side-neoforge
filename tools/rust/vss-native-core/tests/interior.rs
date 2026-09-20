use serde_json::{json, Value};
use std::{
    fs,
    path::Path,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
};
use vss_native_core::{
    backend::World,
    blocks::{Palette, Volume},
};

fn document() -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |name: &str| {
        serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap()
    };
    let mut doc = read("nether.json");
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        doc[key] = read(file);
    }
    doc
}

#[test]
fn complete_material_columns_match_full_surface_pass_in_all_nether_biomes() {
    for biome in [
        "crimson_forest",
        "warped_forest",
        "soul_sand_valley",
        "basalt_deltas",
        "nether_wastes",
    ] {
        let mut doc = document();
        doc["biome_source"] =
            json!({"type":"minecraft:fixed","biome":format!("minecraft:{biome}")});
        let world = Arc::new(World::new(42, 0, doc).unwrap());
        let mut dense = world.surface_region(-1, 0, 1).unwrap();
        let mut proxy = world.interior_proxy(-1, 0).unwrap();
        let mut caves = 0;
        for x in -16..0 {
            for z in 0..16 {
                let column = world.interior_column(x, z).unwrap();
                assert!(Arc::ptr_eq(&column, &world.interior_column(x, z).unwrap()));
                for y in 0..128 {
                    let actual = column[y as usize];
                    assert_eq!(actual, dense.get([x, y, z]), "{biome} {x},{y},{z}");
                    assert_eq!(actual, proxy.get([x, y, z]));
                    if y > 32 && y < 100 && world.palette.is_air(actual) {
                        caves += 1;
                    }
                }
                for kind in 0..4 {
                    assert_eq!(proxy.heightmap(x, z, kind), dense.heightmap(x, z, kind));
                }
            }
        }
        assert!(caves > 0);
    }
}

#[test]
fn interior_proxy_reads_only_touched_columns_and_rolls_back_cave_edits() {
    let mut palette = Palette::from_json(&document()["block_definitions"]).unwrap();
    let rock = palette.named("minecraft:netherrack").unwrap();
    let air = palette.air;
    let count = Arc::new(AtomicUsize::new(0));
    let calls = Arc::clone(&count);
    let mut volume = Volume::interior_proxy(
        [-16, 0, -16],
        32,
        32,
        128,
        palette,
        Box::new(move |_, _| {
            calls.fetch_add(1, Ordering::Relaxed);
            Ok(Arc::new(
                (0..128)
                    .map(|y| if y < 40 || y >= 90 { rock } else { air })
                    .collect(),
            ))
        }),
    )
    .unwrap();
    assert!(volume.blocks.is_empty());
    assert_eq!(count.load(Ordering::Relaxed), 0);
    assert_eq!(volume.get([-1, 50, -1]), air);
    assert_eq!(volume.get([-1, 95, -1]), rock);
    assert_eq!(count.load(Ordering::Relaxed), 1);
    volume.begin().unwrap();
    volume.set([-1, 50, -1], rock);
    volume.set([-1, 95, -1], air);
    volume.finish(false).unwrap();
    assert_eq!(volume.get([-1, 50, -1]), air);
    assert_eq!(volume.get([-1, 95, -1]), rock);
    assert!(volume.published.is_empty());
    let mut failed = Volume::interior_proxy(
        [0, 0, 0],
        16,
        16,
        128,
        volume.palette.clone(),
        Box::new(|_, _| Err("missing".into())),
    )
    .unwrap();
    failed.begin().unwrap();
    failed.set([0, 50, 0], rock);
    failed.get([1, 50, 0]);
    assert!(failed.finish(true).is_err());
    assert!(failed.published.is_empty());
}
