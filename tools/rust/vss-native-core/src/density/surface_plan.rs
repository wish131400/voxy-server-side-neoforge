//! Compile sign queries to a compact shared DAG. Leaves preserve the exact
//! interval evaluator; no floating point expression is reassociated.
use super::{Binary, Graph, Id, Marker, Node, Scratch, Unary};
const MAX_PLAN_EXPANSIONS: usize = 128;
#[derive(Clone, Copy)]
enum Op {
    Leaf(Id), Min(usize,usize), Max(usize,usize), Scale(f64,usize), Squeeze(usize),
}
pub(super) struct SurfacePlan { ops: Vec<Op>, root: usize }
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) enum Sign { Positive, NonPositive, Unknown }
impl SurfacePlan {
    pub fn compile(g: &Graph) -> Option<Self> {
        if g.requires_complete_column_order() { return None; }
        fn node(g:&Graph,id:Id,depth:usize,p:&mut SurfacePlan,slots:&mut std::collections::HashMap<Id,usize>,budget:&mut usize)->usize {
            if let Some(&slot) = slots.get(&id) { return slot; }
            let op = if depth>64 || *budget==0 { Op::Leaf(id) } else {
                *budget-=1;
                match &g.nodes[id] {
                    Node::Binary(Binary::Min,a,b) => Op::Min(node(g,*a,depth+1,p,slots,budget),node(g,*b,depth+1,p,slots,budget)),
                    // Java max propagates NaN. A positive branch may only
                    // short-circuit when neither child can be non-finite.
                    Node::Binary(Binary::Max,a,b) if [*a,*b].iter().all(|&n| {
                        let (lo,hi)=g.column_plan.bounds[n]; lo.is_finite() && hi.is_finite()
                    }) => Op::Max(node(g,*a,depth+1,p,slots,budget),node(g,*b,depth+1,p,slots,budget)),
                    Node::Binary(Binary::Mul,a,b) => {
                        let pair = match ( &g.nodes[*a], &g.nodes[*b] ) {
                            (Node::Constant(k),_) if *k>0. && k.is_finite() => Some((*k,*b)),
                            (_,Node::Constant(k)) if *k>0. && k.is_finite() => Some((*k,*a)),
                            _=>None,
                        };
                        pair.map_or(Op::Leaf(id),|(k,n)|Op::Scale(k,node(g,n,depth+1,p,slots,budget)))
                    }
                    Node::Unary(Unary::Squeeze,n) => Op::Squeeze(node(g,*n,depth+1,p,slots,budget)),
                    Node::Marker(Marker::Once|Marker::Cell,n) => return node(g,*n,depth+1,p,slots,budget),
                    _=>Op::Leaf(id),
                }
            };
            let slot=p.ops.len(); p.ops.push(op); slots.insert(id,slot); slot
        }
        let mut plan=Self {ops:Vec::new(),root:0};
        let mut budget=MAX_PLAN_EXPANSIONS;
        plan.root=node(g,*g.roots.get("final_density")?,0,&mut plan,&mut std::collections::HashMap::new(),&mut budget);
        Some(plan)
    }
    #[cfg(test)]
    pub(super) fn node_count(&self)->usize {self.ops.len()}
    pub fn classify(&self,g:&Graph,p:[i32;3],top:i32,s:&mut Scratch,epsilon:f64,whole_cell:bool)->Sign {
        self.classify_at(self.root,g,p,top,s,epsilon,whole_cell)
    }
    fn classify_at(&self,slot:usize,g:&Graph,p:[i32;3],top:i32,s:&mut Scratch,epsilon:f64,whole_cell:bool)->Sign {
        if !epsilon.is_finite() { return Sign::Unknown; }
        match self.ops[slot] {
            Op::Leaf(id) => {
                let (low,high)=if whole_cell {g.cell_range(id,p,top,s)} else {g.column_range(id,p,top,s)};
                if high<=0. {Sign::NonPositive}
                else if low>epsilon && low.is_finite() && high.is_finite() {Sign::Positive}
                else {Sign::Unknown}
            }
            Op::Min(a,b) => {
                let x=self.classify_at(a,g,p,top,s,epsilon,whole_cell);
                if x==Sign::NonPositive || a==b {return x;}
                let y=self.classify_at(b,g,p,top,s,epsilon,whole_cell);
                if y==Sign::NonPositive {y} else if x==Sign::Positive && y==Sign::Positive {Sign::Positive} else {Sign::Unknown}
            }
            Op::Max(a,b) => {
                let x=self.classify_at(a,g,p,top,s,epsilon,whole_cell);
                if x==Sign::Positive || a==b {return x;}
                let y=self.classify_at(b,g,p,top,s,epsilon,whole_cell);
                if y==Sign::Positive {y} else if x==Sign::NonPositive && y==Sign::NonPositive {Sign::NonPositive} else {Sign::Unknown}
            }
            Op::Scale(k,n) => self.classify_at(n,g,p,top,s,(epsilon+1e-12)/k,whole_cell),
            Op::Squeeze(n) => {
                if epsilon>=0.4 {return Sign::Unknown;}
                self.classify_at(n,g,p,top,s,(epsilon+1e-12)*3.,whole_cell)
            }
        }
    }
}

