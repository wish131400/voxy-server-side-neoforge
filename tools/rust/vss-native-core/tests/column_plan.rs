use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::{
    backend::World,
    density::{Graph, Mode},
    terrain::Terrain,
};
fn document(name: &str) -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |f: &str| {
        serde_json::from_str::<Value>(&fs::read_to_string(root.join(f)).unwrap()).unwrap()
    };
    let mut d = read(name);
    for (key, file) in [
        ("block_definitions", "blocks.json"),
        ("biomes", "biomes.json"),
        ("grass_colormap", "grass.json"),
        ("foliage_colormap", "foliage.json"),
    ] {
        d[key] = read(file);
    }
    d
}
#[test]
fn compiled_plan_matches_original_recursion_bits_across_modes() {
    for file in [
        "overworld.json",
        "amplified.json",
        "nether.json",
        "end.json",
    ] {
        let g = Graph::from_document(917, &document(file)).unwrap();
        let mut fast = g.scratch(0, 0, 4, 8).unwrap();
        let mut original = g.scratch(0, 0, 4, 8).unwrap();
        original.disable_column_plan();
        for mode in [
            Mode::Single,
            Mode::Cell,
            Mode::Raw,
            Mode::Block,
            Mode::Single,
        ] {
            for x in [-5, 0, 4, 17, 0] {
                for z in [0, 4, -1] {
                    for y in (-64..320).step_by(13) {
                        for &id in g.roots.values() {
                            let a = g.compute(id, [x, y, z], mode, &mut fast);
                            let b = g.compute(id, [x, y, z], mode, &mut original);
                            assert_eq!(
                                a.to_bits(),
                                b.to_bits(),
                                "{file} {mode:?} {x},{y},{z} root {id}"
                            );
                        }
                    }
                }
            }
        }
    }
}
#[test]
fn unknown_nonfinite_branches_are_not_pruned() {
    let mut d = document("overworld.json");
    let y = json!({"type":"minecraft:y_clamped_gradient", "from_y":-64,
        "to_y":64, "from_value":-64., "to_value":64.});
    let overflow = json!({"type":"minecraft:mul", "argument1":1e308, "argument2":1e308});
    let unstable = json!({"type":"minecraft:mul", "argument1":overflow, "argument2":y});
    d["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:min",
        "argument1":-2., "argument2":{"type":"minecraft:clamp", "input":unstable,
            "min":0., "max":1.}});
    let g = Graph::from_document(0, &d).unwrap();
    let mut fast = g.scratch(0, 0, 4, 8).unwrap();
    let mut original = g.scratch(0, 0, 4, 8).unwrap();
    original.disable_column_plan();
    let id = g.root("final_density").unwrap();
    for y in [-1, 0, 1] {
        let a = g.compute(id, [0, y, 0], Mode::Raw, &mut fast);
        let b = g.compute(id, [0, y, 0], Mode::Raw, &mut original);
        assert!(a.to_bits() == b.to_bits() || (a.is_nan() && b.is_nan()));
    }
}
#[test]
fn preliminary_interval_search_matches_lattice_scan_including_thin_layers() {
    for variant in 0..3 {
        let mut d = document("overworld.json");
        if variant > 0 {
            let y = json!({"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":-64.,"to_value":320.});
            d["settings"]["noise_router"]["initial_density_without_jaggedness"] = json!({"type":"minecraft:range_choice",
                "input":y,"min_inclusive":if variant==1 {104}else{-56},"max_exclusive":if variant==1{105}else{-55},
                "when_in_range":1.,"when_out_of_range":-1.});
        }
        let t = Terrain::from_document(0, &d).unwrap();
        let id = t.graph.root("initial_density_without_jaggedness").unwrap();
        for i in 0..64 {
            let x = 100000 + i * 17;
            let z = -100000 + i * 31;
            let mut scratch = t
                .graph
                .scratch(x & !15, z & !15, t.cell_width, t.cell_height)
                .unwrap();
            scratch.disable_column_plan();
            let mut expected = i32::MAX;
            for y in (t.min_y..t.min_y + t.height + 1)
                .step_by(t.cell_height as usize)
                .rev()
            {
                if t.graph
                    .compute(id, [x & !3, y, z & !3], Mode::Single, &mut scratch)
                    > 0.390625
                {
                    expected = y;
                    break;
                }
            }
            assert_eq!(t.job(x, z, false).unwrap().preliminary(x, z), expected);
        }
    }
}
#[test]
fn retained_surface_work_preserves_batches_eviction_and_parallel_queries() {
    let d = document("overworld.json");
    let world = World::new(917, 0, d.clone()).unwrap();
    let oracle = World::new(917, 0, d).unwrap();
    std::thread::scope(|scope| {
        let mut tasks = vec![];
        for lane in 0..4 {
            let world = &world;
            let oracle = &oracle;
            tasks.push(scope.spawn(move || {
                for k in 0..12 {
                    let cx = if k % 3 == 0 { 0 } else { k };
                    let cz = lane;
                    let positions: Vec<_> = (0..64)
                        .map(|i| (cx * 16 + i % 8, cz * 16 + i / 8 + (k % 2) * 8))
                        .collect();
                    let out = world.surface_points(&positions).unwrap();
                    let expected = oracle.surface_columns(cx, cz).unwrap();
                    for ((x, z), a) in positions.into_iter().zip(out) {
                        assert_eq!(
                            a.values,
                            expected[((x & 15) * 16 + (z & 15)) as usize].values
                        );
                    }
                }
            }));
        }
        for t in tasks {
            t.join().unwrap();
        }
    });
}

#[test]
fn exterior_boundary_reuse_matches_complete_chunks_in_all_dimensions() {
    for file in ["overworld.json", "amplified.json", "nether.json", "end.json"] {
        for seed in [0, -917] {
            let d = document(file);
            let fast = World::new(seed, 0, d.clone()).unwrap();
            let oracle = World::new(seed, 0, d).unwrap();
            for (cx,cz) in [(-3,2), (0,-1), (9,-13)] {
                let mut points: Vec<_> = (0..256).map(|i| (cx*16+i/16,cz*16+i%16)).collect();
                if seed != 0 { points.reverse(); }
                let expected = oracle.surface_columns(cx,cz).unwrap();
                for batch in points.chunks(64) {
                    let records = fast.surface_points(batch).unwrap();
                    for (&(x,z),record) in batch.iter().zip(records) {
                        assert_eq!(record.values,expected[((x&15)*16+(z&15)) as usize].values,
                            "{file} seed={seed} ({x},{z})");
                    }
                }
            }
        }
    }
}
