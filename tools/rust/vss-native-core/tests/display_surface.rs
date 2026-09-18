use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::{backend::World, density::Mode, terrain::Terrain};
fn document() -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |f: &str| {
        serde_json::from_str::<Value>(&fs::read_to_string(root.join(f)).unwrap()).unwrap()
    };
    let mut d = read("overworld.json");
    for (k, f) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        d[k] = read(f);
    }
    d
}

#[test]
fn sparse_workspace_matches_individual_queries_across_chunks_and_revisits() {
    for seed in [0, -917] {
        let d = document();
        let batched = World::new(seed, 13, d.clone()).unwrap();
        let oracle = World::new(seed, 13, d).unwrap();
        for step in [16, 64, 128, 256, 512] {
            let mut points: Vec<_> = (0..64)
                .map(|i| (-4097 + i % 8 * step, -65 + i / 8 * step)).collect();
            // Duplicate/nearby points and shuffled groups retain their original output order.
            points[62] = points[3];
            points.reverse();
            let expected: Vec<_> = points.iter().map(|&p| oracle.display_points(&[p]).unwrap()[0].values).collect();
            let actual: Vec<_> = batched.display_points(&points).unwrap().iter().map(|r| r.values).collect();
            assert_eq!(actual, expected, "seed={seed}, step={step}");
            assert_eq!(actual, batched.display_points(&points).unwrap().iter().map(|r| r.values).collect::<Vec<_>>());
        }
        let dense: Vec<_> = (0..64).map(|i| (-16+i%8, 16+i/8)).collect();
        assert_eq!(batched.display_points(&dense).unwrap().iter().map(|r|r.values).collect::<Vec<_>>(),
                   oracle.display_points(&dense).unwrap().iter().map(|r|r.values).collect::<Vec<_>>());
    }
}

#[test]
fn dense_cell_bounds_keep_local_thin_layers_across_negative_coordinates() {
    let mut d = document();
    d["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:plains"});
    d["settings"]["surface_rule"] = json!({"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}});
    d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:max",
        "argument1":{"type":"minecraft:y_clamped_gradient","from_y":31,"to_y":33,"from_value":1.,"to_value":-1.},
        "argument2":{"type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"x"},
            "min_inclusive":-1,"max_exclusive":1,"when_out_of_range":-1.,
            "when_in_range":{"type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"y"},
                "min_inclusive":105,"max_exclusive":106,"when_in_range":1.,"when_out_of_range":-1.}}});
    let dense = World::new(0,0,d.clone()).unwrap();
    let sparse = World::new(0,0,d).unwrap();
    let points: Vec<_> = (-4..4).flat_map(|z|(-4..4).map(move |x|(x,z))).collect();
    for batch in points.chunks(32) {
        let rows = dense.display_points(batch).unwrap();
        for (&p,row) in batch.iter().zip(rows) {
            assert_eq!(row.values[0], if (-1..1).contains(&p.0) {106} else {32});
            assert_eq!(row.values, sparse.display_points(&[p]).unwrap()[0].values);
        }
    }
}

#[test]
fn frozen_ocean_admits_clear_water_but_keeps_iceberg_geometry() {
    let mut d = document();
    d["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:deep_frozen_ocean"});
    d["settings"]["aquifers_enabled"] = json!(false);
    d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:y_clamped_gradient",
        "from_y":19,"to_y":21,"from_value":1.,"to_value":-1.});
    let world = World::new(0, 0, d.clone()).unwrap();
    let oracle = World::new(0, 0, d).unwrap();
    let points: Vec<_> = (0..64).map(|i| (100000+i*16,100000)).collect();
    let display = world.display_points(&points).unwrap();
    let exact = oracle.surface_points(&points).unwrap();
    assert!(world.display_query_stats()[5] > 0, "clear water must use the display envelope");
    assert!(world.display_query_stats()[3] > 0, "iceberg columns must retain fallback");
    assert!(exact.iter().any(|r| r.values[0] > 63), "fixture must contain real icebergs");
    for (a,b) in display.iter().zip(&exact) {
        assert_eq!(&a.values[..3], &b.values[..3], "height, water and fluid must agree");
    }
    assert_eq!(world.display_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>(),
        display.iter().map(|r|r.values).collect::<Vec<_>>());
    assert_eq!(world.surface_points(&points).unwrap().iter().map(|r|r.values).collect::<Vec<_>>(),
        exact.iter().map(|r|r.values).collect::<Vec<_>>());
}

