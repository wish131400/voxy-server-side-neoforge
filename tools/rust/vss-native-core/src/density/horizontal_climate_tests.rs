use super::{Graph, Mode};
use serde_json::{json, Value};
fn document() -> Value { serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap() }
#[test]
fn raw_cache_preserves_all_root_bits_across_y_modes_collisions_and_relocation() {
    let doc = document(); let fast = Graph::from_document(42,&doc).unwrap();
    let mut baseline = doc.clone(); baseline["vss_horizontal_climate"] = json!(false);
    let slow = Graph::from_document(42,&baseline).unwrap();
    for batch in 0..30 {
        let x = (batch * 7919) % 100000 - 50000; let z = -x + 13;
        let mut a = fast.scratch(x,z,4,8).unwrap(); let mut b = slow.scratch(x,z,4,8).unwrap();
        for mode in [Mode::Raw,Mode::Single,Mode::Block,Mode::Raw] {
            for y in [-64,63,319,-64] {
                for (name,&id) in &fast.roots {
                    assert_eq!(fast.compute(id,[x,y,z],mode,&mut a).to_bits(),
                        slow.compute(slow.root(name).unwrap(),[x,y,z],mode,&mut b).to_bits(),"{name} {mode:?} {x},{y},{z}");
                }
            }
        }
    }
    assert!(fast.horizontal_stats()["hits"].as_u64().unwrap()>0);
    fast.close_shared_corners();
    assert!(fast.horizontal_lookup(&[(0,0)]).iter().all(|b|*b==0));
}
#[test]
fn misses_are_read_only_alias_lanes_survive_root_addition_and_cancel() {
    let mut doc = document();
    let expression = doc["settings"]["noise_router"]["temperature"].clone();
    doc["settings"]["noise_router"]["vegetation"] = expression;
    let mut graph = Graph::from_document(42,&doc).unwrap();
    assert!(graph.horizontal_lookup(&[(-8,12)]).iter().all(|b|*b==0));
    let mut scratch = graph.scratch(-8,12,4,8).unwrap();
    let value = graph.compute(graph.root("temperature").unwrap(),[-8,30,12],Mode::Raw,&mut scratch);
    let bytes = graph.horizontal_lookup(&[(-8,12)]);
    assert_eq!(u64::from_le_bytes(bytes[0..8].try_into().unwrap()) & 3,3);
    assert_eq!(f64::from_le_bytes(bytes[8..16].try_into().unwrap()).to_bits(), value.to_bits());
    assert_eq!(f64::from_le_bytes(bytes[16..24].try_into().unwrap()).to_bits(), value.to_bits());
    graph.add_root("surface_test",&json!(0.25),&doc).unwrap();
    assert_eq!(graph.horizontal_lookup(&[(-8,12)]),bytes);
    graph.add_root("temperature",&json!(0.75),&doc).unwrap();
    assert!(graph.horizontal_lookup(&[(-8,12)]).iter().all(|b|*b==0));
}
#[test]
fn independent_workspaces_share_exact_inputs_concurrently() {
    let doc = document(); let graph = Graph::from_document(-917,&doc).unwrap();
    let mut original = doc.clone(); original["vss_horizontal_climate"] = json!(false);
    let baseline = Graph::from_document(-917,&original).unwrap();
    std::thread::scope(|scope| {
        for worker in 0..4 { let graph = &graph; let baseline = &baseline;
            scope.spawn(move || { for i in 0..256 {
                let (x,z) = ((i%16)*4-32,worker*32+i/16*4); let mut a = graph.scratch(x,z,4,8).unwrap();
                let mut b = baseline.scratch(x,z,4,8).unwrap();
                for name in ["temperature","vegetation","continents","erosion","depth","ridges"] {
                    for y in [-64,63,319] { assert_eq!(graph.compute(graph.root(name).unwrap(),[x,y,z],Mode::Raw,&mut a).to_bits(),
                        baseline.compute(baseline.root(name).unwrap(),[x,y,z],Mode::Raw,&mut b).to_bits()); }
                }
            }});
        }
    });
    graph.close_shared_corners();
    assert_eq!(graph.horizontal_stats()["payloadBytes"],json!(0));
}

#[test]
fn preliminary_cache_order_does_not_disable_independent_horizontal_climate() {
    let mut doc = document();
    doc["settings"]["noise_router"]["initial_density_without_jaggedness"] =
        json!({"type":"minecraft:cache_2d","argument":{
            "type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,
            "from_value":1.0,"to_value":-1.0}});
    let fast = Graph::from_document(-917, &doc).unwrap();
    doc["vss_horizontal_climate"] = json!(false);
    let slow = Graph::from_document(-917, &doc).unwrap();
    assert!(fast.initial_column_order);
    assert!(!fast.requires_complete_column_order());
    for (x,z) in [(0,0),(-17,15),(100000,-100000),(0,0)] {
        let mut a = fast.scratch(x,z,4,8).unwrap();
        let mut b = slow.scratch(x,z,4,8).unwrap();
        fast.initialize_column_caches(&mut a);
        slow.initialize_column_caches(&mut b);
        for mode in [Mode::Single,Mode::Raw,Mode::Cell,Mode::Raw] {
            for y in [319,63,-64,319] {
                a.advance_block(); b.advance_block();
                for (name,&id) in &fast.roots {
                    assert_eq!(fast.compute(id,[x,y,z],mode,&mut a).to_bits(),
                        slow.compute(slow.root(name).unwrap(),[x,y,z],mode,&mut b).to_bits(),
                        "{name} {mode:?} at {x},{y},{z}");
                }
            }
        }
    }
    assert!(fast.horizontal_stats()["hits"].as_u64().unwrap()>0);
    assert!(fast.horizontal_lookup(&[(0,0)]).iter().any(|b| *b != 0));
}
