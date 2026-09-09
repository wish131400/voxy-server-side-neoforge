use serde_json::{json, Value};
use std::{fs, path::Path};
use vss_native_core::density::{Graph, Mode};

#[test]
fn codec_spline_preserves_duplicate_knots() {
    // Epic Terrain Compatible uses two distinct values at the same location.
    // Vanilla's codec accepts both; at the knot its binary search picks the last.
    let doc = json!({"settings":{"legacy_random_source":false,"noise_router":{
        "final_density":{"type":"minecraft:spline","spline":{
            "coordinate":{"type":"minecraft:y_clamped_gradient","from_y":-4,"to_y":4,"from_value":-4,"to_value":4},
            "points":[{"location":-1,"value":2,"derivative":0},
                      {"location":0,"value":4,"derivative":0},
                      {"location":0,"value":8,"derivative":0},
                      {"location":2,"value":16,"derivative":0}]
        }}
    }}});
    let graph = Graph::from_document(0, &doc).unwrap();
    let id = graph.root("final_density").unwrap();
    let mut scratch = graph.scratch(0, 0, 4, 8).unwrap();
    for (y, expected) in [(-2, 2.), (-1, 2.), (0, 8.), (1, 12.), (2, 16.), (3, 16.)] {
        assert_eq!(
            graph.compute(id, [0, y, 0], Mode::Raw, &mut scratch),
            expected
        );
    }
}

#[test]
fn height_dependent_column_cache_remembers_only_last_column() {
    let doc = json!({"settings":{"legacy_random_source":false,"noise_router":{
        "final_density":{"type":"minecraft:cache_2d","argument":{
            "type":"minecraft:y_clamped_gradient","from_y":0,"to_y":100,"from_value":0,"to_value":1
        }}
    }}});
    let graph = Graph::from_document(0, &doc).unwrap();
    let id = graph.root("final_density").unwrap();
    let mut scratch = graph.scratch(0, 0, 4, 8).unwrap();
    assert_eq!(
        graph.compute(id, [0, 20, 0], Mode::Single, &mut scratch),
        0.2
    );
    assert_eq!(
        graph.compute(id, [0, 80, 0], Mode::Single, &mut scratch),
        0.2
    );
    assert_eq!(
        graph.compute(id, [1, 80, 0], Mode::Single, &mut scratch),
        0.8
    );
    assert_eq!(
        graph.compute(id, [0, 60, 0], Mode::Single, &mut scratch),
        0.6
    );
    assert_eq!(graph.compute(id, [0, 40, 0], Mode::Raw, &mut scratch), 0.4);
}

#[test]
fn cell_cache_outside_cell_reads_child_without_flattened_index_aliasing() {
    let doc = json!({"settings":{"legacy_random_source":false,"noise_router":{
        "final_density":{"type":"minecraft:cache_all_in_cell","argument":{
            "type":"minecraft:y_clamped_gradient","from_y":0,"to_y":100,"from_value":0,"to_value":1}},
        "stateful":{"type":"minecraft:cache_2d","argument":{
            "type":"minecraft:y_clamped_gradient","from_y":0,"to_y":100,"from_value":0,"to_value":1}}
    }}});
    let graph = Graph::from_document(0, &doc).unwrap();
    let id = graph.root("final_density").unwrap();
    let mut scratch = graph.scratch(0, 0, 4, 8).unwrap();
    assert_eq!(graph.compute(id, [20, 20, 20], Mode::Block, &mut scratch), 0.2);
    graph.prepare_cell(id, [0, 0, 0], 0, 128, &mut scratch);
    for p in [[4, 4, 0], [0, 4, 4], [-1, 4, 0], [0, 4, -1], [0, 8, 0]] {
        assert!(graph.final_cell_value(p, &scratch).is_none());
        assert_eq!(graph.compute(id, p, Mode::Block, &mut scratch), p[1] as f64 / 100.);
    }
}