#[test]
fn display_envelope_keeps_thin_density_layers_and_separates_exact_queries() {
    for thin_y in [None, Some(104), Some(105), Some(106), Some(111)] {
        let mut d = document();
        d["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:plains"});
        d["settings"]["surface_rule"] = json!({"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}});
        let ground = json!({"type":"minecraft:y_clamped_gradient","from_y":31,"to_y":33,"from_value":1.,"to_value":-1.});
        d["settings"]["noise_router"]["final_density"] = if let Some(y) = thin_y {
            json!({"type":"minecraft:max","argument1":ground,"argument2":{
                "type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"y"},
                "min_inclusive":y,"max_exclusive":y+1,"when_in_range":1.,"when_out_of_range":-1.}})
        } else { ground };
        // The original vanilla fluid router qualifies for the visual sea plane.
        let world = World::new(0, 0, d.clone()).unwrap();
        let oracle = World::new(0, 0, d).unwrap();
        let points = [(-17, 31), (0, 0), (17, -31)];
        let display = world.display_points(&points).unwrap();
        assert!(world.display_query_stats()[5] > 0, "must exercise density display, not exact fallback");
        for row in display {
            assert_eq!(row.values[0], thin_y.map_or(32, |y| y + 1));
            assert_eq!(row.values[1], thin_y.map_or(63, |y| y + 1));
            assert_eq!(row.values[2], if thin_y.is_none() { 1 } else { 0 });
            assert_ne!(row.values[3] & (1 << 27), 0, "approximate display identity");
        }
        assert_eq!(world.surface_points(&points).unwrap().iter().map(|r| r.values).collect::<Vec<_>>(),
                   oracle.surface_points(&points).unwrap().iter().map(|r| r.values).collect::<Vec<_>>());
    }
}

#[test]
fn display_envelope_empty_columns_do_not_invent_oceans_or_terrain() {
    let mut d = document();
    d["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:plains"});
    d["settings"]["surface_rule"] = json!({"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}});
    d["settings"]["noise_router"]["final_density"] = json!(-1.);
    let world = World::new(0, 0, d).unwrap();
    let row = world.display_points(&[(0, 0)]).unwrap()[0];
    assert_eq!(&row.values[..3], &[-64, -64, 0]);
    assert_ne!(row.values[3] & (1 << 29), 0);
    assert_eq!(world.display_query_stats()[5], 1);
}

#[test]
fn custom_aquifer_uses_checked_envelope_on_land_and_preserves_dry_depressions() {
    for (floor, flooded) in [(128, -100.), (32, -100.), (32, 100.)] {
        let mut d = document();
        d["biome_source"] = json!({"type":"minecraft:fixed","biome":"minecraft:plains"});
        d["settings"]["surface_rule"] = json!({"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}});
        d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:y_clamped_gradient",
            "from_y":floor-1,"to_y":floor+1,"from_value":1.,"to_value":-1.});
        d["settings"]["noise_router"]["fluid_level_floodedness"] = json!(flooded);
        let world = World::new(0, 0, d.clone()).unwrap();
        let exact = World::new(0, 0, d).unwrap();
        let display = world.display_points(&[(1234, 5678)]).unwrap()[0];
        let oracle = exact.surface_points(&[(1234, 5678)]).unwrap()[0];
        assert_eq!(&display.values[..3], &oracle.values[..3]);
        if floor == 128 {
            assert_eq!(world.display_query_stats()[5], 1, "custom router must not disable all fast terrain");
        } else if flooded < 0. {
            assert_eq!(display.values[2], 0, "dry aquifer must not become an ocean");
            assert!(world.display_query_stats()[3] > 0, "disagreement must use exact fallback");
        }
    }
}
#[test]
fn density_intervals_keep_thin_sheets_and_interpolation() {
    for interpolate in [false, true] {
        for y in 104..112 {
            let mut d = document();
            let mut v = json!({"type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"y"},
            "min_inclusive":y,"max_exclusive":y+1,"when_in_range":1.,"when_out_of_range":-1.});
            if interpolate {
                v = json!({"type":"minecraft:interpolated","argument":v});
            }
            d["settings"]["noise_router"]["final_density"] = v;
            let t = Terrain::from_document(0, &d).unwrap();
            let id = t.graph.root("final_density").unwrap();
            for (x, z) in [(-7, 13), (1, -1)] {
                let mut oracle = t.job(x, z, false).unwrap();
                let expected = (t.min_y..t.min_y + t.height).rev().find(|&y| {
                    oracle.scratch.advance_block();
                    t.graph
                        .compute(id, [x, y, z], Mode::Cell, &mut oracle.scratch)
                        > 0.
                });
                assert_eq!(t.job(x, z, false).unwrap().density_surface(x, z), expected);
            }
        }
    }
}
#[test]
fn display_is_isolated_from_exact_cache_and_custom_liquids_fall_back() {
    for flooded in [-100., 100.] {
        let mut d = document();
        d["settings"]["noise_router"]["fluid_level_floodedness"] = json!(flooded);
        let world = World::new(0, 0, d.clone()).unwrap();
        let oracle = World::new(0, 0, d).unwrap();
        let pts = [(-16, -16), (0, 0), (100000, 100000), (-100003, 99997)];
        let display = world.display_points(&pts).unwrap();
        let exact = oracle.surface_points(&pts).unwrap();
        for (a, b) in display.iter().zip(&exact) {
            let mut a = a.values;
            a[3] &= !((1 << 26) | (1 << 27) | (1 << 28));
            let mut b = b.values;
            b[3] &= !(1 << 28);
            assert_eq!(a, b);
        }
        assert_eq!(
            world
                .surface_points(&pts)
                .unwrap()
                .iter()
                .map(|v| v.values)
                .collect::<Vec<_>>(),
            exact.iter().map(|v| v.values).collect::<Vec<_>>()
        );
        world.cancel();
        assert!(world.display_points(&pts).is_err());
    }
}
#[test]
fn adaptive_grid_keeps_shared_edges_and_surface_removal_rules() {
    let mut d = document();
    d["settings"]["aquifers_enabled"] = json!(false);
    d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:y_clamped_gradient","from_y":96,"to_y":97,"from_value":1.,"to_value":-1.});
    for state in ["minecraft:stone", "minecraft:air", "minecraft:water"] {
        d["settings"]["surface_rule"] =
            json!({"type":"minecraft:block","result_state":{"Name":state}});
        let w = World::new(0, 0, d.clone()).unwrap();
        let points: Vec<_> = (0..64).map(|i| (-3 + i % 8, -5 + i / 8)).collect();
        let grid = w.display_points(&points).unwrap();
        for i in 0..64 {
            let single = w.display_points(&[points[i]]).unwrap()[0];
            assert_eq!(
                grid[i].values, single.values,
                "constant field and independently queried shared edges"
            );
        }
    }
}
#[test]
fn sign_plan_preserves_zero_tiny_scales_and_nonfinite_fallbacks() {
    let y = json!({"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":-64.,"to_value":320.});
    let overflow = json!({"type":"minecraft:mul","argument1":1e308,"argument2":1e308});
    for v in [
        json!({"type":"minecraft:mul","argument1":1e-300,"argument2":{"type":"minecraft:mul","argument1":1e-300,"argument2":y}}),
        json!({"type":"minecraft:squeeze","argument":{"type":"minecraft:min","argument1":y,"argument2":0.}}),
        json!({"type":"minecraft:min","argument1":-2.,"argument2":{"type":"minecraft:mul","argument1":overflow,"argument2":y}}),
        json!({"type":"minecraft:squeeze","argument":{"type":"minecraft:mul","argument1":0.64,"argument2":y}}),
    ] {
        let mut d = document();
        d["settings"]["noise_router"]["final_density"] = v;
        let t = Terrain::from_document(0, &d).unwrap();
        let id = t.graph.root("final_density").unwrap();
        for (x, z) in [(-17, 31), (0, 0), (17, -31)] {
            let mut oracle = t.job(x, z, false).unwrap();
            let expected = (t.min_y..t.min_y + t.height).rev().find(|&y| {
                oracle.scratch.advance_block();
                t.graph
                    .compute(id, [x, y, z], Mode::Cell, &mut oracle.scratch)
                    > 0.
            });
            assert_eq!(t.job(x, z, false).unwrap().density_surface(x, z), expected);
        }
    }
}

#[test]
fn display_handles_water_lava_and_negative_grid_edges_without_polluting_exact_points() {
    for liquid in ["minecraft:water", "minecraft:lava"] {
        let mut d = document();
        d["settings"]["aquifers_enabled"] = json!(false);
        d["settings"]["default_fluid"] = json!({"Name":liquid});
        d["settings"]["surface_rule"] =
            json!({"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}});
        d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:y_clamped_gradient","from_y":40,"to_y":41,"from_value":1.,"to_value":-1.});
        let w = World::new(0, 0, d.clone()).unwrap();
        let oracle = World::new(0, 0, d).unwrap();
        let points: Vec<_> = (0..64).map(|i| (-9 + i % 8, -17 + i / 8)).collect();
        let grid = w.display_points(&points).unwrap();
        assert_eq!(
            grid.iter().map(|v| v.values).collect::<Vec<_>>(),
            w.display_points(&points)
                .unwrap()
                .iter()
                .map(|v| v.values)
                .collect::<Vec<_>>()
        );
        let exact = oracle.surface_points(&points).unwrap();
        for (a, b) in grid.iter().zip(&exact) {
            assert_eq!(&a.values[..3], &b.values[..3]);
            assert_eq!(&a.values[4..], &b.values[4..]);
        }
        assert_eq!(
            w.surface_points(&points)
                .unwrap()
                .iter()
                .map(|v| v.values)
                .collect::<Vec<_>>(),
            exact.iter().map(|v| v.values).collect::<Vec<_>>()
        );
    }
}

#[test]
fn structure_and_stateful_display_queries_keep_exact_semantics() {
    for stateful in [false, true] {
        let mut d = document();
        if stateful {
            d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:cache_2d",
                "argument":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}});
        } else {
            d["structure_terrain"] =
                json!([{"chunk_x":-1,"chunk_z":-1,"pieces":[],"junctions":[]}]);
        }
        let w = World::new(0, 0, d.clone()).unwrap();
        let oracle = World::new(0, 0, d).unwrap();
        let points = [(-16, -16), (-15, -15), (-14, -14)];
        let display = w.display_points(&points).unwrap();
        let exact = oracle.surface_points(&points).unwrap();
        assert_eq!(
            display.iter().map(|v| v.values).collect::<Vec<_>>(),
            exact.iter().map(|v| v.values).collect::<Vec<_>>()
        );
    }
}
