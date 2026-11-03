use super::*;
use serde_json::json;

fn select_document(nonfinite: bool) -> serde_json::Value {
    let mut doc: serde_json::Value = serde_json::from_str(include_str!(
        "../../tests/fixtures/worldgen/overworld.json"
    )).unwrap();
    let noise = json!({"type":"minecraft:add", "argument1":{
        "type":"minecraft:noise", "noise":"minecraft:temperature", "xz_scale":0.1, "y_scale":0.1},
        "argument2":{"type":"minecraft:y_clamped_gradient", "from_y":-64, "to_y":320,
            "from_value":1., "to_value":-1.}});
    let fallback = if nonfinite {
        json!({"type":"minecraft:mul","argument1":1e308,"argument2":1e308})
    } else {
        noise.clone()
    };
    doc["settings"]["noise_router"]["final_density"] = json!({"type":"minecraft:interpolated", "argument":{
        "type":"lithostitched:select", "input":{"type":"minecraft:y_clamped_gradient",
            "from_y":-64,"to_y":320,"from_value":-1.,"to_value":1.},
        "fallback":fallback,"selections":[{"range":[-0.9,0.9],"function":noise}]}});
    doc
}

#[test]
fn finite_select_reuses_lattice_edges_and_preserves_later_exact_queries() {
    for seed in [0, -917] {
        let g = Graph::from_document(seed, &select_document(false)).unwrap();
        let root = g.root("final_density").unwrap();
        for (x,z) in [(-8,-4),(0,0),(100000,100004)] {
            for bottom in [-8,0,8,64] {
                let mut fast = g.scratch(x,z,4,8).unwrap();
                let mut oracle = g.scratch(x,z,4,8).unwrap();
                oracle.disable_column_plan();
                let (lo,hi) = g.column_range(root,[x,bottom,z],bottom+7,&mut fast);
                assert_eq!(fast.corners.len(),2);
                for mode in [Mode::Cell,Mode::Block] {
                    for y in bottom..bottom+8 {
                        let p = [x,y,z];
                        let a = g.compute(root,p,mode,&mut fast);
                        let b = g.compute(root,p,mode,&mut oracle);
                        assert_eq!(a.to_bits(),b.to_bits(),"{seed} {p:?} {mode:?}");
                        assert!(b>=lo && b<=hi);
                    }
                }
                assert_eq!(oracle.corners.len(),8);
                let p = [x+1,bottom+3,z+1];
                assert_eq!(g.compute(root,p,Mode::Cell,&mut fast).to_bits(),
                    g.compute(root,p,Mode::Cell,&mut oracle).to_bits());
            }
        }
    }
}

#[test]
fn select_with_nonfinite_result_keeps_the_full_interpolation_fallback() {
    let g = Graph::from_document(0,&select_document(true)).unwrap();
    let root = g.root("final_density").unwrap();
    let Node::Marker(Marker::Interpolated(_),child) = g.nodes[root] else { panic!() };
    assert!(!g.column_plan.bounds[child].0.is_finite());
    let mut fast = g.scratch(0,0,4,8).unwrap();
    let mut oracle = g.scratch(0,0,4,8).unwrap();
    oracle.disable_column_plan();
    for y in [-64,-1,0,319] {
        let p = [0,y,0];
        assert_eq!(g.compute(root,p,Mode::Cell,&mut fast).to_bits(),
            g.compute(root,p,Mode::Cell,&mut oracle).to_bits());
    }
    assert_eq!(fast.corners.len(),oracle.corners.len());
}

#[test]
fn shared_sign_dag_stays_bounded_without_rejecting_or_changing_the_graph() {
    let mut doc = select_document(false);
    doc["settings"]["noise_router"]["final_density"] = json!(-1.);
    let mut g = Graph::from_document(0,&doc).unwrap();
    let mut root = g.root("final_density").unwrap();
    for _ in 0..36 {
        g.nodes.push(Node::Binary(Binary::Min,root,root));
        root = g.nodes.len()-1;
    }
    g.roots.insert("final_density".into(),root);
    g.column_plan = ColumnPlan::compile(&g);
    let plan = surface_plan::SurfacePlan::compile(&g).unwrap();
    assert!(plan.node_count()<=37);
    for y in [0,64,120] {
        let mut scratch = g.scratch(0,0,4,8).unwrap();
        assert_eq!(g.compute(root,[0,y,0],Mode::Cell,&mut scratch),-1.);
        assert!(matches!(plan.classify(&g,[0,y,0],y+7,&mut scratch,1e-12,false),
            surface_plan::Sign::NonPositive));
    }
}