#[test]
fn vanilla_density_routers() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let rows =
        fs::read_to_string(root.join("reference.jsonl")).expect("run referenceVanillaWorldgen");
    let mut count = 0;
    let mut failures = vec![];
    for row in rows.lines() {
        let row: Value = serde_json::from_str(row).unwrap();
        let name = row["settings"].as_str().unwrap();
        let doc: Value =
            serde_json::from_str(&fs::read_to_string(root.join(format!("{name}.json"))).unwrap())
                .unwrap();
        let seed = row["seed"].as_str().unwrap().parse().unwrap();
        let graph = Graph::from_document(seed, &doc).unwrap();
        let mut scratch = graph.scratch(0, 0, 4, 8).unwrap();
        for (key, values) in row["density"].as_object().unwrap() {
            let id = graph.root(key).unwrap();
            for (pos, expected) in row["points"]
                .as_array()
                .unwrap()
                .iter()
                .zip(values.as_array().unwrap())
            {
                let p = std::array::from_fn(|i| pos[i].as_i64().unwrap() as i32);
                let actual = graph.compute(id, p, Mode::Raw, &mut scratch);
                let bits = u64::from_str_radix(expected.as_str().unwrap(), 16).unwrap();
                count += 1;
                if actual.to_bits() != bits && failures.len() < 20 {
                    failures.push(format!(
                        "{name} seed={seed} {key} {p:?}: {actual:?} != {:?}",
                        f64::from_bits(bits)
                    ));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    println!("vanilla density values bit-exact={count}");
}
#[test]
fn graph_rejects_unknown_and_cycles() {
    let mut doc = json!({"settings":{"legacy_random_source":false,"noise_router":{"final_density":"test:a"}},"density_functions":{"test:a":"test:b","test:b":"test:a"}});
    assert!(Graph::from_document(0, &doc)
        .err()
        .unwrap()
        .contains("cyclic"));
    doc["settings"]["noise_router"]["final_density"] = json!({"type":"mod:unknown"});
    assert!(Graph::from_document(0, &doc)
        .err()
        .unwrap()
        .contains("unsupported"));
}
#[test]
fn vanilla_base_columns() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let mut count = 0;
    let mut failures = vec![];
    for row in fs::read_to_string(root.join("reference.jsonl"))
        .unwrap()
        .lines()
    {
        let row: Value = serde_json::from_str(row).unwrap();
        let name = row["settings"].as_str().unwrap();
        let doc: Value =
            serde_json::from_str(&fs::read_to_string(root.join(format!("{name}.json"))).unwrap())
                .unwrap();
        let seed = row["seed"].as_str().unwrap().parse().unwrap();
        let terrain = vss_native_core::terrain::Terrain::from_document(seed, &doc).unwrap();
        for expected in row["columns"].as_array().unwrap() {
            let x = expected["x"].as_i64().unwrap() as i32;
            let z = expected["z"].as_i64().unwrap() as i32;
            let column = terrain.base_column(x, z).unwrap();
            for (offset, (actual, want)) in column
                .blocks
                .iter()
                .zip(expected["blocks"].as_array().unwrap())
                .enumerate()
            {
                count += 1;
                let actual = actual.block_name(&terrain.default_block);
                let want = want.as_str().unwrap();
                if actual != want && failures.len() < 30 {
                    failures.push(format!(
                        "{name} seed={seed} ({x},{},{z}): {actual} != {want}",
                        column.min_y + offset as i32
                    ));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    println!("vanilla base column blocks exact={count}");
}
#[test]
fn vanilla_climate_and_zoom() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let mut count = 0;
    for row in fs::read_to_string(root.join("reference.jsonl"))
        .unwrap()
        .lines()
    {
        let row: Value = serde_json::from_str(row).unwrap();
        let name = row["settings"].as_str().unwrap();
        let doc: Value =
            serde_json::from_str(&fs::read_to_string(root.join(format!("{name}.json"))).unwrap())
                .unwrap();
        let seed = row["seed"].as_str().unwrap().parse().unwrap();
        let graph = Graph::from_document(seed, &doc).unwrap();
        let source = vss_native_core::climate::BiomeSource::from_document(&doc, &graph).unwrap();
        let mut scratch = graph.scratch(0, 0, 4, 8).unwrap();
        let mut last = None;
        for (i, p) in row["points"].as_array().unwrap().iter().enumerate() {
            let pos = std::array::from_fn(|j| p[j].as_i64().unwrap() as i32);
            let actual = source.sample(&graph, pos.map(|v| v >> 2), &mut scratch, &mut last);
            assert_eq!(
                actual,
                row["biomes"][i].as_str().unwrap(),
                "{name} {seed} {pos:?}"
            );
            let zoom_seed = row["zoom_seed"].as_str().unwrap().parse().unwrap();
            let expected = std::array::from_fn(|j| row["zoom"][i][j].as_i64().unwrap() as i32);
            assert_eq!(vss_native_core::biome::zoom_quart(zoom_seed, pos), expected);
            count += 2;
        }
    }
    println!("vanilla climate/zoom comparisons={count}");
}

#[test]
fn chunk_cell_traversal_preserves_columns_and_visits_each_block_once() {
    use vss_native_core::terrain::{Substance, Terrain};
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    for dimension in ["overworld", "nether", "end"] {
        let source: Value = serde_json::from_str(
            &fs::read_to_string(root.join(format!("{dimension}.json"))).unwrap()).unwrap();
        for stateful in [false, true] {
            let mut doc = source.clone();
            if stateful {
                // Exercise the stateful traversal with the same vanilla noise,
                // aquifers and ore veins, without altering their inputs.
                doc["settings"]["noise_router"]["test_order_sensitive"] = json!({
                    "type":"minecraft:cache_2d", "argument":{
                        "type":"minecraft:y_clamped_gradient", "from_y":-64,
                        "to_y":320, "from_value":-1, "to_value":1}});
            }
            let terrain = Terrain::from_document(5856955135056969302, &doc).unwrap();
            if stateful { assert!(terrain.graph.requires_complete_column_order()); }
            for (x0, z0) in [(-32, -16), (0, 16)] {
                let mut values = vec![Substance::Air; 256 * terrain.height as usize];
                let mut visited = vec![false; values.len()];
                let index = |x:i32,y:i32,z:i32| (((x-x0)*16+(z-z0))*terrain.height+y-terrain.min_y) as usize;
                terrain.job(x0,z0,false).unwrap().fill_chunk(x0,z0,|x,y,z,value| {
                    assert!((x0..x0+16).contains(&x) && (z0..z0+16).contains(&z));
                    let i=index(x,y,z);
                    assert!(!visited[i], "duplicate block");
                    visited[i]=true; values[i]=value;
                });
                assert!(visited.iter().all(|v| *v));
                let mut reference = terrain.job(x0,z0,false).unwrap();
                for x in x0..x0+16 { for z in z0..z0+16 {
                    let column=reference.column(x,z).unwrap();
                    for (dy,value) in column.blocks.iter().enumerate() {
                        let y=terrain.min_y+dy as i32;
                        assert_eq!(values[index(x,y,z)], *value,
                            "{dimension} stateful={stateful} ({x},{y},{z})");
                    }
                }}
            }
        }
    }
}

#[test]
fn chunk_cell_traversal_clips_cells_wider_than_a_chunk() {
    use vss_native_core::terrain::Terrain;
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen/end.json");
    let mut doc:Value=serde_json::from_str(&fs::read_to_string(root).unwrap()).unwrap();
    doc["settings"]["noise"]["size_horizontal"]=json!(8);
    doc["settings"]["noise_router"]["final_density"]=json!({
        "type":"minecraft:cache_2d", "argument":{
            "type":"minecraft:y_clamped_gradient", "from_y":0,
            "to_y":128, "from_value":1, "to_value":-1}});
    let terrain=Terrain::from_document(0,&doc).unwrap();
    assert!(terrain.graph.requires_complete_column_order());
    let mut visited=std::collections::HashSet::new();
    terrain.job(-16,16,false).unwrap().fill_chunk(-16,16,|x,y,z,_| {
        assert!((-16..0).contains(&x) && (16..32).contains(&z));
        assert!(visited.insert([x,y,z]));
    });
    assert_eq!(visited.len(),256*terrain.height as usize);
}

#[test]
fn sparse_cell_traversal_preserves_centres_and_neighbour_tops() {
    use vss_native_core::terrain::{Substance, Terrain};
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    for dimension in ["overworld", "nether", "end"] {
        let mut doc: Value = serde_json::from_str(
            &fs::read_to_string(root.join(format!("{dimension}.json"))).unwrap()).unwrap();
        doc["settings"]["noise_router"]["test_order_sensitive"] = json!({
            "type":"minecraft:cache_2d", "argument":{
                "type":"minecraft:y_clamped_gradient", "from_y":-64,
                "to_y":320, "from_value":-1, "to_value":1}});
        let terrain = Terrain::from_document(5856955135056969302, &doc).unwrap();
        for (x0, z0) in [(-32, -16), (0, 16)] {
            // Corners, cell boundaries, overlapping neighbours and a duplicate.
            let centres: Vec<_> = [(0,0),(3,3),(4,3),(8,8),(15,15),(3,3)].into_iter()
                .map(|(x,z)|(x+x0,z+z0)).collect();
            let mut actual = terrain.job(x0,z0,false).unwrap();
            actual.prepare_surface_columns(&centres);
            for &(x,z) in &centres {
                let expected = terrain.job(x0,z0,false).unwrap().column(x,z).unwrap();
                let column = actual.column(x,z).unwrap();
                assert_eq!(column.blocks, expected.blocks, "{dimension} centre {x},{z}");
                assert_eq!(column.surface_height, expected.surface_height);
                assert_eq!(column.ocean_floor, expected.ocean_floor);
                assert_eq!(column.fluid_height, expected.fluid_height);
                for (xx,zz) in [(x,z),(x,(z-1).max(z0)),(x,(z+1).min(z0+15)),
                    ((x-1).max(x0),z),((x+1).min(x0+15),z)] {
                    let expected = terrain.job(x0,z0,false).unwrap().column(xx,zz).unwrap();
                    let top = expected.blocks.iter().enumerate().rev()
                        .find(|(_,b)| **b != Substance::Air)
                        .map(|(dy,b)|(terrain.min_y+dy as i32,*b));
                    assert_eq!(actual.surface_top(xx,zz),top,"{dimension} neighbour {xx},{zz}");
                }
            }
        }
    }
}