#[cfg(test)]
mod probe {
    use super::*;
    fn document() -> serde_json::Value {
        serde_json::from_str(
            &std::fs::read_to_string(
                std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join("tests/fixtures/worldgen/overworld.json"),
            )
            .unwrap(),
        )
        .unwrap()
    }
    #[test]
    fn max_plan_preserves_thin_layers_and_unknown_nonfinite_children() {
        use serde_json::json;
        let original: serde_json::Value=serde_json::from_str(include_str!("../../tests/fixtures/worldgen/overworld.json")).unwrap();
        let gradient=json!({"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.});
        for second in [json!(-0.1),json!({"type":"minecraft:mul","argument1":1e308,"argument2":1e308}),
            json!({"type":"minecraft:range_choice","input":gradient.clone(),"min_inclusive":0.01,
                "max_exclusive":0.02,"when_in_range":1.,"when_out_of_range":-1.})] {
            let mut doc=original.clone();
            doc["settings"]["noise_router"]["final_density"]=json!({"type":"minecraft:max",
                "argument1":{"type":"minecraft:mul","argument1":gradient.clone(),"argument2":0.01},"argument2":second});
            let g=Graph::from_document(0,&doc).unwrap();
            let root=g.root("final_density").unwrap();
            let mut fast=g.scratch(-16,-16,4,8).unwrap();
            let mut oracle=g.scratch(-16,-16,4,8).unwrap();
            for bottom in (-64..320).step_by(8) {
                for whole in [false,true] {
                    let sign=g.surface_plan.as_ref().unwrap().classify(&g,[-4,bottom,-4],bottom+7,&mut fast,1e-12,whole);
                    for y in bottom..bottom+8 {
                        let value=g.compute(root,[-4,y,-4],super::super::Mode::Cell,&mut oracle);
                        match sign {Sign::Positive=>assert!(value>0.),Sign::NonPositive=>assert!(!(value>0.)),Sign::Unknown=>{}}
                    }
                }
            }
        }
    }

    #[test]
    fn lattice_ranges_use_two_corners_and_enclose_full_interpolation() {
        let mut d = document();
        d["settings"]["noise_router"]["final_density"] = serde_json::json!({"type":"minecraft:interpolated",
            "argument":{"type":"minecraft:add",
                "argument1":{"type":"minecraft:noise","noise":"minecraft:temperature","xz_scale":0.1,"y_scale":0.1},
                "argument2":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":320,"from_value":1.,"to_value":-1.}}});
        let g = Graph::from_document(917, &d).unwrap();
        let id = g.root("final_density").unwrap();
        for x in [-8, 0, 4] {
            for z in [-4, 0, 8] {
                for base in [-64, 0, 64, 248] {
                    let mut fast = g.scratch(x, z, 4, 8).unwrap();
                    let mut full = g.scratch(x, z, 4, 8).unwrap();
                    let (lo, hi) = g.column_range(id, [x, base, z], base + 7, &mut fast);
                    assert_eq!(
                        fast.corners.len(),
                        2,
                        "must actually omit zero-weight corners"
                    );
                    for y in base..base + 8 {
                        let v = g.compute(id, [x, y, z], super::super::Mode::Cell, &mut full);
                        assert!(v >= lo && v <= hi, "{x},{y},{z}: {v} outside {lo}..{hi}");
                    }
                    assert_eq!(full.corners.len(), 8);
                    // Subsequent fluid-band queries reuse the proven edge in both
                    // interpolation orders and leave the full-cell cache untouched.
                    for mode in [super::super::Mode::Cell, super::super::Mode::Block] {
                        for y in base..base + 8 {
                            fast.advance_block();
                            full.advance_block();
                            let a = g.compute(id, [x, y, z], mode, &mut fast);
                            let b = g.compute(id, [x, y, z], mode, &mut full);
                            assert_eq!(a.to_bits(), b.to_bits(), "edge reuse {x},{y},{z} {mode:?}");
                        }
                    }
                    assert_eq!(fast.corners.len(), 2);
                    assert!(fast.cells.iter().all(Option::is_none));
                    // A following non-lattice request must load a complete cell; no
                    // partial cache entry may masquerade as eight valid corners.
                    let a = g.compute(
                        id,
                        [x + 1, base + 3, z + 1],
                        super::super::Mode::Cell,
                        &mut fast,
                    );
                    let b = g.compute(
                        id,
                        [x + 1, base + 3, z + 1],
                        super::super::Mode::Cell,
                        &mut full,
                    );
                    assert_eq!(a.to_bits(), b.to_bits());
                }
            }
        }
    }
    #[test]
    fn duplicate_spline_knots_have_finite_bounds_without_changing_values() {
        for locations in [vec![-1., 0., 0., 1.], vec![0., 0., 0.]] {
            let mut d = document();
            let points: Vec<_> = locations
                .iter()
                .enumerate()
                .map(|(i, &x)| {
                    serde_json::json!({
                "location":x,"value":i as f64-1.5,"derivative":i as f64*0.2-0.3})
                })
                .collect();
            d["settings"]["noise_router"]["final_density"] = serde_json::json!({"type":"minecraft:interpolated",
                "argument":{"type":"minecraft:spline","spline":{
                    "coordinate":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":64,"from_value":-1.,"to_value":1.},
                    "points":points}}});
            let g = Graph::from_document(0, &d).unwrap();
            let root = g.root("final_density").unwrap();
            let Node::Marker(_, child) = g.nodes[root] else {
                panic!()
            };
            assert!(g.column_plan.bounds[child].0.is_finite());
            let mut s = g.scratch(0, 0, 4, 8).unwrap();
            let mut oracle = g.scratch(0, 0, 4, 8).unwrap();
            oracle.disable_column_plan();
            for y in (-64..64).step_by(8) {
                let (a, b) = g.column_range(root, [0, y, 0], y + 7, &mut s);
                for yy in y..y + 8 {
                    let v = g.compute(root, [0, yy, 0], super::super::Mode::Cell, &mut oracle);
                    assert!(
                        v.is_finite() && v >= a && v <= b,
                        "{yy} {v} outside {a}..{b}"
                    );
                }
            }
        }
    }
    #[test]
    fn flat_sheltered_caches_keep_corner_pruning_inside_their_covered_region() {
        let mut d = document();
        d["settings"]["noise_router"]["final_density"] = serde_json::json!({"type":"minecraft:interpolated",
            "argument":{"type":"minecraft:flat_cache","argument":{"type":"minecraft:cache_2d",
                "argument":{"type":"minecraft:y_clamped_gradient","from_y":-64,"to_y":64,"from_value":1.,"to_value":-1.}}}});
        let g = Graph::from_document(0, &d).unwrap();
        assert!(g.stateful_columns && !g.exposed_column_order);
        let root = g.root("final_density").unwrap();
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        let mut oracle = g.scratch(0, 0, 4, 8).unwrap();
        for y in [0, 16, -16, 0] {
            let (lo, hi) = g.column_range(root, [4, y, 4], y + 7, &mut s);
            for yy in y..y + 8 {
                let v = g.compute(root, [4, yy, 4], super::super::Mode::Cell, &mut oracle);
                assert!(v >= lo && v <= hi);
            }
        }
        // A fresh inside query resolves one edge; an outside range remains
        // unknown, preserving the existing order-sensitive fallback boundary.
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        g.column_range(root, [4, 0, 4], 7, &mut s);
        assert_eq!(s.corners.len(), 2);
        assert_eq!(
            g.column_range(root, [20, 0, 20], 7, &mut s),
            (f64::NEG_INFINITY, f64::INFINITY)
        );
    }
    #[test]
    fn lattice_proof_uses_selected_edge_not_unrelated_large_corners() {
        for (x, edge, off_axis) in [(0, -0.078125_f64, 1e12_f64), (-4, 0.078125_f64, -1e12_f64)] {
            let mut d = document();
            d["settings"]["noise_router"]["final_density"] = serde_json::json!({
                "type":"minecraft:interpolated",
                "argument":{"type":"minecraft:range_choice",
                    "input":{"type":"lithostitched:axis","axis":"x"},
                    "min_inclusive":x as f64-0.5,"max_exclusive":x as f64+0.5,
                    "when_in_range":edge,"when_out_of_range":off_axis}});
            let g = Graph::from_document(917, &d).unwrap();
            let root = g.root("final_density").unwrap();
            let Node::Marker(_, child) = g.nodes[root] else {
                panic!()
            };
            let (global_low, global_high) = g.column_plan.bounds[child];
            assert!(global_low.abs().max(global_high.abs()) > 1e11);
            let mut s = g.scratch(x, 0, 4, 8).unwrap();
            let (low, high) = g.column_range(root, [x, 0, 0], 7, &mut s);
            assert!(
                if edge < 0. { high < 0. } else { low > 1e-12 },
                "edge={edge} bound={low}..{high}"
            );
            assert_eq!(s.corners.len(), 2);
            let mut oracle = g.scratch(x, 0, 4, 8).unwrap();
            for y in 0..8 {
                let v = g.compute(root, [x, y, 0], super::super::Mode::Cell, &mut oracle);
                assert!(
                    v >= low && v <= high,
                    "edge={edge} y={y} v={v} bound={low}..{high}"
                );
                assert_eq!(v.to_bits(), edge.to_bits());
            }
        }
    }
    #[test]
    fn unbounded_off_axis_corners_cannot_be_discarded() {
        let mut d = document();
        d["settings"]["noise_router"]["final_density"] = serde_json::json!({"type":"minecraft:interpolated",
            "argument":{"type":"minecraft:range_choice","input":{"type":"lithostitched:axis","axis":"x"},
            "min_inclusive":0,"max_exclusive":1,"when_in_range":1.,
            "when_out_of_range":{"type":"minecraft:mul","argument1":1e308,"argument2":1e308}}});
        let g = Graph::from_document(0, &d).unwrap();
        let mut s = g.scratch(0, 0, 4, 8).unwrap();
        let range = g.column_range(g.root("final_density").unwrap(), [0, 0, 0], 7, &mut s);
        assert_eq!(range, (f64::NEG_INFINITY, f64::INFINITY));
        assert_eq!(s.corners.len(), 8);
    }
    #[test]
    #[ignore = "requires VSS_DISPLAY_DOCUMENT"]
    fn interpolation_bounds() {
        let d = serde_json::from_str(
            &std::fs::read_to_string(std::env::var("VSS_DISPLAY_DOCUMENT").unwrap()).unwrap(),
        )
        .unwrap();
        let g = Graph::from_document(0, &d).unwrap();
        eprintln!(
            "stateful={} exposed={}",
            g.stateful_columns, g.exposed_column_order
        );
        fn trace(g: &Graph, id: Id, seen: &mut std::collections::HashSet<Id>) {
            if !seen.insert(id)
                || g.column_plan.bounds[id].0.is_finite() && g.column_plan.bounds[id].1.is_finite()
            {
                return;
            }
            let children = match &g.nodes[id] {
                Node::Unary(_, a) | Node::Marker(_, a) | Node::Clamp(a, _, _) => vec![*a],
                Node::Binary(_, a, b) => vec![*a, *b],
                Node::Range(a, _, _, b, c) => vec![*a, *b, *c],
                Node::Spline(s) => {
                    fn visit(s: &super::super::Spline, out: &mut Vec<Id>) {
                        if let super::super::Spline::Multipoint { coordinate, points } = s {
                            out.push(*coordinate);
                            for (_, s, _) in points {
                                visit(s, out);
                            }
                            if points.windows(2).any(|p| p[0].0 >= p[1].0) {
                                eprintln!("unordered spline knots");
                            }
                        }
                    }
                    let mut out = vec![];
                    visit(s, &mut out);
                    out
                }
                Node::Shifted(_, _, _, shifts) => shifts.to_vec(),
                _ => vec![],
            };
            if children.is_empty() {
                eprintln!(
                    "unknown leaf={id} kind={}",
                    match &g.nodes[id] {
                        Node::Beard => "beard",
                        Node::Axis(_) => "axis",
                        Node::Noise(..) => "noise",
                        Node::FastNoise(..) => "fastnoise",
                        _ => "other",
                    }
                );
            }
            for child in children {
                trace(g, child, seen);
            }
        }
        trace(
            &g,
            *g.roots.get("final_density").unwrap(),
            &mut std::collections::HashSet::new(),
        );
        for (id, n) in g.nodes.iter().enumerate() {
            if let Node::Marker(Marker::Interpolated(_), child) = n {
                eprintln!(
                    "interpolator={id} child={child} bound={:?}",
                    g.column_plan.bounds[*child]
                );
            }
        }
    }
}
