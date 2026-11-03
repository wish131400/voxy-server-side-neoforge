//! Differential checks against the recursive production path, plus cache lifecycle.
use super::{Graph, Mode};
use crate::backend::World;
use serde_json::{json, Value};
use std::{fs, path::Path};

fn fixture(dimension: &str) -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/worldgen");
    let read = |name: &str| {
        serde_json::from_str::<Value>(&fs::read_to_string(root.join(name)).unwrap()).unwrap()
    };
    let mut doc = read(&format!("{dimension}.json"));
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
fn pair(doc: Value, seed: i64) -> (World, World) {
    let mut oracle = World::new(seed, 0, doc.clone()).unwrap();
    oracle.terrain.graph.arithmetic_plan = super::arithmetic_plan::Plan::default();
    oracle.terrain.graph.shared_corner_safe.fill(false);
    (oracle, World::new(seed, 0, doc).unwrap())
}
#[test]
fn exact_display_revisits_and_distant_relocation_preserve_complete_records() {
    for dimension in ["overworld", "nether", "end", "amplified"] {
        for seed in [42, -917] {
            let (a, b) = pair(fixture(dimension), seed);
            for batch in 0..20 {
                let bx = if batch % 3 == 0 {
                    100000 + batch * 16
                } else {
                    -32 + batch * 16
                };
                let bz = if batch % 3 == 0 {
                    -100000 - batch * 16
                } else {
                    32 - batch * 16
                };
                let points: Vec<_> = (0..64)
                    .map(|i| {
                        let j = if batch % 2 == 0 { i } else { 63 - i };
                        (bx + j % 8, bz + j / 8)
                    })
                    .collect();
                for display in [false, true, false] {
                    let x = if display {
                        a.display_points(&points)
                    } else {
                        a.surface_points(&points)
                    }
                    .unwrap();
                    let y = if display {
                        b.display_points(&points)
                    } else {
                        b.surface_points(&points)
                    }
                    .unwrap();
                    for (i, (x, y)) in x.iter().zip(y).enumerate() {
                        assert_eq!(
                            x.values, y.values,
                            "{dimension} seed={seed} batch={batch} display={display} p={:?}",
                            points[i]
                        );
                    }
                }
            }
        }
    }
}
#[test]
fn exposed_stateful_and_modified_graphs_keep_the_original_path() {
    let original = fixture("overworld");
    let vertical = json!({"type":"minecraft:cache_2d","argument":{
        "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}});
    for density in [
        vertical.clone(),
        json!({"type":"minecraft:flat_cache","argument":vertical}),
        json!({"type":"minecraft:add","argument1":0.1,"argument2":original["settings"]["noise_router"]["final_density"]}),
    ] {
        let mut doc = original.clone();
        doc["settings"]["noise_router"]["final_density"] = density;
        let (a, b) = pair(doc, 42);
        for p in [
            (0, 0),
            (15, 15),
            (16, 16),
            (-1, -1),
            (-17, 3),
            (100000, -100000),
            (0, 0),
        ] {
            assert_eq!(
                a.surface_point(p.0, p.1).unwrap().values,
                b.surface_point(p.0, p.1).unwrap().values
            );
        }
    }
}

#[test]
fn compact_arithmetic_preserves_all_router_roots_bit_for_bit() {
    for dimension in ["overworld", "nether", "end", "amplified"] {
        let doc = fixture(dimension);

        for seed in [42, -917] {
            let mut a = Graph::from_document(seed, &doc).unwrap();
            a.arithmetic_plan = super::arithmetic_plan::Plan::default();
            a.shared_corner_safe.fill(false);
            let b = Graph::from_document(seed, &doc).unwrap();
            let mut roots: Vec<_> = a.roots.keys().cloned().collect();
            roots.sort();
            for mode in [
                Mode::Raw,
                Mode::Single,
                Mode::Block,
                Mode::Cell,
                Mode::Slice,
            ] {
                let mut x = a.scratch(-16, 16, 4, 8).unwrap();
                let mut y = b.scratch(-16, 16, 4, 8).unwrap();
                a.initialize_column_caches(&mut x);
                b.initialize_column_caches(&mut y);
                for i in 0..256 {
                    let p = [-16 + (i * 7) % 32, -64 + (i * 13) % 384, 16 + (i * 11) % 32];
                    for root in &roots {
                        assert_eq!(
                            a.compute(a.roots[root], p, mode, &mut x).to_bits(),
                            b.compute(b.roots[root], p, mode, &mut y).to_bits(),
                            "{dimension} seed={seed} root={root} p={p:?}"
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn arithmetic_nan_signed_zero_and_branches_remain_bit_exact() {
    use super::{Binary, Node, Unary};
    let mut g = Graph::from_document(42, &fixture("overworld")).unwrap();
    let first = g.nodes.len();
    for v in [
        0.,
        -0.,
        f64::NAN,
        f64::INFINITY,
        f64::NEG_INFINITY,
        0.5,
        -0.5,
    ] {
        g.nodes.push(Node::Constant(v));
    }
    let mut roots = vec![];
    for a in first..first + 7 {
        for b in first..first + 7 {
            for op in [Binary::Add, Binary::Mul, Binary::Min, Binary::Max] {
                let child = g.nodes.len();
                g.nodes.push(Node::Binary(op, a, b));
                let root = g.nodes.len();
                g.nodes.push(Node::Unary(Unary::Square, child));
                roots.push(root);
            }
        }
    }
    g.arithmetic_plan = super::arithmetic_plan::Plan::compile(&g);
    let mut compiled = g.scratch(0, 0, 4, 8).unwrap();
    let mut oracle = g.scratch(0, 0, 4, 8).unwrap();
    oracle.disable_column_plan();
    for mode in [
        Mode::Raw,
        Mode::Single,
        Mode::Block,
        Mode::Cell,
        Mode::Slice,
    ] {
        for &id in &roots {
            let a = g.compute(id, [3, 77, 4], mode, &mut oracle);
            let b = g.compute(id, [3, 77, 4], mode, &mut compiled);
            assert!(
                a.to_bits() == b.to_bits() || (a.is_nan() && b.is_nan()),
                "node={id} mode={mode:?} {a} {b}"
            );
        }
    }
}

#[test]
fn leases_return_on_park_reset_cancel_and_graph_rebuild() {
    let mut world = World::new(42, 0, fixture("overworld")).unwrap();
    let g = &world.terrain.graph;
    let id = g
        .nodes
        .iter()
        .enumerate()
        .find(|(id, _)| {
            g.shared_corner_safe[*id] && g.column_plan.corner_slots[*id] != super::column_plan::NONE
        })
        .unwrap()
        .0;
    let mut work = g.scratch(0, 0, 4, 8).unwrap();
    g.compute(id, [0, 64, 0], Mode::Single, &mut work);
    assert!(work.shared_corner.is_some());
    let mut second = g.scratch(0, 0, 4, 8).unwrap();
    work.release_shared_corners();
    g.compute(id, [0, 64, 0], Mode::Single, &mut second);
    assert!(second
        .shared_corner
        .as_ref()
        .unwrap()
        .get(id, [0, 64, 0])
        .is_some());
    second.reset_chunk(16, 0);
    assert!(second.shared_corner.is_none());
    let mut job = world.terrain.job(0, 0, false).unwrap();
    // A real density query leases a table, parking gives it back before budgets.
    job.block([0, 64, 0]);
    assert!(job.scratch.shared_corner.is_some());
    let parked = job.park();
    let resumed = crate::terrain::Job::resume(&world.terrain, parked);
    assert!(resumed.scratch.shared_corner.is_none());
    drop(resumed);
    world.cancel();
    assert!(g.shared_corners.take().is_none());
    drop(work);
    drop(second);
    // A graph rebuild owns a distinct pool; no prior table can answer new roots.
    let old = world.terrain.graph.shared_corners.clone();
    world
        .terrain
        .graph
        .add_root("test", &json!(0.75), &fixture("overworld"))
        .unwrap();
    assert!(!std::sync::Arc::ptr_eq(
        &old,
        &world.terrain.graph.shared_corners
    ));
}

#[test]
fn beard_exposed_caches_and_uncovered_flat_queries_do_not_share_answers() {
    let fixture = fixture("overworld");
    let vertical = json!({"type":"minecraft:cache_2d","argument":{
        "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}});
    for input in [
        json!({"type":"minecraft:beardifier"}),
        json!({"type":"minecraft:flat_cache","argument":{"type":"minecraft:beardifier"}}),
        vertical.clone(),
    ] {
        let mut doc = fixture.clone();
        doc["settings"]["noise_router"]["final_density"] =
            json!({"type":"minecraft:interpolated","argument":input});
        let g = Graph::from_document(42, &doc).unwrap();
        let root = g.roots["final_density"];
        let super::Node::Marker(_, child) = g.nodes[root] else {
            panic!("expected interpolated")
        };
        assert!(!g.shared_corner_safe[child]);
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        g.initialize_column_caches(&mut s);
        for beard in [-0.5, 0.25, -0.5] {
            s.beard = beard;
            g.compute(child, [0, 64, 0], Mode::Single, &mut s);
            assert!(s.shared_corner.is_none());
        }
    }
    // A sheltered cache can share only inside the aligned covered lattice.
    let mut doc = fixture;
    doc["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:interpolated",
        "argument":{"type":"minecraft:flat_cache","argument":vertical}});
    let g = Graph::from_document(42, &doc).unwrap();
    let super::Node::Marker(_, child) = g.nodes[g.roots["final_density"]] else {
        panic!("expected interpolated")
    };
    assert!(g.shared_corner_safe[child]);
    for (origin, p) in [(0, [32, 64, 32]), (1, [4, 64, 4])] {
        let mut s = g.scratch(origin, origin, 4, 8).unwrap();
        g.compute(child, p, Mode::Single, &mut s);
        assert!(s.shared_corner.is_none());
    }
}

#[test]
fn sheltered_stateful_cache_return_always_finishes_the_shared_lease() {
    let mut doc = fixture("overworld");
    let vertical = json!({"type":"minecraft:cache_2d","argument":{
        "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}});
    doc["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:interpolated",
        "argument":{"type":"minecraft:cache_2d","argument":{"type":"minecraft:flat_cache","argument":vertical}}});
    let g = Graph::from_document(42, &doc).unwrap();
    assert!(g.stateful_columns && !g.exposed_column_order);
    let super::Node::Marker(_, child) = g.nodes[g.roots["final_density"]] else {
        panic!("expected interpolated")
    };
    assert!(g.shared_corner_safe[child]);
    let mut s = g.scratch(0, 0, 4, 8).unwrap();
    for p in [[0, 64, 0], [4, 64, 4], [0, 128, 0], [4, 128, 4]] {
        g.compute(child, p, Mode::Single, &mut s);
        assert!(
            !s.shared_suspended,
            "early returns must not strand the sharing guard"
        );
        assert!(s.shared_corner.as_ref().unwrap().get(child, p).is_some());
    }
}
